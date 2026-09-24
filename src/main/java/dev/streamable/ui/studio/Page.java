package dev.streamable.ui.studio;

import dev.streamable.ui.kit.Icons;

/** The Studio's pages, in navigation order. */
public enum Page {
    HOME("Home", "Preview, outputs, destinations and mixer", Icons.Icon.HOME),
    SOURCES("Sources", "Browser sources on the program canvas", Icons.Icon.SOURCES),
    VIDEO("Video", "Canvas, output resolutions and scaling", Icons.Icon.VIDEO),
    AUDIO("Audio", "Microphone processing and the mix", Icons.Icon.AUDIO),
    RECORDING("Recording", "Local recording format and quality", Icons.Icon.RECORD),
    STREAMING("Streaming", "Encoder, bitrate and reconnection", Icons.Icon.STREAM),
    DESTINATIONS("Destinations", "Where the stream goes, and testing it", Icons.Icon.DESTINATIONS),
    HEALTH("Stream Health", "What is happening, and what to do about it", Icons.Icon.HEALTH),
    RUNTIME("Components", "FFmpeg, browser engine and AI models", Icons.Icon.RUNTIME),
    ADVANCED("Advanced", "Interface, diagnostics and licences", Icons.Icon.ADVANCED);

    private final String title;
    private final String subtitle;
    private final Icons.Icon icon;

    Page(String title, String subtitle, Icons.Icon icon) {
        this.title = title;
        this.subtitle = subtitle;
        this.icon = icon;
    }

    public String title() {
        return title;
    }

    public String subtitle() {
        return subtitle;
    }

    public Icons.Icon icon() {
        return icon;
    }
}
