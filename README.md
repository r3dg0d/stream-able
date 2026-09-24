# Stream-able

**An OBS-like recording, livestreaming and multistreaming studio that runs inside Minecraft.**

Stream-able is the successor to [Record-able](https://modrinth.com/mod/record-able) by JoEusebe. It keeps the recorder and adds what you need to actually go live: RTMP/RTMPS streaming, multistreaming, interactive Chromium **browser sources** composited over gameplay, a professional microphone chain with local AI noise cancellation, and first-class ultrawide support - without a second application on your machine.

**One jar.** Drop `stream-able-<version>.jar` into `mods/` next to Fabric API. The browser engine library (MCEF Modern) is bundled; FFmpeg, the Chromium engine and the optional noise-cancellation models are downloaded on demand, pinned by version and SHA-256, and verified before anything runs.

---

## What it does

### The Studio (F6)
- One screen for everything: sidebar pages, record / go-live controls that are always visible, and a status bar summarising FFmpeg, encoder, canvas and outputs, frame rate, microphone and network.
- **Home**: live program preview at the canvas's true shape, safe-area guides, the region each output actually shows (for example the 16:9 crop of an ultrawide canvas), both outputs, destinations and a mixer.
- **Sources, Video, Audio, Recording, Streaming, Destinations, Stream Health, Components, Advanced** pages.
- Keyboard navigation: Tab / Shift+Tab, Enter or Space, Ctrl+1...0 for pages, Ctrl+R to record, Esc closes popups, then leaves text fields, then the Studio.
- While a Stream-able screen is open, recordings and streams keep showing the last game frame, so viewers never see your settings or keys.

### Recording
- Local recording through FFmpeg with hardware encoding (NVIDIA NVENC, Intel Quick Sync, AMD AMF, VA-API) or software x264/x265/VP9/SVT-AV1. **Auto** picks the best encoder that passed a *real test encode* on your machine.
- MP4, MKV, MOV and WebM, with container/codec combinations validated before recording starts.
- Constant-quality, VBR or CBR; optional size limit; pause/resume.
- Game audio, Plasmo Voice, and your processed microphone - optionally on its own track for editing.
- A/V alignment by measured start offsets, and a timeline that never loses time: when the game renders slower than the output rate, frames are repeated and audio is padded, so a 60 FPS recording is 60 FPS in real time.
- Files are tagged BT.709 with square pixels, so players display them correctly.

### Ultrawide and custom resolutions
- Independent **program canvas**, **recording output** and **streaming output** sizes. Presets for 16:9, 16:10, 21:9 and 32:9, "match game window", and exact custom sizes.
- Per-output scaling: **Native, Fit, Fill, Center Crop, Stretch** - never stretched silently. The Video page describes exactly what each output shows, and warns about distortion, odd sizes, encoder limits and services that may handle ultrawide differently.
- Suggested 16:9 stream size for an ultrawide canvas; record at native 21:9 or 32:9 while streaming 16:9.
- Scaling happens on the GPU before readback.

### Streaming and multistreaming
- RTMP and RTMPS to Twitch, YouTube, X, Kick or any endpoint. If a service gives you a Stream URL and a Stream Key, Stream-able can publish to it.
- Destinations sharing an encode profile are served by **one encoder** fanned out with FFmpeg's `tee` muxer; a dead ingest cannot take the healthy ones down.
- Reconnect with backoff, bounded queues and live health: delivered bitrate, encoder FPS and latency, dropped and repeated frames, network condition.
- **Destination test**: for Twitch, a real `?bandwidthtest=true` publish that does not make your channel live. For other services, which offer no known private test mode, Stream-able checks DNS, TCP, TLS and the RTMP handshake, then runs your exact encoder settings locally - and says so plainly.

### Microphone
- A full processing chain, in order: input gain, high-pass, **AI noise cancellation**, gate/expander, EQ (with a live response graph and draggable bands), de-esser, compressor, automatic gain, limiter, output gain. The EQ can sit before or after the compressor.
- **Simple mode** (preset, noise-cancellation strength, level, calibration, test) and **Advanced mode** (every stage, live gain-reduction meters, presets, diagnostics).
- **Calibration** measures room noise and your speaking level and recommends input gain and a gate threshold; nothing changes until you apply it.
- **Test recording with raw vs processed playback**, and optional live monitoring (off by default, never saved on).
- Mute, push-to-talk, push-to-mute and AI-bypass hotkeys (unbound by default).
- Mono microphones are centred; interfaces that put a mic on input 1 only are detected automatically.
- Everything runs on the DSP worker thread in 10 ms blocks with a bounded queue; the mixer clock is never slowed by the microphone.

### AI noise cancellation - local only
No audio ever leaves your computer. Stream-able runs open models through ONNX Runtime, in-process and without Python:

| Model | Rate | Measured on an Intel i9-14900K, one inference thread | Added latency |
| --- | --- | --- | --- |
| DPDFNet-2 | 48 kHz | 1.02 ms per 10 ms hop (RTF 0.12) | 40 ms model + 10 ms stage |
| DeepFilterNet2-architecture (DPDFNet baseline) | 16 kHz | 0.30 ms per hop (RTF 0.04) | 40 ms |
| GTCRN | 16 kHz | 0.39 ms per hop (RTF 0.03) | no model look-ahead |

**Auto** tries them in that order, keeps the first that runs in real time on your machine, and demotes to a lighter model if the CPU falls behind. If none can load, the voice passes through untouched and the Audio page says why.

### Browser sources
- Live Chromium pages rendered off-screen and composited over the game, with real transparency.
- Full interaction in the canvas editor (F7): clicking, scrolling, typing, clipboard shortcuts.
- Transform box with resize handles and a rotation knob, snapping, and numeric fields.
- Per-source routing: your screen, the recording, the stream - in any combination.

### Stream HUD
A compact panel (LIVE / REC time, bitrate, encoder FPS, drops, network; detailed mode adds encoder, destinations and a mic meter). It is drawn **after** the frame is captured, so it is only ever on your screen. Drag it anywhere in the canvas editor; its position is remembered as a fraction of the screen. Toggle with F8.

---

## Recording *and* streaming, independently

Recording and streaming are separate state machines that share only the composed frame. Stopping a recording never touches a running broadcast, and a stream failure never corrupts the file on disk. They run as separate FFmpeg processes on purpose.

---

## How browser-source transparency works

Getting a web overlay to composite correctly takes four things, and CSS is only one of them:

1. **The browser is created transparent.** Stream-able passes `transparent = true` to MCEF, so CEF produces a BGRA buffer with a real alpha channel.
2. **CSS is injected on every page load**: a forced-transparency base, then your own CSS on top, so redirects and widget reloads stay transparent.
3. **The pixel path preserves alpha** - CEF buffer, `GL_BGRA` upload, RGBA8 texture, compositor shader, program framebuffer.
4. **Blending is premultiplied.** If overlay edges ever look haloed, flip **Advanced > Browser pages use premultiplied alpha**.

The acceptance test is blunt: a red circle on an otherwise empty page must show Minecraft everywhere outside the circle. The bundled test page (**Sources > Add test page**) includes exactly that.

---

## Source editor controls

Open with **F7**, or from the Studio.

| Action | Result |
| --- | --- |
| Drag inside the box | Move |
| Drag a red handle | Resize, preserving aspect ratio |
| **Shift** + drag a handle | Free / non-uniform stretch |
| Drag the round knob | Rotate |
| **Shift** while rotating | Snap to 15 degrees |
| Arrow keys | Nudge 1 px (**Shift**: 10 px) |
| **Delete** | Remove the selected source |
| Drag the stream HUD | Move the HUD |
| **Esc** | Leave Interact mode, then close the editor |

In **Interact** mode, mouse and keyboard go to Chromium instead. While no editor screen is open, browser sources receive no input at all.

---

## Streaming destinations

Each destination has a name, service, enabled flag, Server URL, Stream Key and connection state. Presets exist for Twitch, YouTube and X; every field is overridable. "Custom RTMP/RTMPS" accepts anything FFmpeg can write to.

**Kick has no preset, deliberately.** Its Amazon IVS ingest host is per-account. Use **Custom RTMP/RTMPS** with the exact URL from your Kick dashboard.

A destination only reports **Live** once FFmpeg confirms it is publishing. A stream that has never published is treated as a configuration problem, not a dropped connection, and the Destinations page and Stream Health explain the likely cause.

---

## Security of stream keys

A stream key is a credential.

- Never written to logs, toasts, crash reports, FFmpeg command previews or diagnostic exports, and never in `toString()`
- Redacted centrally by `SecretRedactor`, which strips the configured keys *and* anything shaped like one; **Copy diagnostics** passes every line through it
- Entered in a masked field (show / paste / clear); a masked key cannot be copied out of the field
- Arguments reach FFmpeg as a process argument array, never through a shell
- On Linux and macOS the config file is `chmod 600` whenever it contains a key. On Windows it keeps default permissions, because Java cannot set restrictive ACLs portably.

---

## Audio

```
Game ───────────┐
Microphone ─────┤ (processed chain)
Plasmo Voice ───┼──> program mix ──> AAC ──> stream / recording
Browser audio ──┘   (see limitation below)
```

The mixer is clocked by time, not by any input: every tick emits exactly the samples elapsed time calls for, so no bus can drift from the video, and a silent input never stalls FFmpeg's muxer.

### Plasmo Voice (optional)
Plasmo Voice plays through its own OpenAL context, so Stream-able integrates through its client API (`pv-addon-streamable`): other players' voices go to the Plasmo Voice bus, and you can choose Plasmo Voice's already-processed microphone as your mic source. Stream-able warns if you stack aggressive noise suppression on top of Plasmo Voice's own. Plasmo Voice is never bundled.

### Browser audio: a known limitation
**Browser audio cannot currently be mixed into the broadcast.** CEF has `CefAudioHandler`, but the JCEF build MCEF Modern `0.3.3+mc26.1.jcef146.0.10` ships has no audio handler of any kind, so there is no API to attach to. Page audio plays on your speakers; the Sources page only offers the audio modes that can actually work. Stream-able will not capture your whole desktop to fake it.

---

## Requirements

| | |
| --- | --- |
| Minecraft | **26.1.2** (Java Edition) |
| Loader | Fabric >= 0.19.3 |
| Java | **25** (what 26.1.2 itself requires) |
| Fabric API | 0.155.2+26.1.2 |
| Everything else | Bundled or downloaded on demand - see Components |

### Components (downloaded on demand)
All pinned in `assets/streamable/runtime/manifest.json` with SHA-256, fetched over HTTPS with resume and retries, extracted with path-traversal and symlink checks, and installed atomically. The **Components** page shows progress, errors, source and licence for each, with Install / Retry / Verify.

| Component | Version | Used for |
| --- | --- | --- |
| FFmpeg (BtbN GPL build) | 8.1.3 | All encoding. The 8.1 branch is used because FFmpeg 9 builds need NVIDIA driver 610+ for NVENC. |
| Chromium engine (jcef-natives) | CEF 146.0.10 | Browser sources |
| ONNX Runtime | 1.30.0 | Noise cancellation |
| DPDFNet-2 48 kHz, DPDFNet baseline 16 kHz, GTCRN | pinned revisions | Noise cancellation models |

FFmpeg lookup order: a path you configure (Components page) > the verified managed install > `ffmpeg` on `PATH` (can be turned off). Old unpinned downloads from Record-able or Stream-able 1.0 are **not executed**, because they were never checksum-verified.

---

## Upgrading

- **From Stream-able 1.0**: your config migrates automatically (schema 1 to 2). The canvas, outputs and microphone settings are carried over; the old single microphone gain and noise toggle map onto the new chain.
- **From Record-able**: recordings stay where they are; **Advanced > Import Record-able settings** copies your settings. Record-able's files are never modified.

---

## Platforms

Linux and Windows are both first-class. Frame capture reads Minecraft's own render target through OpenGL - **no X11 screen capture, no display-server dependency**, so it works the same on Wayland. Live audio reaches FFmpeg over a loopback TCP socket on both.

**Browser sources on NixOS / Guix and other non-FHS systems.** The downloaded Chromium links against about 28 system libraries (`libnss3`, `libgbm`, `libX11`, ...). Stream-able detects missing ones with `ldd` and prints the real cause instead of the misleading "cannot open shared object file". Run the launcher in an FHS environment (`steam-run`) or add the listed libraries to `programs.nix-ld.libraries`. Recording and streaming keep working either way.

---

## Known issues

- **MCEF issue #4 (Backspace / Enter in off-screen browsers).** Stream-able sends the character events a real keyboard would, through MCEF's public API, plus a self-verifying JavaScript fallback that only acts if the page did not change. See the source for details.
- **Browser audio does not reach the broadcast** (see Audio).
- **Per-destination `tee` reporting is coarse** for destinations sharing an encoder.
- **Shader mods** replace parts of the render pipeline; Stream-able hooks the tail of `GameRenderer.render`, the most compatible point available, but exotic pipelines may interact badly.

---

## Verification status

What has been checked, and how (Stream-able 1.1.0):

- **Unit and integration tests** (`./gradlew test`, 40 test classes): runtime manifest and installer, archive safety, scaling maths, command building, frame pacing and timelines, the audio mixer, every DSP stage, the noise-cancellation stage and manager, real model inference against the pinned ONNX Runtime and model files (optional, `STREAMABLE_MODEL_DIR`), the destination tester against a local sink, config migration, diagnostics redaction, container compatibility.
- **Run on this machine (Linux, i9-14900K, RTX 4090 driver 595) in a dev client** on a virtual display with software OpenGL, so the game itself drew at about 13 FPS; encoding used the real GPU: managed FFmpeg install and encoder probing; the Studio pages at GUI scales 2 and 4 on a 2560x1080 window; a 2560x1080 NVENC recording (60 FPS constant, SAR 1:1, BT.709 tags; audio and video track lengths within 0.13 s); a live RTMP stream of the 1920x1080 center crop to a local server (104 s, 6.16 Mbps, reconnect back-off after the server stopped); the destination test (both reachable and unreachable server); browser sources on screen and in outputs; local-only routing; the stream HUD kept out of recordings; HUD dragging; microphone capture and meters.
- **Not verified on real services or hardware**: publishing to Twitch/YouTube/X, Windows, AMD/Intel encoders, 32:9 at 5120x1440 in a real game session, high-refresh pacing on a real GPU display, and hours-long recordings.

---

## Building from source

```bash
./gradlew build         # jar in build/libs/
./gradlew test          # tests, no Minecraft needed
./gradlew runClient     # dev client
./gradlew test -Dstreamable.benchmarks=true --tests '*DspBenchmarkTest*'   # DSP cost on your machine
```

Every dependency is pinned in `gradle.properties`. Minecraft 26.x ships deobfuscated, so the buildscript declares no mapping layer.

---

## Architecture

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

```
dev/streamable/
  runtime/      pinned, verified, atomic downloads (FFmpeg, Chromium, ONNX Runtime, models)
  video/        resolutions, aspect classes, scaling maths, validation
  compositor/   program canvas, GPU scaling, PBO readback, game snapshot, local overlay
  pipeline/     per-output frame pacing and capture
  ffmpeg/       discovery, capability probe, command building, processes, progress
  recording/    recording state machine, audio tracks, muxing
  streaming/    destinations, encoder groups, health, reconnect; test/ destination tester
  audio/        buses and mixer; dsp/ chain stages; ai/ noise cancellation; mic/ capture,
                calibration, presets, test, monitoring
  browser/      MCEF integration, runtime, input, CSS, audio bridge
  source/       source model, z-order; transform/ geometry and the editor
  diagnostics/  Stream Health report, redacted diagnostics
  config/       versioned settings, migration, atomic IO, Record-able import
  ui/           kit/ design system; studio/ Studio pages; HUD; canvas editor
  mixin/        the render hook and GUI renderer accessor; audio library hook
```

---

## License and attribution

Stream-able is MIT licensed and is a derivative work of **Record-able** by JoEusebe, whose copyright notice is retained in `LICENSE`. `NOTICE` lists the inherited files and every third-party component, what is bundled (MCEF Modern under LGPL-2.1, unmodified and replaceable; XZ for Java; the Inter font) and what is downloaded (FFmpeg under the GPL, run as a separate program; Chromium; ONNX Runtime; the noise models), with their licences.

No OBS Studio code or assets are used. OBS is referenced only as inspiration for workflow and terminology.
