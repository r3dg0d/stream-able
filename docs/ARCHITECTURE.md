# Stream-able architecture

This describes how a frame and a microphone block travel through Stream-able,
and where each responsibility lives. File names are under `src/main/java/dev/streamable/`.

## Threads

| Thread | Work |
| --- | --- |
| Render thread | GL: game snapshot, program canvas composition, GPU scaling, PBO readback, local overlay, the Studio, the stream HUD pass |
| Encoder writer threads (one per FFmpeg process) | Write raw frames to FFmpeg's stdin from a bounded queue; repeat owed frames |
| FFmpeg reader threads | Parse `-progress` and stderr (redacted) |
| Mixer clock | Every 20 ms emits exactly the samples elapsed time calls for |
| Microphone capture | Reads the device, converts to 48 kHz mono float |
| DSP worker | Runs the microphone chain in 10 ms blocks from a bounded queue |
| Runtime executor | Downloads, verification, extraction, encoder probing |
| Chromium (MCEF) | Off-screen page rendering |

Nothing on the render thread blocks on disk, network, FFmpeg or audio.

## Video path

```
 GameRenderer.render (TAIL, mixin/GameRendererMixin)
   │
   ├─ compositor.snapshotGame()         clean copy of the game frame, before any local overlay
   │                                    (used as the frozen frame while a Stream-able screen is open)
   │
   ├─ VideoPipeline.onFrame()
   │     ├─ ProgramCompositor.composeProgramFrame()
   │     │     game (or snapshot) mapped onto the program canvas with gameScaling
   │     │     + sources whose routing includes the output, in z-order
   │     │     → ProgramTarget (canvas-sized GPU texture; a second one only when routings differ)
   │     │
   │     └─ per output (recording, streaming):
   │           FramePacer.framesDue(now)     exact rational pacing; time is never discarded
   │           OutputCapture.capture()       GPU scale to the output size with OutputTransform
   │                                         (Native / Fit / Fill / Center Crop / Stretch),
   │                                         Y-flip, then async readback through a 3-PBO ring
   │           OutputCapture.collect()       fenced PBOs → PooledFrame (ref-counted buffers)
   │                                         → sink.submitFrame(frame, repeat)
   │
   ├─ local overlay                     sources routed to "my screen", drawn into the main
   │                                    render target (only over gameplay or the canvas editor)
   │
   └─ StreamHud.renderAfterCapture()    second, HUD-only GUI pass: on screen, never in outputs
```

`video/OutputTransform` is the only place scaling maths lives; the Studio preview,
the Video page's descriptions and the GPU capture all use it.

`FFmpegProcess` keeps a slot queue of `(frame, repeat)`. When the game renders
slower than the output rate, the pacer reports owed frames and the writer repeats
the last picture, so a 60 FPS output stays 60 FPS in real time. Frames repeated for
this reason are counted separately from frames dropped because the encoder fell behind.

Every command is built by `ffmpeg/FFmpegCommandBuilder` as an argument list and
started with `ProcessBuilder`: no shell, and stream keys only ever appear inside
the list itself. The encode head converts RGBA to BT.709 limited range, stamps the
frames BT.709 (`setparams`), pins SAR 1:1 and tags the output.

## Audio path

```
 OpenAL loopback (game) ────────┐
 voice chat (SVC / Plasmo) ─────┤    AudioMixer (timer-clocked, 48 kHz stereo s16)
 MicrophoneProcessor output ────┼──> per-bus queues with backlog bounds, plus keyed
 browser audio tap ─────────────┘    queues per speaker / page stream, summed at mix time
                                     emitDue(): mixes what is queued, pads silence
                                      │
                                      ├─> recording: program WAV (+ mic-only WAV) → muxed at stop
                                      │   with -itsoffset from measured first-sample times
                                      ├─> streaming: LiveAudioSender (loopback TCP) → FFmpeg
                                      └─> replay buffer: in-memory ring of timestamped PCM
```

Sources that can have several simultaneous talkers (voice chat players, browser
page streams) submit with a key; each key has its own queue and they are summed
with saturation, so two people speaking at once overlap instead of playing one
after the other. Idle keys are dropped after 5 s.

### Voice chat (`compat/`)
Simple Voice Chat is reached through its plugin API (the `voicechat` entrypoint,
compile-only dependency): entity and locational voices are faded linearly to zero
at the voice range from positions cached on the client tick; static (group)
voices are unfaded. Plasmo Voice uses its client addon API. Either mod's
microphone can be the mic source, via `MicrophoneRouting`.

### Browser audio (`browser/audio`)
JCEF in MCEF Modern has no `CefAudioHandler`, so `audio-tap.js` runs inside each
page. It patches `AudioNode.prototype.connect/disconnect` so anything connected to
a live destination goes through a per-context hub (volume → monitor gain →
destination, and volume → ScriptProcessor tap), and gives media elements a
`MediaElementSource` in the tap's own context. The tap posts base64 16-bit PCM to
a `CefMessageRouter` query; `BrowserAudioTap.parse` range-checks every field
(pages are untrusted), `StreamResampler` converts to 48 kHz per stream, and the
chunk is mixed into the Browser Sources bus keyed by source and stream.

