package dev.streamable.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlatformUtilsTest {

    @Test
    void ffmpegInstallHintsPointAtManagedComponentsNotRetiredHosts() {
        for (PlatformUtils.Platform platform : PlatformUtils.Platform.values()) {
            String hint = PlatformUtils.ffmpegInstallHintFor(platform);
            assertFalse(hint.toLowerCase().contains("gyan"), platform + ": " + hint);
            assertFalse(hint.toLowerCase().contains("johnvansickle"), platform + ": " + hint);
            assertFalse(hint.toLowerCase().contains("evermeet"), platform + ": " + hint);
            assertFalse(hint.isBlank());
        }
        String desktop = PlatformUtils.ffmpegInstallHintFor(PlatformUtils.Platform.WINDOWS);
        assertTrue(desktop.contains("Components"), desktop);
        assertTrue(desktop.contains("BtbN"), desktop);
        assertEquals(desktop, PlatformUtils.ffmpegInstallHintFor(PlatformUtils.Platform.LINUX));
        assertEquals(desktop, PlatformUtils.ffmpegInstallHintFor(PlatformUtils.Platform.MACOS));
    }

    @Test
    void audioMethodDescribesOpenALLoopbackOnDesktop() {
        for (PlatformUtils.Platform desktop : new PlatformUtils.Platform[] {
                PlatformUtils.Platform.WINDOWS,
                PlatformUtils.Platform.LINUX,
                PlatformUtils.Platform.MACOS
        }) {
            String desc = PlatformUtils.audioMethodDescriptionFor(desktop);
            assertTrue(desc.contains("OpenAL"), desktop + ": " + desc);
            assertFalse(desc.contains("DirectShow"), desktop + ": " + desc);
            assertFalse(desc.contains("PulseAudio"), desktop + ": " + desc);
            assertFalse(desc.contains("AVFoundation"), desktop + ": " + desc);
        }
        assertTrue(PlatformUtils.audioMethodDescriptionFor(PlatformUtils.Platform.ANDROID)
                .contains("OpenAL"));
    }

    @Test
    void detectPlatformIsCachedAndKnownOnThisHost() {
        PlatformUtils.Platform first = PlatformUtils.detectPlatform();
        assertSame(first, PlatformUtils.detectPlatform());
        assertNotEquals(PlatformUtils.Platform.UNKNOWN, first);
    }
}
