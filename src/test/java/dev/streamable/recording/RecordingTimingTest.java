package dev.streamable.recording;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecordingTimingTest {

    private static final long MS = 1_000_000L;

    @Test
    void audioThatStartedLaterIsDelayed() {
        // Video's first frame at t=100 ms, audio's first sample at t=112.5 ms:
        // FFmpeg's -itsoffset delays its input, so the audio needs +12.5 ms.
        assertEquals(0.0125, RecordingController.audioOffsetSeconds(100 * MS, 112_500_000L, 0), 1e-9);
    }

    @Test
    void audioThatStartedEarlierIsAdvanced() {
        assertEquals(-0.020, RecordingController.audioOffsetSeconds(100 * MS, 80 * MS, 0), 1e-9);
    }

    @Test
    void manualNudgeIsAdded() {
        assertEquals(0.0125 + 0.040, RecordingController.audioOffsetSeconds(100 * MS, 112_500_000L, 40), 1e-9);
        assertEquals(0.040, RecordingController.audioOffsetSeconds(0, 0, 40), 1e-9);
    }
}
