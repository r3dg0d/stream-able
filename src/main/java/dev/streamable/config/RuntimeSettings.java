package dev.streamable.config;

/** Managed-runtime preferences. The defaults are what a normal player wants. */
public final class RuntimeSettings {

    /** Download and install missing runtimes automatically, in the background. */
    public boolean autoInstall = true;
    /** Expert override: an FFmpeg binary to use instead of the managed one. */
    public String ffmpegOverridePath = "";
    /** Use an FFmpeg found on PATH while the managed one is not installed. */
    public boolean allowSystemFfmpeg = true;
    /** Whether browser sources (Chromium) should be initialised at all. */
    public boolean browserEngineEnabled = true;

    public void validate() {
        if (ffmpegOverridePath == null) {
            ffmpegOverridePath = "";
        }
        ffmpegOverridePath = ffmpegOverridePath.trim();
    }
}
