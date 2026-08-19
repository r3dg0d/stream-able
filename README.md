# Stream-able

**An OBS-like recording, livestreaming and multistreaming studio that runs inside Minecraft.**

Stream-able is the successor to [Record-able](https://modrinth.com/mod/record-able) by JoEusebe. It keeps the recorder and adds everything you need to actually go live: RTMP/RTMPS streaming, multistreaming to several services at once, and real interactive Chromium **browser sources** composited over gameplay — alerts, chat widgets, goals, custom HTML — without a second application on your machine.

The idea is simple: **Minecraft itself becomes the streaming studio.**

---

## What it does

### Recording
Everything Record-able did, preserved:

- Local gameplay recording via FFmpeg, with automatic FFmpeg download and checksum verification
- Hardware encoding — NVIDIA NVENC, AMD AMF, Intel Quick Sync, VA-API — with software x264 fallback
- MP4, MKV, MOV and WebM containers
- Configurable resolution, frame rate, bitrate and quality
- Game audio via OpenAL loopback, plus microphone with gain, push-to-talk and noise suppression
- Separate audio tracks, replay buffer, auto-clips, deferred capture and crash recovery
- Microsecond-accurate A/V alignment: audio is muxed in afterwards with a *measured* start offset, which is what stops long recordings drifting

### Streaming
- RTMP and RTMPS to Twitch, YouTube, X, Kick, or any other endpoint
- Protocol-based: if a service gives you a **Stream URL** and a **Stream Key**, Stream-able can publish to it. No platform API, no OAuth, nothing to break when a service changes its dashboard.
- Automatic encoder detection that runs a **real test encode** rather than trusting `ffmpeg -encoders`
- Reconnect with exponential backoff, bounded frame queues, and live health diagnostics

### Multistreaming
- Enable as many destinations as you like
- Destinations sharing an encode profile are served by **one encoder** and fanned out with FFmpeg's `tee` muxer — three services cost one encode, not three
- Each `tee` slave carries `onfail=ignore`, so a dead ingest cannot take the healthy ones down
- A destination with its own profile transparently gets its own encoder, and you are warned about the extra CPU/GPU cost
- Bandwidth estimator, because sharing an encoder does **not** share upstream: three 12 Mbps outputs still need ~36 Mbps

### Browser sources
- Live Chromium pages rendered off-screen and composited over the game
- Transparent backgrounds, so alert overlays sit on gameplay with no black box
- Full interaction: clicking, scrolling, typing, clipboard shortcuts
- OBS-style transform editor with a red bounding box, eight resize handles and a rotation knob
- Per-source routing: visible on your screen, in the recording, on the stream — in any combination

---

## Recording *and* streaming, independently

Recording and streaming are separate state machines that share only the composed frame:

```
Not recording + streaming
Recording     + not streaming
Recording     + streaming
```

Stopping a recording never touches a running broadcast, and a stream failure never corrupts the file on disk. They run as separate FFmpeg processes on purpose — modest duplication is worth more than a clever shared encoder that can ruin a recording.

---

## How browser-source transparency works

Getting a web overlay to composite correctly takes four things, and CSS is only one of them:

1. **The browser is created transparent.** Stream-able passes `transparent = true` to MCEF, so CEF produces a BGRA buffer with a real alpha channel instead of an opaque page.
2. **CSS is injected on every page load**, in two blocks: a forced-transparency base with `!important`, then your own CSS on top. Re-applying on every load matters — a redirect or a widget's own reload would otherwise leave the overlay opaque.
3. **The pixel path preserves alpha** — CEF buffer → `GL_BGRA` upload → RGBA8 texture → compositor shader → program framebuffer, with no stage flattening it onto black.
4. **Blending is premultiplied.** Chromium hands out premultiplied alpha, so the compositor blends with `(ONE, ONE_MINUS_SRC_ALPHA)` and scales all four channels by the source opacity. If overlay edges ever look haloed, flip **Advanced → Premultiplied browser alpha**.

The acceptance test is deliberately blunt: a red circle on an otherwise empty page must show Minecraft everywhere outside the circle. No black rectangle, no white rectangle. The bundled test page (**Advanced → Add browser test page**) includes exactly that.

The transparency preset offered in the UI:

```css
body {
    background-color: rgba(0, 0, 0, 0);
    margin: 0px auto;
    overflow: hidden;
}
```

---

## Source editor controls

Open with **F7**, or from the Studio.

```
              O   <- rotation knob
              |
     #--------#--------#
     |                 |
     |  Browser Source |
     |                 |
     #--------#--------#
```

| Action | Result |
| --- | --- |
| Drag inside the box | Move |
| Drag a red handle | Resize, preserving aspect ratio |
| **Shift** + drag a handle | Free / non-uniform stretch |
| Drag the round knob | Rotate |
| **Shift** while rotating | Snap to 15° |
| Arrow keys | Nudge 1 px |
| **Shift** + arrows | Nudge 10 px |
| **Delete** | Remove the selected source |
| **Esc** | Leave Interact mode, then close the editor |

Snapping to the canvas centre, canvas edges and other sources is on by default and can be turned off (**Advanced → Snap px = 0**).

The numeric properties — `Width`, `Height`, `PosX`, `PosY`, `Rot` — are the *same values* the handles edit, so dragging and typing always agree.

### Transform vs Interact

| Mode | Mouse and keyboard go to |
| --- | --- |
| **Transform** | The editor: select, move, resize, rotate |
| **Interact** | Chromium: clicks, scrolling, typing, clipboard |

While no editor screen is open, browser sources receive **no input at all** — movement keys, attacks and item use behave exactly as they would without the mod.

---

## Streaming destinations

Each destination has a name, platform, enabled flag, Stream URL, Stream Key and connection state (`Offline`, `Connecting`, `Live`, `Reconnecting`, `Error`).

Presets exist for Twitch, YouTube and X, and every field is overridable — ingest hostnames change, and a preset should never be a cage. "Custom RTMP/RTMPS" accepts anything FFmpeg can write to.

**Kick has no preset, deliberately.** Its Amazon IVS ingest host and path are per-account, so no default can be correct, and a preset you must overwrite before it works is worse than none. Use **Custom RTMP/RTMPS** with the exact URL from your Kick dashboard (`rtmps://<prefix>.global-contribute.live-video.net/`) and your stream key. Destinations saved under the old Kick preset keep working — they load as custom entries with their credentials intact.

When a destination fails you can **Reconnect** it, **Disable** it, or **Copy diagnostic** (FFmpeg output with credentials stripped) without taking the rest of the broadcast offline.

### Connected vs. merely started

A destination only reports **Live** once FFmpeg confirms it is actually publishing — a spawned process proves nothing, since FFmpeg can start, fail the RTMP handshake and exit a second later.

A stream that has *never* published is treated as a configuration problem rather than a dropped connection: it retries a few times and then reports what went wrong, instead of looping "Reconnecting…" forever. The usual causes are a stale stream key, a stream not enabled on the service's dashboard, or an ingest URL missing its application path — Stream-able rejects a host-only URL up front, because `rtmps://host/KEY` with no `/app` is the single most common paste mistake and FFmpeg only reports it as a bare `Input/output error`.

---

## Security of stream keys

A stream key is a credential: anyone holding it can broadcast to your channel.

- Never written to logs, toasts, crash reports or diagnostic exports
- Never included in `toString()` — `StreamingCredentials` masks it structurally
- Redacted centrally by `SecretRedactor`, which strips both keys it has been told about *and* anything that merely looks like one in FFmpeg output
- Entered in a password-style field with reveal, paste and clear
- On Linux and macOS the config file is `chmod 600` whenever it contains a key

**On Windows** the config is a plain file with default permissions — Java cannot set restrictive ACLs portably. If you share the machine, that is worth knowing.

---

## Audio

Stream-able mixes a stereo program mix for the broadcast while local recordings keep their separate tracks:

```
Game ───────────┐
Microphone ─────┤
Plasmo Voice ───┼──> program mix ──> AAC ──> stream
Browser audio ──┘
```

### Plasmo Voice

Proximity chat needs explicit integration: Plasmo Voice does **not** play through Minecraft's audio device, it opens its own OpenAL context, so the loopback capture that picks up game audio never sees it. Without integration, voice chat is simply absent from recordings and streams.

Stream-able registers a Plasmo Voice addon (`pv-addon-streamable`) that captures two things:

- **Other players' voices** — decoded audio from `AudioSourceWriteEvent`, into the Plasmo Voice bus.
- **Your microphone** — from `AudioCaptureProcessedEvent`, i.e. the signal *after* Plasmo Voice's own noise suppression, gain and activation gating. Viewers therefore hear exactly what other players hear, and the mic is only live while you are actually transmitting rather than permanently open.

Both are observation only — neither event is cancelled or modified, so voice chat behaves exactly as it would without Stream-able. The integration is optional: with the mod absent, the addon class is never loaded and the Audio section says so. Toggle it with **Audio → Capture voice chat**.

Simple Voice Chat is not supported; this pack uses Plasmo Voice.

### Timing

The mixer is clocked by a timer rather than by the game bus. Every tick emits exactly as many samples as elapsed time calls for, mixing in whatever each bus has queued and padding with silence otherwise. That keeps the byte count exactly proportional to elapsed time — so the microphone cannot drift away from the video — and, just as importantly, means the audio stream never goes idle. A silent input would stall FFmpeg's muxer waiting for something to interleave against the video, which stops the broadcast entirely.

### Browser audio: a known limitation

**Browser audio cannot currently be mixed into the broadcast.** This is a genuine upstream gap, not an omission:

CEF exposes `CefAudioHandler` (`OnAudioStreamPacket`), which would deliver per-browser PCM cleanly. **That handler does not exist in the JCEF build this mod targets.** MCEF Modern `0.3.3+mc26.1.jcef146.0.10` bundles `me.friwi:jcef-api` at `cef-146.0.10`, whose `org.cef.handler` package contains display, load, render, keyboard, focus, lifespan and request handlers — and no audio handler of any kind. There is no Java API to attach to, with or without a patched MCEF.

What this means in practice:

- You **hear** alert sounds — Chromium plays to the system output device
- Viewers **do not**, unless you capture system audio yourself
- Per-source audio settings are still stored and shown, ready for when the capability lands
- Stream-able will not silently capture your desktop audio to fake support; that would capture every sound on the machine, not just the overlay

---

## Requirements

| | |
| --- | --- |
| Minecraft | **26.1.2** (Java Edition) |
| Loader | Fabric ≥ 0.19.3 |
| Java | **25** (what 26.1.2 itself requires) |
| Fabric API | 0.155.2+26.1.2 |
| Browser sources | MCEF Modern `0.3.3+mc26.1.jcef146.0.10` — optional |
| Encoding | FFmpeg, downloaded on request or found on `PATH` |

### MCEF setup

Browser sources need [MCEF Modern](https://modrinth.com/mod/mcef-modern). Install it like any Fabric mod.

The pinned build is **`0.3.3+mc26.1.jcef146.0.10`** — the release for the Minecraft 26.1 line, whose `fabric.mod.json` declares `"minecraft": ">=26.1"` and therefore accepts 26.1.2. Do **not** substitute `0.3.3+mc26.2...`; that targets Minecraft 26.2.

MCEF is a **suggested**, not required, dependency. Without it — or if Chromium fails to start — Stream-able logs the reason, shows *"Browser Sources unavailable"*, and recording and streaming carry on working. Chromium downloads itself on first use, asynchronously; sources show a placeholder until it is ready.

### FFmpeg setup

Stream-able looks for FFmpeg in this order:

1. A path you configured
2. Its own bundle directory (`.minecraft/stream-able/ffmpeg`)
3. **Record-able's bundle directory** (`.minecraft/recordable/ffmpeg`) — so upgrading does not download a second copy
4. `PATH`

---

## Upgrading from Record-able

- **Your recordings are safe.** Stream-able defaults to the same `recordings` folder. Nothing is moved, renamed or deleted; existing videos simply appear.
- **Your settings can come with you.** If `config/recordable.json` exists, the Studio home page offers to import it. The old file is never modified, so going back to Record-able still works.
- **Your FFmpeg download is reused** (see above).

Stream-able writes its own config to `config/stream-able.json` and does not touch Record-able's.

---

## Platforms

Linux and Windows are both first-class.

Frame capture reads Minecraft's own render target through OpenGL, so there is **no X11 screen capture and no display-server dependency** — it works the same under Wayland. Live audio reaches FFmpeg over a loopback TCP socket rather than a named pipe, because `mkfifo` does not exist on Windows and Java cannot create a Win32 named pipe without native code.

Platform-specific behaviour is isolated rather than assumed:

| Concern | Linux | Windows |
| --- | --- | --- |
| FFmpeg binary | `ffmpeg` | `ffmpeg.exe`, auto-downloaded from gyan.dev |
| Live audio transport | loopback TCP | loopback TCP (no `mkfifo` needed) |
| Frame capture | Minecraft's render target via OpenGL — no X11, works on Wayland | same |
| Browser native | `libjcef.so` | `jcef.dll` |
| Config permissions | `chmod 600` when it holds a stream key | default ACLs — see the security note |
| Microphone | Java Sound (ALSA/PipeWire) | Java Sound (WASAPI/DirectSound) |

If browser sources fail to start on Windows, the cause is almost always a missing **Microsoft Visual C++ Redistributable (x64)** or an interrupted runtime download; Stream-able detects the failure and says so rather than printing the raw linker error.

---

## Known issues

**MCEF issue #4 — editing keys in off-screen browsers.**
[Upstream issue](https://github.com/DimasKama/mcef-modern/issues/4): MCEF forwards a key press as an AWT `KEY_PRESSED`, which java-cef translates to `KEYEVENT_RAWKEYDOWN`. There is no path to `KEYEVENT_KEYDOWN`, and Blink's editing commands for Backspace and Enter are not reached from a bare raw-keydown in the OSR pipeline. The symptom is that you can type `hello` into a page but cannot delete a character or submit the field.

Stream-able handles this in two layers:

1. **Correct event emulation.** A real keyboard produces *both* a key-down and a character message for these keys — `WM_CHAR 0x08` for Backspace, `0x0D` for Enter. MCEF already exposes `onCharTyped`, which maps to `KEYEVENT_CHAR`, so Stream-able sends the character event the platform would have sent. This uses only MCEF's public API: no fork, no reflection, no patched jar. Printable keys are excluded — Minecraft already delivers those, and synthesising a second one would type every letter twice. Shortcuts (`Ctrl+…`) are excluded too, so `Ctrl+A`/`C`/`V`/`X` keep working.
2. **A self-verifying JavaScript fallback**, injected on every page load. It watches for an editing key, lets the browser's own default action run first, and performs the edit itself *only if the DOM verifiably did not change*. It therefore cannot double-delete, only ever touches the focused editable element, and goes dormant automatically if MCEF or JCEF fixes the underlying bug — with no configuration.

It deliberately does **not** blanket-run `document.execCommand('delete')` on every Backspace, which would corrupt input on pages where the native path already works.

**Browser sources on NixOS / Guix and other non-FHS systems.**
MCEF downloads a prebuilt Chromium. On a distribution without `/usr/lib` it cannot find the ~28 system libraries it links against (`libnss3`, `libgbm`, `libX11`, `libstdc++`, …), and Linux reports this as the misleading

```
.../libjcef.so: cannot open shared object file: No such file or directory
```

naming a file that is present. Stream-able detects this: it checks whether the library actually exists, runs `ldd` to list what is genuinely missing, and prints the real cause plus NixOS-specific guidance instead of the raw error. Recording and streaming keep working; only browser sources are disabled.

To fix it, run the launcher in an FHS environment:

```bash
nix-shell -p steam-run --run "steam-run prismlauncher"
```

or enable `programs.nix-ld` and list the reported libraries (`nss`, `nspr`, `glib`, `gtk3`, `at-spi2-atk`, `cups`, `dbus`, `libdrm`, `mesa`, `expat`, the `xorg` libraries, `libxkbcommon`, `pango`, `cairo`, `alsa-lib`, `stdenv.cc.cc.lib`).

Other known limitations:

- **Browser audio does not reach the broadcast** (see Audio, above)
- **Per-slave `tee` reporting is coarse.** Destinations sharing an encoder are marked live together; individual failures are attributed by matching the ingest URL in FFmpeg's output. A destination with its own encoder has fully independent state.
- **Shader mods** replace parts of the render pipeline. Stream-able composites from `Minecraft.getMainRenderTarget()` at the tail of the render pass, which is the most compatible point available, but exotic pipelines may still interact badly.
- **Runtime behaviour is not machine-verified in this build.** The logic is unit-tested (121 tests) and the project compiles, but the acceptance tests in the table below require a real GPU, a display and live ingest credentials.

---

## Building from source

```bash
./gradlew build
```

That is the whole story: no IDE step, no manual dependency wrangling. The jar lands in `build/libs/`.

```bash
./gradlew test          # unit tests only, no Minecraft needed
./gradlew runClient     # launch a dev client
```

Every dependency is pinned in `gradle.properties` — no `latest.release` anywhere.

**A note on mappings:** Minecraft 26.x ships **deobfuscated**. Mojang publishes no `client_mappings` for 26.1.2 and no Yarn build exists, because there is nothing left to map. The buildscript therefore declares no mapping layer, exactly like MCEF Modern's does for the same Minecraft line.

---

## Manual acceptance tests

These need a real client and cannot be automated here.

| | Test | Expected |
| --- | --- | --- |
| A | Record 60 s | Game audio and mic present, video plays, no drift |
| B | Stream to a custom RTMP endpoint | Gameplay, game audio and mic arrive; clean stop |
| C | Enable 3 destinations | All go `Live`; disabling one leaves the other two `Live` |
| D | Load a transparent page | No black or white rectangle; Minecraft visible through it |
| E | Move / resize / Shift-stretch / rotate 37° | Renders correctly throughout |
| F | Click buttons in a rotated source | The correct button receives the click |
| G | Type `abcdef`, Backspace, Enter, Ctrl+A, Ctrl+V | All behave correctly despite MCEF issue #4 |
| H | View the remote stream | Browser sources appear **on stream**, not just locally |
| I | Interrupt the network | Minecraft stays responsive; reconnect backs off correctly |
| J | Create/delete sources repeatedly | No runaway Chromium processes, no native memory growth |

The bundled test page (**Advanced → Add browser test page**) covers D, F, G and J directly.

---

## Architecture

```
Minecraft render thread
        |
        v
ProgramCompositor  ──  browser sources drawn in z-order
        |                (off-screen framebuffer)
        v
   PBO readback
        |
        +--> RecordingController ──> FFmpeg ──> file
        |
        +--> StreamController ────> FFmpeg ──> tee ──┬──> Twitch
                                                     ├──> YouTube
                                                     └──> Custom RTMPS
```

The output frame is built off-screen from the *clean* game image, **then** local overlays are drawn to the screen. That ordering is what lets a source appear on stream but not on your monitor, and guarantees edit handles never reach viewers.

```
dev/streamable/
  audio/        buses, mixer, capture (ported), live PCM transport, WAV writer
  browser/      engine-neutral abstraction; mcef/ input/ css/ audio/ backends
  compositor/   program canvas, GL program, off-screen compositing, editor overlay
  config/       versioned settings, atomic IO, Record-able import
  ffmpeg/       binary discovery, capability probing, command building, processes
  recording/    recording state machine, metadata, disk guardian
  source/       source model, z-ordering; transform/ geometry and the editor
  streaming/    destinations, grouping, encoder groups, health, reconnect
  ui/           Studio, source editor, health HUD, widgets
  mixin/        the single render hook
```

---

## License and attribution

Stream-able is MIT licensed and is a derivative work of **Record-able** by JoEusebe, whose copyright notice is retained in `LICENSE` as the MIT License requires. `NOTICE` lists exactly which files were inherited and what changed.

Browser support is provided by **MCEF Modern** (LGPL-2.1) by DimasKama, built on JCEF and the Chromium Embedded Framework. FFmpeg is invoked as an external process and never bundled.

No OBS Studio code or assets are used. OBS is referenced only as inspiration for workflow and terminology.