Timing matters: pages wire up audio while they load, so the tap is registered
with DevTools `Page.addScriptToEvaluateOnNewDocument` (with the Page domain
enabled). Each browser is created at `about:blank`, and the real URL is loaded
when the blank page finishes loading and the script is registered (with a
timeout fallback). JCEF fixes a browser's handlers and message routers at
creation, so a throwaway browser installs them on MCEF's shared client before
the first real source.

### Replay buffer (`recording/replay`)
`ReplayBuffer` runs a third output through FFmpeg's segment muxer: 2 s MPEG-TS
segments with forced keyframes on every boundary, wrapping after the configured
length plus a margin, and a CSV list FFmpeg appends as segments close. Audio
is kept in `ReplayAudioRing`, anchored to wall-clock arrival times. Saving
copies the newest contiguous run of segments, extracts the matching audio by
time, and joins them with the concat demuxer (video stream-copied, audio to
AAC) into `recordings/clips`. `ClipTriggers` turns per-tick observations
(death edge, a melee target dying within 5 s, an advancement toast, a dimension
change) into saves 4 s later, with a 10 s cooldown.

### Watermark (`compositor/WatermarkRenderer`)
The text is rasterised once with Java2D in the bundled Inter font into a
premultiplied texture and drawn into each output after scaling, so every output
has it in the same corner.

The microphone:

```
 device / voice-chat mic ──> ChannelSelector (auto mono detect) ──> resample to 48 kHz
   ──> MicrophoneProcessor queue (64 blocks) ──> DSP worker
         MicrophoneChain: input gain → high-pass → AI noise cancellation → gate/expander
                          → EQ ⇄ (de-esser, compressor) → AGC → limiter → output gain
         meters, test-recording taps, monitor tap
   ──> mute / push-to-talk / push-to-mute gate ──> centred stereo ──> mixer MICROPHONE bus
```

If the worker falls behind (more than 3 blocks queued), AI noise cancellation is
skipped - the voice passes through it untouched - until the queue drains; if it
falls far behind (more than 25 blocks), the stalest blocks are dropped and counted,
so latency cannot grow without bound. The mixer clock is never slowed by the microphone.

### Noise cancellation (`audio/ai`)

`NoiseCancellationStage` wraps a `StreamingEnhancer` (STFT, model, overlap-add) with a
fixed latency: dry and wet rings are indexed by input time, so switching models or
falling back never shifts the voice in time. Models load in a child-first class
loader (`IsolatedModelLoader`) so ONNX Runtime classes come from the verified
download, not the game's class path. `NoiseCancellationManager` picks a backend
(DPDFNet-2 48 kHz → DPDFNet baseline 16 kHz → GTCRN), benchmarks it on warm-up, and
demotes it if the real-time factor stays too high.

## Managed runtimes (`runtime/`)

```
 manifest.json (bundled, pinned: version, URLs, SHA-256, size, format, include globs)
   → RuntimeDownloader   HTTPS only, .part files, Range resume, mirrors, 3 attempts
   → Sha256              verified before extraction
   → ArchiveExtractor    zip / tar.gz / tar.xz; rejects "..", absolute paths, escaping
                         symlinks and hard links; size caps
   → RuntimeInstaller    staging directory → validate → install receipt → atomic move
   → ManagedRuntime      state machine (NOT_INSTALLED … READY / FAILED), progress,
                         validateInstall / postInstall hooks
```

FFmpeg, the Chromium natives (installed where MCEF Modern expects them, with the
`install.lock` jcefmaven checks, so MCEF never downloads anything itself), ONNX
Runtime and the three models are all `ManagedRuntime`s.

## Configuration

`config/StreamAbleConfig` is schema version 2. `ConfigIo.migrate` upgrades schema 1
files: the old single output size becomes independent canvas / recording / streaming
outputs, and the old microphone gain and noise toggle map onto the new chain. Files
are written atomically and are `chmod 600` on POSIX when they contain a stream key.

## UI (`ui/`)

- `kit/`: a small retained component tree on top of Minecraft's GUI - `UiNode`,
  layouts (`Column`, `Row`, `Grid`, `Adaptive`), controls, dialogs, popups, tooltips,
  toasts, keyboard focus. Rounded shapes come from a custom SDF `RenderPipeline`
  (`assets/streamable/shaders/core/rounded_rect.*`); text uses the bundled Inter font.
  Layout runs every frame, so rows that appear or disappear with live state never
  leave stale gaps.
- `studio/`: the Studio shell and one builder per page. Controls bind directly to
  the config objects and live runtime state.
- `StreamHud`, `SourceEditorScreen`: the HUD and the canvas editor.
