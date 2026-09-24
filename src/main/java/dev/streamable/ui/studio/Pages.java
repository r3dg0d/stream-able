package dev.streamable.ui.studio;

import dev.streamable.ui.kit.Layouts;

/** Builds a page's content into the Studio's scrolling column. */
final class Pages {

    private Pages() {
    }

    static void build(Page page, Studio s, Layouts.Column column) {
        switch (page) {
            case HOME -> HomePage.build(s, column);
            case SOURCES -> SourcesPage.build(s, column);
            case VIDEO -> VideoPage.build(s, column);
            case AUDIO -> AudioPage.build(s, column);
            case RECORDING -> RecordingPage.build(s, column);
            case STREAMING -> StreamingPage.build(s, column);
            case DESTINATIONS -> DestinationsPage.build(s, column);
            case HEALTH -> HealthPage.build(s, column);
            case RUNTIME -> RuntimePage.build(s, column);
            case ADVANCED -> AdvancedPage.build(s, column);
        }
    }
}
