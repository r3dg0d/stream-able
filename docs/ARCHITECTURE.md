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

Finite probes and recording/replay muxes use `FFmpegProcesses.run`: the output
pipe drains on a daemon reader while the caller waits against the process
deadline. Diagnostics retain at most 64 KiB while excess output is drained.
Timeout or interruption terminates the owned child and its live descendants.
Recording audio muxes stop after two minutes and keep the video/WAV source files
if finalization fails; replay muxes use the same two-minute deadline.

Compatible destinations share one FFmpeg process via the `tee` muxer
(`onfail=ignore` per slave). `streaming/TeeSlaveAttributor` maps redacted stderr
error lines onto named destinations so a mid-stream slave drop marks only that
row `ERROR` while siblings stay `LIVE`; a full process death still reconnects the
group as a unit (`StreamEncoderGroup` + `ReconnectPolicy`). Failed tee rows are
scheduled for dedicated-encoder recovery on the same reconnect backoff
(`StreamEncoderGroup.scheduleTeeRecoveries` → `StreamController.tick` →
`reconnectDestination` + `TeeRecovery`); Destinations → Reconnect splits
immediately. Destinations → Disable cancels a pending schedule; exhausted
retries and ineligible schedules (disabled / no LIVE sibling) surface as
ERROR with a clear message. Healthy siblings are not restarted. `diagnostics/HealthReport` turns
those ERROR / RECONNECTING rows into Stream Health findings and Fair/Poor network
condition so a partial tee drop is never reported as "Everything is running
smoothly."

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

### Browser CSS (`browser/css`)
`BrowserCssInjector` builds the main-frame script that installs two idempotent
`<style>` blocks (forced transparency with `!important`, then user CSS). User
CSS is clamped to 64 KiB before escaping. The script stores payloads on
`window.__streamableCss`, applies immediately, retries on `DOMContentLoaded`
when the document is still loading, and installs a one-shot `MutationObserver`
so SPA widgets that rebuild `<head>` cannot leave the overlay opaque until the
next navigation. Load-handler re-injection on every main-frame `onLoadEnd`
still covers full navigations and redirects.

### Browser audio (`browser/audio`)

JCEF in MCEF Modern has no `CefAudioHandler`, so `audio-tap.js` runs inside each
frame. It patches `AudioNode.prototype.connect/disconnect` so anything connected to
a live destination goes through a per-context hub (volume → monitor gain →
destination, and volume → ScriptProcessor tap), and gives media elements a
`MediaElementSource` in the tap's own context. The tap posts base64 16-bit PCM to
a `CefMessageRouter` query; `BrowserAudioTap.parse` range-checks every field
(pages are untrusted), `StreamResampler` converts to 48 kHz per stream, and the
chunk is mixed into the Browser Sources bus keyed by source and stream.

Timing matters: pages wire up audio while they load, so the tap is registered
with DevTools `Page.addScriptToEvaluateOnNewDocument` (with the Page domain
enabled) and re-injected on every CEF frame's load start/end via
`CefFrame.executeJavaScript` (main document and iframes). Mode/volume changes
walk `CefBrowser.getFrameIdentifiers()` so iframe taps stay in sync.
`speechSynthesis` is wrapped to cancel/mute when monitor is off; its audio
still cannot enter the capture graph. Each browser is created at `about:blank`,
and the real URL is loaded when the blank page finishes loading and the script
is registered (with a timeout fallback). JCEF fixes a browser's handlers and
message routers at creation, so a throwaway browser installs them on MCEF's
shared client before the first real source.

### Capture health (`compositor/OutputCapture` → Studio / Stream Health)
Each output tracks average GPU readback time, PBO-ring skips (three in-flight
readbacks) and buffer-pool exhaustion. `VideoPipeline.OutputStats.broken` is set
when capture or collect throws; Stream Health emits a CRITICAL finding and the
Video page's Capture health card shows Broken until the session is restarted.
Healthy sessions still surface skip/exhaust metrics so backlog is visible early.

### Disk space (`recording/DiskSpaceGuardian`)
Before a recording starts, and every 5 s while one is active, Stream-able
queries the recordings volume's usable space. Configurable warn / block used-%
and a free-space floor live on `RecordingSettings`; under 100 MB free always
blocks. A blocked start returns the message as `lastError`; a mid-recording
block asks `StreamAbleClient` to stop (same path as the size limit). Threshold
math is pure (`evaluate`) so unit tests do not need a real `FileStore`.
Recording ETA (`secondsRemaining`) divides free bytes by the measured write
rate (or configured bitrate before 5 s); Stream Health findings and the Stream
HUD `DISK WARN` / `DISK LOW` pills (`ui/DiskHudBadge`) share those thresholds
(2 h / 30 min) and the client's 5 s free-space cache.

