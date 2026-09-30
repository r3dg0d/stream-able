# Windows notes (Minecraft 26.1.2, 26.2 and 26.3)

Partial coverage for [issue #3](https://github.com/r3dg0d/stream-able/issues/3).
This is a smoke checklist and platform quirks list — **a live Windows Minecraft
client session is still unverified**. CI on `windows-latest` (Temurin 25) runs
`./gradlew test` and `build` only; it never boots the game.

## What CI already proves

- Sources compile and unit/integration tests pass on Windows (same suite as Linux).
- Native-runtime thread stacks size from `ProcessHandle` when `/proc` is absent
  (`RuntimeManager`), floored at 64 MB.
- Jar naming and version-sync are asserted on Linux; Windows proves the same
  sources build.

## Client smoke checklist (manual)

Use a disposable Minecraft (26.1.2, 26.2 or 26.3) + Fabric Loader install. Do **not** commit stream
keys.

1. Drop the `stream-able-*-mc<your version>-fabric.jar` for your Minecraft version and matching Fabric API into `mods/`.
2. Launch, open **Studio → Components**: install / verify FFmpeg (managed BtbN
   GPL build) and Chromium. Confirm progress / Retry / Verify work.
3. Record a short local clip with any working encoder; open the file and check
   A/V presence.
4. Optional: add a browser source (test page under Studio), confirm meters /
   page audio modes; check mic meters.
5. Optional: NVENC / AMF only if the GPU stack is present — note failures
   honestly in the issue rather than forcing a close.

When any of the above is done on a real Windows 10/11 box, update the README
**Verification status** and comment on issue #3 with the findings (OS build,
GPU, encoder used). Leave the issue open until acceptance boxes are checked.

## Platform quirks

| Topic | Behaviour |
| --- | --- |
| Config secrets | `stream-able.json` keeps default NTFS ACLs when it holds a stream key. Java cannot set restrictive ACLs portably; on Linux/macOS the file is `chmod 600`. Documented under README Security. |
| FFmpeg | Prefer **Studio → Components** (BtbN GPL 8.1.x). A system `ffmpeg` on PATH also works. |
| Game audio | OpenAL loopback into the mixer (same as Linux/macOS) — not DirectShow Stereo Mix. |
| Microphone | Java Sound capture; WASAPI native path is intentionally stubbed/disabled. |
| Browser | MCEF Modern Chromium; same iframe / `speechSynthesis` limits as elsewhere (issues #1). |
| Encoders | Software x264 always; NVENC / AMF / QSV depend on drivers and the managed FFmpeg build. |

## Related

- Issue [#3](https://github.com/r3dg0d/stream-able/issues/3) — acceptance tracking.
- Issue [#1](https://github.com/r3dg0d/stream-able/issues/1) — browser audio.
- Issue [#2](https://github.com/r3dg0d/stream-able/issues/2) — live publish verification.
