# Changelog

All notable changes to Stream-able. Versions follow [Semantic Versioning](https://semver.org/).

## Unreleased

### Fixed
- Studio "Stop at size" no longer refuses a recording limit above 1,000,000 MB.
  Validation only turns a negative size into 0 (no limit) and keeps any larger
  size, so the field now accepts the same non-negative values.

- Studio Page volume now stops at 100%. The slider allowed 0–200%, but
  `BrowserSource.setAudioVolume` stores at most 100%, so the upper half of
  the slider did nothing. Opacity on the same card was already 0–100%.

- Recording disk tooltips no longer say the recording stops below a hardcoded
  100 MB. They show the configured free-space floor (`diskSpaceMinFreeMb`,
  default 500). That floor is also what stops a recording, not a separate
  100 MB constant. The minimum you can set is still 100 MB.
- Studio numeric fields now use the same bounds as validation: stream audio
  bitrate 32–512 kbps, B-frames 0–8, encoder queue 8–600 frames, video bitrate
  100–200,000 kbps, maximum bitrate 100–400,000 kbps, and buffer size
  100–800,000 kbit. Recording bitrate, audio bitrate, sync offset and replay
  length, the HUD size, opacity and snap distance, and noise-reduction amount
  match their validators too. Reconnect policy is unchanged.
- Studio reconnect fields now use the same bounds as validation. The longest wait
  cannot be set below the first retry (it rises when the first retry would pass it),
  and 0 attempts means unlimited instead of being rejected by the field.
- A destination whose encoder fails to spawn (missing FFmpeg, a port that will
  not bind) now follows the same reconnect backoff as a dropped process. Previously
  only an unexpected exit armed the timer, so that destination stayed on error
  while the rest of a multistream kept going. The wait is shown in milliseconds
  when it is under a second, instead of "0 seconds".
- **Use suggested size** no longer upscales a canvas smaller than 720p, and no longer
  letterboxes an ultrawide (or leaves Stretch in place). It picks the largest common
  16:9 that fits on both axes, at most 1440p tall, and center-crops a wider canvas so
  the stream fills the frame. Same-shape and narrower canvases still show the whole
  picture.

## 1.4.2 - 2026-10-01

Upload-budget and stream continuity fix for Minecraft 26.1.2, 26.2 and 26.3 (Fabric).

### Fixed
- Budget all destination copies against a measured upload speed, reserving 20%
  for overhead and other traffic. Profiles are capped for each session without
  changing saved bitrate preferences. This prevents sustained FIFO packet loss
  when multistream upload demand exceeds upstream capacity; Stream Health now
  reports the planned total upload and marks lost stream sections as poor output.

## 1.4.1 - 2026-10-01

Reliability update for Minecraft 26.1.2, 26.2 and 26.3 (Fabric).

### Fixed
- Isolate multistream destination writes with bounded FFmpeg FIFO packet queues.
  A stalled ingest no longer blocks the shared encoder and healthy destinations;
  congested outputs discard packets and resume at a keyframe. Stream Health
  reports queue overflow episodes and labels its combined diagnosis **Output**
  rather than inferring internet quality from encoder speed.
- Kick custom RTMPS targets retain the required `/app` path. An earlier migration
  incorrectly removed it, producing `host/key` rather than `host:443/app/key`.
  Known pathless Kick/IVS endpoints are repaired on load and when building runtime
  credentials; standard ports use RTMPS/443. Other services and explicit custom
  paths remain unchanged. Saved keys are preserved.
- FFmpeg probe and mux deadlines now cover output collection. Silent or stuck
  binaries cannot block before the timeout check; output is drained concurrently
  with bounded diagnostic memory, and interruption terminates the owned process.
  Recording audio muxes have a two-minute deadline and retain separate source
  files on failure.
- Config saves create unique temporary files and restrict permissions before
  writing credentials. A stale temporary symlink cannot overwrite another file,
  and failed saves clean up their own partial file.
- Repair null streaming sections, destination lists/entries and legacy microphone
  noise settings during migration instead of quarantining an otherwise valid
  config and resetting unrelated settings or saved destinations.

### Added
- **Boot test in CI.** A Fabric client game test starts a real client and a world on each
  supported Minecraft version, presses the Studio key through the game's own input path,
  waits for the Studio and the canvas editor to open (which loads the mod's shaders and
  pipeline), takes screenshots, and checks that the render mixin is firing and that the
  OpenGL/Vulkan guard agrees with the backend in use. `./gradlew runClientGameTest -Pmc_target=<mc>`.
  Mixin targets, shaders and key numbering only fail at runtime, so this is what makes
  "supports version X" checkable.

## 1.4.0 - 2026-09-29

Stream-able now builds for **Minecraft 26.1.2, 26.2 and 26.3**, one jar each
(`stream-able-1.4.0-mc<version>-fabric.jar`).

### Added
- **Minecraft 26.1.2 and 26.3 support** alongside 26.2. Fabric API 0.155.3+26.1.2,
  0.161.0+26.2 and 0.161.0+26.3; MCEF Modern `0.3.3+mc26.1`, `+mc26.2` and `+mc26.3`
  (`jcef146.0.10`). Choose the target with `-Pmc_target=<version>`; what differs per
  version lives in `versions/<mc>.properties`, `src/compat/<line>` and
  `src/shaders/<set>` (see docs/ARCHITECTURE.md).
- **Minecraft 26.3 port.** 26.3 replaced GLFW with SDL and moved the GPU API to
  `com.mojang.renderpearl`. Input now uses `InputConstants` (SDL scancodes, left mouse
  button 1, SDL modifier masks) through `InputCompat`; the GPU package move is applied
  by `versions/26.3.remap`; the render hook is `GameRenderer.render()`; the mod's
  shaders have a 26.3 set (explicit locations, reordered `DynamicTransforms` block).
- **CI builds and tests all three versions** on Linux (26.2 also on Windows), and the
  version-sync check covers every target.
- **`FolderOpener`**: "Open logs / recordings / clips folder" launches the desktop's
  opener itself (26.3 removed Minecraft's `Util.OS.openPath`), with the path passed as
  one argument.

### Fixed
- **Crash on a Vulkan client.** Minecraft 26.2+ can render with Vulkan (Video Settings >
  Graphics API), and falls back to it when OpenGL cannot start. Stream-able's
  compositor called raw OpenGL with no context, which LWJGL treats as fatal, aborting
  the whole game (SIGABRT) the first time a frame was composed. It now checks for an
  OpenGL context, stays out of the render loop otherwise, and Stream Health shows a
  critical finding naming the setting to change ("Prefer OpenGL", then restart).
  Diagnostics no longer query GL when there is no context.
- **Studio clicks on 26.3.** The UI kit tested the left mouse button as `0`; 26.3
  numbers it `1`, so every Studio control would have ignored clicks. All checks use
  `InputConstants.MOUSE_BUTTON_LEFT`.

### Changed
- Key, modifier and mouse-button values in shared code come from `InputConstants`
  instead of GLFW, so they are correct on every supported version.
- `mod_version` 1.4.0; the fabric loader requirement is 0.19.5 on every target.

### Improved
- **Stream HUD disk ETA badge**: while recording, the compact HUD shows
  `DISK WARN` when free space at the current data rate is under 2 hours, or
  `DISK LOW` under 30 minutes — the same thresholds as Stream Health. Pure
  `DiskHudBadge` / `DiskSpaceGuardian.secondsRemaining` so Health and HUD cannot
  drift; free-space probes stay on the existing 5 s cache.

### Removed
- **Dead Record-able carry-over config**: `killMontages` and deferred-capture
  fields (`deferredCapture`, `deferredCaptureFps`, `deferredOutputFps`,
  `deferredInterpolation`) were never wired and never shown in Studio. Dropped
  from settings; stale JSON keys are ignored on load (same pattern as the
  retired recording-overlay fields). Automatic kill clips remain via
  `autoClipOnKill`.


## 1.3.2 - 2026-09-29

Stream HUD polish (cycles 17–19) on Minecraft 26.2.

### Improved
- **Stream HUD corner placement**: unused `streamHudPosition` (TL / TR / BL / BR)
  now places the panel when it has not been free-dragged. Advanced → Stream HUD
  exposes a corner control; picking a corner or Reset clears free-drag so the
  enum applies. Default stays top-right. Pure `StreamHudPlacement` helper.
- **Stream HUD detailed replay seconds**: detailed mode adds a
  `Replay N / M s` row under the fill pill (with a saving suffix while muxing).
- **Stream HUD replay fill-%**: the compact `REPLAY` pill shows how full the
  buffer is (`REPLAY 42%` while filling, `REPLAY 100%` when ready, `SAVING`
  while a clip is muxing). Saving a clip flashes `Saving clip…` immediately,
  then the result. Pure `ReplayHudBadge` helper matches Studio's buffered /
  configured seconds.
- **Config NaN hardening**: hostile `streamHudScale` / `streamHudOpacity` /
  `snapThreshold` values that are NaN reset to defaults before clamping
  (Math.clamp alone leaves NaN intact).
- **Stream HUD mic DSP badge**: when the microphone queue is under pressure
  (same thresholds as Stream Health), the compact HUD shows a `MIC DSP`
  warning pill, or `MIC DROP` when blocks were discarded. Detailed mode's
  mic meter caption matches. Audio and Health pages already covered this; the
  HUD now surfaces it without opening Studio.

### Removed
- **Dead recording-overlay config**: `showRecordingOverlay`, `overlayPosition`
  and `overlayScale` were never wired (Stream HUD replaced Record-able's
  recording overlay). Dropped from settings and from Record-able import
  mapping; stale JSON keys are ignored on load.

## 1.3.1 - 2026-09-29

Recording, capture, browser, and mic health (cycles 12–15) on Minecraft 26.2.

### Added
- **Capture health in Studio**: Studio → Video shows live GPU readback time,
  capture FPS, PBO-ring skips and buffer-exhaust counts for Recording /
  Streaming / Replay. A broken output (OpenGL capture disabled for the session)
  is named on that page and as a Stream Health CRITICAL finding — sessions no
  longer claim "Everything is running smoothly" when an output is dead.
- **Disk space guardian**: Recording refuses to start (and an active recording
  stops) when the recordings volume is at the configured block threshold or
  under 100 MB free. Studio → Recording shows free space, warn/block used-%
  and a free-space floor; Stream Health already reported time-remaining.

### Improved
- **Mic DSP overrun Stream Health**: overload / backlog findings name the queue
  depth, overrun and drop counts, and tell you to pick a lighter noise model or
  lower strength on Studio → Audio (same advice as the Audio diagnostics notice).
  Dropped blocks escalate the finding to CRITICAL. Stream Health always lists
  **Queue backlog** while the mic is capturing.
- **Browser CSS injector hardening**: user CSS is clamped to 64 KiB before
  injection; the page script waits for `DOMContentLoaded` when the document is
  still loading, keeps payloads on `window.__streamableCss`, and installs a
  one-shot `MutationObserver` so SPA head rebuilds cannot strip transparency.
  The forced-transparency base also clears `background-image`.
- Stream Health always lists **Readback skips** and **Buffers exhausted** per
  active output (not only when delayed), so backlog is visible before a stall
  finding appears.

### Fixed
- Disk guardian messages no longer carry Minecraft formatting codes or emoji
  (plain language for Studio notices and `lastError`).

## 1.3.0 - 2026-09-29

Streaming reliability (cycles 4–11) on Minecraft 26.2.

### Improved
- **Tee recovery cancel / exhaust**: Destinations → Disable cancels a pending
  automatic dedicated-encoder recovery so `tick()` will not split a user-disabled
  row. Ineligible schedules (disabled target, or no LIVE sibling left) are dropped
  instead of sticking forever; RECONNECTING rows without a healthy sibling fall
  back to ERROR with a clear message. Exhausted `ReconnectPolicy.maxAttempts`
  writes a user-facing ERROR instead of leaving the prior FFmpeg line alone.
- **TeeRecovery refuses disabled targets**: `shouldSplitOff` requires
  `destination.enabled()` so a DISABLED row cannot be split onto a new encoder.
- **Automatic tee slave recovery**: a mid-stream failed tee row is scheduled for
  recovery on a dedicated encoder using the session reconnect backoff (healthy
  siblings stay on the shared process). Destinations → Reconnect still splits
  immediately. Disable reconnect in Streaming settings to require manual recovery
  only. Solo groups and fully-failed groups still restart as a unit.
- **Tee slave Reconnect**: Destinations → Reconnect on a mid-stream failed tee
  row recovers that destination on a dedicated encoder when siblings are still
  live, instead of restarting the shared FFmpeg process (and dropping healthy
  slaves). Solo groups and fully-failed groups still restart as a unit.
- **Stream Health destination findings**: mid-stream tee slave drops and
  reconnecting rows are named in Stream Health findings (and network condition
  drops to Fair/Poor). A session with one failed destination no longer claims
  "Everything is running smoothly." Destination live/failed counts appear as a
  metric.
- **Per-destination tee reporting**: when FFmpeg's `tee` muxer logs a slave
  failure while `onfail=ignore` keeps the encoder alive, Stream-able attributes
  the stderr line to the named destination and marks only that row
  `ERROR` (Stream Health / Studio). Healthy siblings stay `LIVE`. Mid-stream
  slave drops no longer look identical across the whole group.

### Fixed
- **FFmpeg install hints** no longer point at retired gyan.dev / johnvansickle /
  evermeet hosts; desktop platforms direct users to Studio → Components (managed
  BtbN GPL build).
- **Audio method labels** describe OpenAL loopback (game) + Java Sound (mic)
  instead of DirectShow / PulseAudio / AVFoundation.
- **YouTube preset migration**: saved `rtmp://a.rtmp.youtube.com/live2` destinations
  are rewritten to the RTMPS default on load (exact former preset only).

### Docs
- `docs/WINDOWS.md`: Windows client smoke checklist and quirks for issue #3
  (live client still unverified).

### Added
- **Windows CI matrix**: GitHub Actions runs Temurin 25 `./gradlew test` and
  `build` on `ubuntu-latest` and `windows-latest` (jar artifact still uploaded
  from Linux). Version-sync also checks `minecraft_version` against CHANGELOG
  and the README requirements / jar-name lines.
- Curated GitHub issues for known gaps: [#1](https://github.com/r3dg0d/stream-able/issues/1)
  iframe / speechSynthesis browser audio,
  [#2](https://github.com/r3dg0d/stream-able/issues/2) live Twitch/YouTube/X publish
  verification, [#3](https://github.com/r3dg0d/stream-able/issues/3) Windows client smoke.
- **RTMPS NetworkProbe fixture**: local TLS + RTMP handshake unit test (never publishes).
- **Optional live ingest reachability** (`STREAMABLE_LIVE_INGEST=1`): handshake-only
  probes of Twitch / YouTube / X default hosts (no stream keys).

### Changed
- Native-runtime thread stacks size from `ProcessHandle` command-line length
  when `/proc/self/cmdline` is unavailable (Windows launchers), still floored
  at 64 MB.
- `docs/ARCHITECTURE.md` documents the Minecraft 26.2 render/GUI call sites.
- **Browser audio iframes** ([#1](https://github.com/r3dg0d/stream-able/issues/1)):
  the in-page tap is injected into every CEF frame via `CefFrame.executeJavaScript`
  (load start/end and mode/volume reconfigure), so iframe media / Web Audio reach
  the Browser Sources bus when the engine can script the frame.
- **`speechSynthesis` local honour**: Off / Stream-only cancel or mute utterances
  locally (still not capturable; Chromium plays TTS outside Web Audio).
- **Test flake**: `FFmpegProcessTimelineTest` uses 64 KiB frames so the OS pipe
  backs up during the slow-consumer sleep (16-byte frames flaked on fast GHA).
- **YouTube preset → RTMPS** ([#2](https://github.com/r3dg0d/stream-able/issues/2)):
  default ingest is now `rtmps://a.rtmps.youtube.com/live2` (plain
  `rtmp://a.rtmp.youtube.com/live2` remains the documented fallback).
- **Browser test page**: same-origin iframe audio controls + `speechSynthesis`
  button; stale "local monitoring only" Web Audio comment removed so the page
  matches the in-page tap.

## 1.2.0 - 2026-09-29

Port to Minecraft 26.2 and polish.

### Changed
- **Minecraft 26.2** (was 26.1.2): Fabric Loader 0.19.5, Fabric API 0.161.0+26.2,
  Loom 1.17.21, MCEF Modern `0.3.3+mc26.2.jcef146.0.10`, Plasmo Voice API 2.1.17.
  Simple Voice Chat API stays 2.6.24 (matches `voicechat-fabric-2.6.24+26.2`).
- **Render / GUI APIs for 26.2**: `TextureFormat` → `GpuFormat`; program capture
  reads `gameRenderer.mainRenderTarget()`; Studio screens and the F1 HUD flag go
  through `ClientGui` (`Minecraft.gui.screen()` / `setScreen()` /
  `gui.hud.isHidden()`); rounded-rect pipeline uses `GpuFormat` attributes,
  `withVertexBinding` and `PrimitiveTopology.QUADS`; stream HUD's second GUI pass
  calls `GuiRenderer.render()` with no fog buffer.
- Release jar is now named `stream-able-<ver>-mc26.2-fabric.jar`.
- LICENSE is a clean dual-copyright MIT text (derivative note lives in NOTICE) so
  GitHub can detect the MIT license.
- CI `actions/upload-artifact` bumped `@v4` → `@v7` (Node 20 deprecation).

### Added
- **GitHub Actions CI** (`.github/workflows/ci.yml`): version-sync (`mod_version` ↔ CHANGELOG)
  plus Temurin 25 `./gradlew test` and remapped jar build on every push / PR to `main`
  (landed in 1.1.0 cycle; documented here after the port).

## 1.1.0 - 2026-09-24

A large feature release. Existing configs migrate automatically (schema 1 to 2);
nothing needs to be re-entered.

### Added
- **One-jar install.** MCEF Modern is bundled (unmodified, replaceable). FFmpeg,
  the Chromium engine, ONNX Runtime and the noise models are downloaded on demand
  by a new runtime manager: pinned versions and SHA-256, HTTPS only, resumable
  downloads with retries, path-traversal and symlink protection, atomic installs,
  and a **Components** page with progress, Install / Retry / Verify, source and licence.
- **Ultrawide and custom resolutions.** Independent program canvas, recording
  output and streaming output; presets for 16:9, 16:10, 21:9 and 32:9, "match game
  window" and exact custom sizes; Native / Fit / Fill / Center Crop / Stretch scaling
  per output, done on the GPU before readback; live validation and warnings.
- **New Studio.** Sidebar navigation, always-visible Record / Go Live, status bar,
  live preview with safe-area guides and each output's real framing, and pages for
  Sources, Video, Audio, Recording, Streaming, Destinations, Stream Health,
  Components and Advanced. Full keyboard navigation.
- **Microphone chain**: high-pass, AI noise cancellation, gate/expander, EQ with a
  response graph and draggable bands, de-esser, compressor, automatic gain, limiter;
  simple and advanced modes, presets (built-in, custom, import/export), calibration,
  test recording with raw/processed playback, live monitoring (off by default),
  input-channel auto-detection for mono mics, and mute / push-to-talk / push-to-mute /
  AI-bypass hotkeys.
- **Local AI noise cancellation** through ONNX Runtime: DPDFNet-2 (48 kHz), the
  DPDFNet baseline checkpoint (DeepFilterNet2 architecture, 16 kHz) and GTCRN, with
  real-time benchmarking, automatic fallback and demotion. No audio leaves the computer.
- **Destination test**: Twitch bandwidth-test publishes; other services get a
  connectivity check (DNS, TCP, TLS, RTMP handshake) plus a local encode at your
  settings, clearly labelled as such.
- **Stream Health** page with plain-language findings, and **Copy diagnostics**
  with every stream key redacted.
- **Compact stream HUD**, drawn after capture so it never reaches outputs, draggable
  in the canvas editor.
- Microphone-only audio track for recordings; pause/resume.
- **Simple Voice Chat support** (2.6+, not bundled): other players' voices on the
  Voice chat bus with proximity fading, and SVC's microphone as a mic source.
- **Replay buffer** (F12 to save, up to 10 minutes) and **automatic clips** on
  death, kills, advancements and dimension changes, saved to `recordings/clips`.
- **Watermark**: text in any corner, with size and opacity, on recordings and
  clips, the stream, or both.
- **Browser-source audio in recordings and streams**, through an in-page tap
  (JCEF in MCEF has no audio handler); every audio mode and a per-source page
  volume. Speech synthesis and iframe audio are not captured.
- Opt-in DSP benchmark (`-Dstreamable.benchmarks=true`).

### Changed
- Recording defaults to **Auto** encoder (best encoder that passed a real test
  encode) instead of software x264.
- FFmpeg 8.1.3 (BtbN GPL build) is the managed version; FFmpeg 9 builds would
  need NVIDIA driver 610+ for NVENC.
- The encoder timeline never loses time: when the game renders slower than the
  output rate, frames are repeated and audio is padded; repeats are reported
  separately from drops.
- Output files carry full BT.709 colour tags and square pixels.
- Audio mixer emits every owed sample after a stall and bounds each bus's backlog.

### Fixed
- Several voice-chat players speaking at once were queued one after another;
  they are now mixed.
- CSS, input-shim and script injection on page load never ran, because browsers
  were looked up by an identifier that is not valid when they are created.
- The old "use FFmpeg on PATH" setting was only logged; it is now enforced.
- Recordings could start in containers that cannot hold the chosen codecs (e.g.
  WebM with H.264) and fail at the end; the combination is now checked first.
- Sources routed to "my screen" were never actually drawn on screen.
- Outputs could show Stream-able's own screens when no output was running yet.
- The destination **Reconnect** button closed its encoder group instead of restarting it.
- Installing ONNX Runtime crashed the game (SIGSEGV) under launchers that put the
  whole classpath on the command line: ONNX Runtime regex-matches the command line
  while starting up and overflowed the 1 MB thread stack. Threads that start native
  runtimes now get a stack sized to the command line.
- Streams and recordings could turn black with garbled lines at the top when
  another mod (e.g. Voxy) left `GL_PACK_ROW_LENGTH` set; the frame readback now
  resets the pack row length and skips, and restores them afterwards.

### Security
- Unpinned FFmpeg downloads from Record-able or Stream-able 1.0 are no longer
  executed, because they were never checksum-verified.
- Stream keys are masked in the UI, cannot be copied out while masked, and are
  removed from logs, diagnostics, test output and FFmpeg command previews.

### Removed
- The old Studio, HUD and confirm screens, and the unused legacy microphone
  capture class. Settings for features that are not implemented (kill montages,
  deferred capture) are kept in the config file for compatibility but are not shown.

## 1.0.0

First release: recording, RTMP/RTMPS streaming and multistreaming, browser sources
through MCEF Modern, Plasmo Voice integration.
