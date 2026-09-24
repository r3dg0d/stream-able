package dev.streamable.config;

import dev.streamable.config.RecordingSettings.Container;
import dev.streamable.ffmpeg.AudioCodec;
import dev.streamable.ffmpeg.VideoEncoder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The container rules mirror what FFmpeg 8.1's muxers accept (measured with the pinned build). */
class ContainerCompatibilityTest {

    @Test
    void webmOnlyTakesVp9OrAv1AndOpus() {
        assertFalse(Container.WEBM.supports(VideoEncoder.Codec.H264));
        assertFalse(Container.WEBM.supports(VideoEncoder.Codec.HEVC));
        assertTrue(Container.WEBM.supports(VideoEncoder.Codec.VP9));
        assertTrue(Container.WEBM.supports(VideoEncoder.Codec.AV1));
        assertTrue(Container.WEBM.supports(AudioCodec.OPUS));
        assertFalse(Container.WEBM.supports(AudioCodec.AAC));
    }

    @Test
    void movRejectsModernCodecs() {
        assertTrue(Container.MOV.supports(VideoEncoder.Codec.HEVC));
        assertFalse(Container.MOV.supports(VideoEncoder.Codec.AV1));
        assertFalse(Container.MOV.supports(VideoEncoder.Codec.VP9));
        assertFalse(Container.MOV.supports(AudioCodec.OPUS));
        assertTrue(Container.MOV.supports(AudioCodec.PCM));
    }

    @Test
    void mkvAndMp4AcceptEverything() {
        for (VideoEncoder.Codec codec : VideoEncoder.Codec.values()) {
            assertTrue(Container.MKV.supports(codec));
            assertTrue(Container.MP4.supports(codec));
        }
        for (AudioCodec codec : AudioCodec.values()) {
            assertTrue(Container.MKV.supports(codec));
            assertTrue(Container.MP4.supports(codec));
        }
    }

    @Test
    void problemExplainsTheFix() {
        String problem = Container.WEBM.problemWith(VideoEncoder.X264, AudioCodec.OPUS);
        assertNotNull(problem);
        assertTrue(problem.contains("MKV"));
        assertNull(Container.MKV.problemWith(VideoEncoder.X264, AudioCodec.AAC));
    }
}
