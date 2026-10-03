package dev.streamable.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Studio numeric controls use these constants as their min and max.
 * {@code validate()} must clamp to the same numbers, including the edges the
 * old fields rejected (audio bitrate 32 and 512, eight B-frames, a queue of 8).
 */
class StudioNumericBoundsTest {

    @Test
    @DisplayName("streaming fields clamp to the studio bounds")
    void streamingFieldsMatchValidate() {
        StreamingSettings settings = new StreamingSettings();
        settings.bitrateKbps = 1;
        settings.maxBitrateKbps = 1;
        settings.bufferSizeKbits = 1;
        settings.keyframeSeconds = 0.1;
        settings.audioBitrateKbps = 1;
        settings.bFrames = -3;
        settings.frameQueueCapacity = 1;
        settings.uploadSpeedMbps = -4;
        settings.validate();
        assertEquals(StreamingSettings.MIN_BITRATE_KBPS, settings.bitrateKbps);
        assertEquals(StreamingSettings.MIN_MAX_BITRATE_KBPS, settings.maxBitrateKbps);
        assertEquals(StreamingSettings.MIN_BUFFER_SIZE_KBITS, settings.bufferSizeKbits);
        assertEquals(StreamingSettings.MIN_KEYFRAME_SECONDS, settings.keyframeSeconds, 0.0);
        assertEquals(StreamingSettings.MIN_AUDIO_BITRATE_KBPS, settings.audioBitrateKbps);
        assertEquals(StreamingSettings.MIN_B_FRAMES, settings.bFrames);
        assertEquals(StreamingSettings.MIN_FRAME_QUEUE_CAPACITY, settings.frameQueueCapacity);
        assertEquals(StreamingSettings.MIN_UPLOAD_SPEED_MBPS, settings.uploadSpeedMbps, 0.0);

        settings.bitrateKbps = 9_000_000;
        settings.maxBitrateKbps = 9_000_000;
        settings.bufferSizeKbits = 9_000_000;
        settings.keyframeSeconds = 40;
        settings.audioBitrateKbps = 9_000;
        settings.bFrames = 30;
        settings.frameQueueCapacity = 9_000;
        settings.uploadSpeedMbps = 99_999;
        settings.validate();
        assertEquals(StreamingSettings.MAX_BITRATE_KBPS, settings.bitrateKbps);
        assertEquals(StreamingSettings.MAX_MAX_BITRATE_KBPS, settings.maxBitrateKbps);
        assertEquals(StreamingSettings.MAX_BUFFER_SIZE_KBITS, settings.bufferSizeKbits);
        assertEquals(StreamingSettings.MAX_KEYFRAME_SECONDS, settings.keyframeSeconds, 0.0);
        assertEquals(StreamingSettings.MAX_AUDIO_BITRATE_KBPS, settings.audioBitrateKbps);
        assertEquals(StreamingSettings.MAX_B_FRAMES, settings.bFrames);
        assertEquals(StreamingSettings.MAX_FRAME_QUEUE_CAPACITY, settings.frameQueueCapacity);
        assertEquals(StreamingSettings.MAX_UPLOAD_SPEED_MBPS, settings.uploadSpeedMbps, 0.0);

        settings.bitrateKbps = 6_000;
        settings.maxBitrateKbps = 0;
        settings.bufferSizeKbits = 0;
        settings.keyframeSeconds = 0;
        settings.uploadSpeedMbps = Double.NaN;
        settings.validate();
        assertEquals(6_000, settings.maxBitrateKbps);
        assertEquals(12_000, settings.bufferSizeKbits);
        assertEquals(2.0, settings.keyframeSeconds, 0.0);
        assertEquals(0.0, settings.uploadSpeedMbps, 0.0);
    }

    @Test
    @DisplayName("recording fields clamp to the studio bounds")
    void recordingFieldsMatchValidate() {
        RecordingSettings settings = new RecordingSettings();
        settings.bitrateKbps = 1;
        settings.qualityPreset = -5;
        settings.audioBitrateKbps = 1;
        settings.audioDelayMs = -99_000;
        settings.replayBufferSeconds = 1;
        settings.validate();
        assertEquals(RecordingSettings.MIN_BITRATE_KBPS, settings.bitrateKbps);
        assertEquals(RecordingSettings.MIN_QUALITY_PRESET, settings.qualityPreset);
        assertEquals(RecordingSettings.MIN_AUDIO_BITRATE_KBPS, settings.audioBitrateKbps);
        assertEquals(RecordingSettings.MIN_AUDIO_DELAY_MS, settings.audioDelayMs);
        assertEquals(RecordingSettings.MIN_REPLAY_SECONDS, settings.replayBufferSeconds);

        settings.bitrateKbps = 9_000_000;
        settings.qualityPreset = 500;
        settings.audioBitrateKbps = 9_000;
        settings.audioDelayMs = 99_000;
        settings.replayBufferSeconds = 9_000;
        settings.validate();
        assertEquals(RecordingSettings.MAX_BITRATE_KBPS, settings.bitrateKbps);
        assertEquals(RecordingSettings.MAX_QUALITY_PRESET, settings.qualityPreset);
        assertEquals(RecordingSettings.MAX_AUDIO_BITRATE_KBPS, settings.audioBitrateKbps);
        assertEquals(RecordingSettings.MAX_AUDIO_DELAY_MS, settings.audioDelayMs);
        assertEquals(RecordingSettings.MAX_REPLAY_SECONDS, settings.replayBufferSeconds);
    }

    @Test
    @DisplayName("interface sliders clamp to the studio bounds")
    void interfaceFieldsMatchValidate() {
        InterfaceSettings settings = new InterfaceSettings();
        settings.snapThreshold = -10;
        settings.streamHudScale = 0.1f;
        settings.streamHudOpacity = 0.01f;
        settings.validate();
        assertEquals(InterfaceSettings.MIN_SNAP_PX, settings.snapThreshold, 0.0);
        assertEquals(InterfaceSettings.MIN_HUD_SCALE, settings.streamHudScale, 0f);
        assertEquals(InterfaceSettings.MIN_HUD_OPACITY, settings.streamHudOpacity, 0f);

        settings.snapThreshold = 1_000;
        settings.streamHudScale = 9f;
        settings.streamHudOpacity = 4f;
        settings.validate();
        assertEquals(InterfaceSettings.MAX_SNAP_PX, settings.snapThreshold, 0.0);
        assertEquals(InterfaceSettings.MAX_HUD_SCALE, settings.streamHudScale, 0f);
        assertEquals(InterfaceSettings.MAX_HUD_OPACITY, settings.streamHudOpacity, 0f);
    }

    @Test
    @DisplayName("noise reduction override clamps to the studio bounds and keeps the off sentinel")
    void noiseReductionMatchesValidate() {
        MicrophoneSettings settings = new MicrophoneSettings();
        settings.noise.strengthOverrideDb = -1;
        settings.validate();
        assertEquals(-1.0, settings.noise.strengthOverrideDb, 0.0);

        settings.noise.strengthOverrideDb = MicrophoneSettings.NoiseCancellation.MIN_STRENGTH_OVERRIDE_DB;
        settings.validate();
        assertEquals(MicrophoneSettings.NoiseCancellation.MIN_STRENGTH_OVERRIDE_DB,
                settings.noise.strengthOverrideDb, 0.0);

        settings.noise.strengthOverrideDb = 250;
        settings.validate();
        assertEquals(MicrophoneSettings.NoiseCancellation.MAX_STRENGTH_OVERRIDE_DB,
                settings.noise.strengthOverrideDb, 0.0);
    }
}
