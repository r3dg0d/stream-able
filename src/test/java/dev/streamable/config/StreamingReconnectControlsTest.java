package dev.streamable.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Studio reconnect fields must accept only what {@link StreamingSettings#validate()}
 * keeps: the longest wait cannot sit below the first retry, and 0 attempts is unlimited.
 */
class StreamingReconnectControlsTest {

    @Test
    @DisplayName("longest wait cannot be stored below the first retry")
    void longestWaitStaysAtOrAboveFirstRetry() {
        StreamingSettings settings = new StreamingSettings();
        assertEquals(5_000, settings.longestWaitMinMs());
        assertFalse(settings.trySetMaxReconnectDelayMs(1_000));
        assertEquals(60_000, settings.maxReconnectDelayMs);

        assertTrue(settings.trySetReconnectDelayMs(8_000));
        assertEquals(8_000, settings.longestWaitMinMs());
        assertEquals(60_000, settings.maxReconnectDelayMs);
        assertFalse(settings.trySetMaxReconnectDelayMs(7_999));
        assertEquals(60_000, settings.maxReconnectDelayMs);
        assertTrue(settings.trySetMaxReconnectDelayMs(8_000));

        assertTrue(settings.trySetReconnectDelayMs(120_000));
        assertEquals(120_000, settings.maxReconnectDelayMs, "raising the first retry lifts a shorter ceiling");
        assertEquals(120_000, settings.longestWaitMinMs());
    }

    @Test
    @DisplayName("zero attempts is unlimited and matches validate")
    void zeroAttemptsIsUnlimited() {
        StreamingSettings settings = new StreamingSettings();
        assertTrue(settings.trySetMaxReconnectAttempts(0));
        assertEquals(0, settings.maxReconnectAttempts);
        assertTrue(settings.reconnectPolicy().shouldRetry(10_000));
        assertFalse(settings.trySetMaxReconnectAttempts(-1));
        assertFalse(settings.trySetMaxReconnectAttempts(StreamingSettings.MAX_RECONNECT_ATTEMPTS + 1));
        assertEquals(0, settings.maxReconnectAttempts);

        assertTrue(settings.trySetReconnectDelayMs(StreamingSettings.MIN_RECONNECT_DELAY_MS));
        assertTrue(settings.trySetMaxReconnectDelayMs(StreamingSettings.MAX_RECONNECT_DELAY_MS));
        assertFalse(settings.trySetReconnectDelayMs(StreamingSettings.MIN_RECONNECT_DELAY_MS - 1));
        assertFalse(settings.trySetReconnectDelayMs(StreamingSettings.MAX_INITIAL_RECONNECT_DELAY_MS + 1L));
        assertFalse(settings.trySetMaxReconnectDelayMs(StreamingSettings.MAX_RECONNECT_DELAY_MS + 1L));
        assertEquals(StreamingSettings.MIN_RECONNECT_DELAY_MS, settings.reconnectDelayMs);
        assertEquals(StreamingSettings.MAX_RECONNECT_DELAY_MS, settings.maxReconnectDelayMs);

        settings.validate();
        assertEquals(StreamingSettings.MIN_RECONNECT_DELAY_MS, settings.reconnectDelayMs);
        assertEquals(StreamingSettings.MAX_RECONNECT_DELAY_MS, settings.maxReconnectDelayMs);
        assertEquals(0, settings.maxReconnectAttempts);
    }

    @Test
    @DisplayName("validate clamps the same bounds the studio fields reject")
    void validateClampsToTheSameBounds() {
        StreamingSettings settings = new StreamingSettings();
        settings.reconnectDelayMs = 100;
        settings.maxReconnectDelayMs = 50;
        settings.maxReconnectAttempts = -3;
        settings.validate();
        assertEquals(StreamingSettings.MIN_RECONNECT_DELAY_MS, settings.reconnectDelayMs);
        assertEquals(StreamingSettings.MIN_RECONNECT_DELAY_MS, settings.maxReconnectDelayMs);
        assertEquals(0, settings.maxReconnectAttempts);

        settings.reconnectDelayMs = 400_000;
        settings.maxReconnectDelayMs = 2_000_000;
        settings.maxReconnectAttempts = 5_000;
        settings.validate();
        assertEquals(StreamingSettings.MAX_INITIAL_RECONNECT_DELAY_MS, settings.reconnectDelayMs);
        assertEquals(StreamingSettings.MAX_RECONNECT_DELAY_MS, settings.maxReconnectDelayMs);
        assertEquals(StreamingSettings.MAX_RECONNECT_ATTEMPTS, settings.maxReconnectAttempts);
    }
}