### Replay buffer (`recording/replay`)
`ReplayBuffer` runs a third output through FFmpeg's segment muxer: 2 s MPEG-TS
segments with forced keyframes on every boundary, wrapping after the configured
length plus a margin, and a CSV list FFmpeg appends as segments close. Audio
is kept in `ReplayAudioRing`, anchored to wall-clock arrival times. Saving
copies the newest contiguous run of segments, extracts the matching audio by
time, and joins them with the concat demuxer (video stream-copied, audio to
AAC) into `recordings/clips`. `ClipTriggers` turns per-tick observations
(death edge, a melee target dying within 5 s, an advancement toast, a dimension
change) into saves 4 s later, with a 10 s cooldown. The Stream HUD shows fill
percent via `ui/ReplayHudBadge` (`REPLAY N%` / `REPLAY 100%` / `SAVING`) from
`bufferedSeconds()` vs `configuredSeconds()`, flashes on save start/result, and
in detailed mode adds a `Replay N / M s` row. Placement uses
`ui/StreamHudPlacement` (corner enum when undragged; free-drag fractions otherwise).

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
Stream Health lists DSP time, queue backlog, overruns and drops while the mic is
capturing; an overload finding names those counts and points at Studio → Audio
(lighter noise model / lower strength). Dropped audio is CRITICAL. The Stream HUD
surfaces the same pressure as a compact `MIC DSP` / `MIC DROP` pill (`ui/MicHudBadge`)
even when detailed HUD mode is off.

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
Gson ignores unknown JSON keys on load, so retired Record-able carry-overs
(`killMontages`, deferred-capture fields, recording-overlay fields) drop out of
`stream-able.json` on the next save. `LegacyRecordableImport` maps a surviving
`recordable.json` into recording settings and likewise skips those retirees.

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

## Supported Minecraft versions and the compat layer

Stream-able builds **one jar per Minecraft version**: 26.1.2, 26.2 and 26.3. All
three ship Java 25 and are deobfuscated (mojmap), so there is no mapping layer and
no `remapJar`. Choose the target with `-Pmc_target=<version>` (default 26.2).

Almost all of the code is shared. What differs lives in four places, each selected
by the target's `versions/<mc>.properties`:

| Mechanism | What it holds | Where |
| --- | --- | --- |
| `compat_dir` | Java that touches a Minecraft surface whose shape differs. Each version has its own copy of the same classes; shared code only calls those. | `src/compat/mc26_1`, `mc26_2`, `mc26_3` |
| `shader_set` | GLSL for the mod's own pipeline (26.3 needs explicit `layout(location)` and a reordered `DynamicTransforms` block). | `src/shaders/glsl330`, `src/shaders/locations` |
| `versions/<mc>.remap` | Pure package moves, applied to the sources before compiling (26.3 moved the GPU API from `com.mojang.blaze3d.*` to `com.mojang.renderpearl.*`). Only moves belong here. | `versions/26.3.remap` |
| Dependencies | Minecraft, Fabric API, MCEF Modern and loader for that line. | `versions/<mc>.properties` |

The compat classes, and what changes in each:

| Class | 26.1.2 | 26.2 | 26.3 |
| --- | --- | --- | --- |
| `util/ClientGui` | screen, `setScreen` and the F1 flag on `Minecraft` | on `Minecraft.gui` | as 26.2 |
| `compat/RenderCompat` | `getMainRenderTarget()`, `getGameRenderState()`, `TextureFormat.RGBA8`, fog buffer passed to `GuiRenderer.render` | `gameRenderer.mainRenderTarget()`, `gameRenderState()`, `GpuFormat.RGBA8_UNORM`, `render()` | as 26.2 |
| `compat/InputCompat` | GLFW (`Type.KEYSYM`, `glfwGetMouseButton`, `KeyEvent.scancode()`) | as 26.1.2 | SDL (`Type.KEYBOARD`, `SDL_GetMouseState`, `KeyEvent.keycode()`, `KEY_LGUI`/`KEY_RGUI`) |
| `ui/kit/UiPipelines` | `VertexFormatElement` statics, `withVertexFormat` | `GpuFormat` attributes, `withVertexBinding`, `PrimitiveTopology` | as 26.2 |
| `mixin/GameRendererAccessor` | also exposes `fogRenderer` | `guiRenderer`, `useUiLightmap` | as 26.2 |
| `mixin/GameRendererMixin` | injects `render(DeltaTracker, boolean)` | same | injects `render()` |

Rules that keep the split honest:

- **Input values come from `InputConstants`, never from literals or GLFW.** 26.3
  renumbers everything: keys are SDL scancodes (`KEY_A` is 4, not 65), the left
  mouse button is 1 (not 0), `REPEAT` is -1 and `MOD_SHIFT` is a two-bit mask. Use
  `InputConstants.KEY_*`, `MOD_*` and `MOUSE_BUTTON_*`, and test modifiers with
  `(mods & MOD_X) != 0`, never `==`.
- **Anything that only fails at runtime must be run.** Mixin targets, shaders and
  uniform-block layouts are strings; a compile can pass while 26.3 aborts. Boot each
  target in a dev client before calling it supported.
- **No raw OpenGL without a context.** `compositor/GraphicsBackend` checks that the
  render thread has GL capabilities; on a Vulkan client (an option since 26.2, and
  a fallback when OpenGL cannot start) `StreamAbleClient.onFrameRendered` returns
  early and Stream Health explains the fix, instead of LWJGL aborting the JVM.

To add a version: create `versions/<mc>.properties`, reuse the closest `compat_dir`
and `shader_set` (or copy them), add the target to the CI matrix, and run the
client.

## Platform notes

- Windows client smoke checklist and quirks: [`WINDOWS.md`](WINDOWS.md) (issue #3).
