# Changelog

All notable changes to Stream-able. Versions follow [Semantic Versioning](https://semver.org/).

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
