package dev.streamable.ffmpeg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FFmpegCommandBuilderTest {

    private static final String FFMPEG = "/usr/bin/ffmpeg";

    private static EncodeProfile liveProfile() {
        return new EncodeProfile(
                VideoProfile.liveDefault(VideoEncoder.X264, 1920, 1080, 60, 6000),
                AudioProfile.LIVE_DEFAULT);
    }

    private static int indexOf(List<String> args, String value) {
        return args.indexOf(value);
    }

    private static String valueAfter(List<String> args, String flag) {
        int i = indexOf(args, flag);
        assertTrue(i >= 0 && i + 1 < args.size(), "missing flag " + flag + " in " + args);
        return args.get(i + 1);
    }

    @Test
    void singleDestinationUsesFlvDirectly() {
        List<String> args = FFmpegCommandBuilder.buildStreamCommand(
                FFMPEG, liveProfile(), 1920, 1080, List.of("rtmp://host/app/KEY"), 5000, 0);
        // The output muxer is the trailing "-f flv <url>" triple; an earlier
        // "-f" belongs to the rawvideo input, so index from the end.
        int n = args.size();
        assertEquals("-f", args.get(n - 3));
        assertEquals("flv", args.get(n - 2));
        assertEquals("rtmp://host/app/KEY", args.get(n - 1));
        assertFalse(args.contains("tee"), "single destination must not use the tee muxer");
    }

    @Test
    void multipleDestinationsFanOutThroughTee() {
        List<String> args = FFmpegCommandBuilder.buildStreamCommand(FFMPEG, liveProfile(), 1920, 1080,
                List.of("rtmp://a/app/K1", "rtmps://b/app/K2", "rtmp://c/app/K3"), 5000, 0);
        assertTrue(args.contains("tee"), "expected tee muxer for 3 destinations");
        String target = args.getLast();
        assertTrue(target.contains("rtmp://a/app/K1"));
        assertTrue(target.contains("rtmps://b/app/K2"));
        assertTrue(target.contains("rtmp://c/app/K3"));
        assertEquals(3, target.split("\\|").length);
    }

    @Test
    void teeSlavesIgnoreIndividualFailures() {
        // A dead ingest must not tear down the healthy ones.
        String target = FFmpegCommandBuilder.buildTeeTarget(List.of("rtmp://a/k", "rtmp://b/k"));
        long slaves = target.split("\\|", -1).length;
        long guarded = java.util.regex.Pattern.compile("onfail=ignore").matcher(target).results().count();
        assertEquals(2, slaves);
        assertEquals(slaves, guarded, "every tee slave needs onfail=ignore");
        assertTrue(target.startsWith("[f=flv:onfail=ignore]"));
    }

    @Test
    void teeEscapingPreservesUrlColonsButEscapesPipes() {
        // Escaping ':' would corrupt every rtmp:// URL.
        assertEquals("rtmp://host/app/KEY", FFmpegCommandBuilder.escapeTeeUrl("rtmp://host/app/KEY"));
        assertEquals("rtmp://host/a\\|b", FFmpegCommandBuilder.escapeTeeUrl("rtmp://host/a|b"));
        assertEquals("a\\\\b", FFmpegCommandBuilder.escapeTeeUrl("a\\b"));
    }

    @Test
    void bracketsAreRejectedRatherThanEscaped() {
        assertTrue(FFmpegCommandBuilder.isTeeSafe("rtmp://host/app/KEY"));
        assertFalse(FFmpegCommandBuilder.isTeeSafe("rtmp://host/[weird]"));
    }

    @Test
    void videoInputIsRawFramesOnStdin() {
        List<String> args = FFmpegCommandBuilder.buildStreamCommand(
                FFMPEG, liveProfile(), 1920, 1080, List.of("rtmp://a/k"), 5000, 0);
        assertEquals("rawvideo", valueAfter(args, "-f"));
        assertEquals("rgb24", valueAfter(args, "-pix_fmt"));
        assertEquals("1920x1080", valueAfter(args, "-video_size"));
        assertEquals("pipe:0", args.get(indexOf(args, "-i") + 1));
    }

    @Test
    void liveAudioComesFromLoopbackTcp() {
        List<String> args = FFmpegCommandBuilder.buildStreamCommand(
                FFMPEG, liveProfile(), 1920, 1080, List.of("rtmp://a/k"), 47123, 0);
        assertTrue(args.contains("tcp://127.0.0.1:47123"), "expected loopback audio input");
        assertTrue(args.contains("s16le"));
        assertEquals("aac", valueAfter(args, "-c:a"));
    }

    @Test
    void audioCanBeOmittedEntirely() {
        List<String> args = FFmpegCommandBuilder.buildStreamCommand(
                FFMPEG, liveProfile(), 1920, 1080, List.of("rtmp://a/k"), 0, 0);
        assertTrue(args.contains("-an"));
        assertFalse(args.contains("-c:a"));
    }

    @Test
    void keyframeIntervalIsTwoSecondsWorthOfFrames() {
        List<String> args = FFmpegCommandBuilder.buildStreamCommand(
                FFMPEG, liveProfile(), 1920, 1080, List.of("rtmp://a/k"), 5000, 0);
        assertEquals("120", valueAfter(args, "-g"), "60fps x 2s keyframe interval");
        assertEquals("0", valueAfter(args, "-bf"), "B-frames off by default for live");
    }

    @Test
    void nvencUsesItsOwnRateControlSpelling() {
        EncodeProfile nvenc = new EncodeProfile(
                VideoProfile.liveDefault(VideoEncoder.NVENC_H264, 1920, 1080, 60, 8000),
                AudioProfile.LIVE_DEFAULT);
        List<String> args = FFmpegCommandBuilder.buildStreamCommand(
                FFMPEG, nvenc, 1920, 1080, List.of("rtmp://a/k"), 5000, 0);
        assertEquals("h264_nvenc", valueAfter(args, "-c:v"));
        assertEquals("cbr", valueAfter(args, "-rc"));
        assertEquals("p5", valueAfter(args, "-preset"));
    }

    @Test
    void recordingCommandHasNoAudioAndWritesFile() {
        List<String> args = FFmpegCommandBuilder.buildRecordingCommand(FFMPEG,
                VideoProfile.liveDefault(VideoEncoder.X264, 2560, 1440, 60, 20000),
                2560, 1440, "/videos/out.mp4");
        assertTrue(args.contains("-an"));
        assertEquals("/videos/out.mp4", args.getLast());
        assertTrue(args.contains("+faststart"));
    }

    @Test
    void muxCommandAppliesMeasuredAudioOffset() {
        List<String> args = FFmpegCommandBuilder.buildMuxCommand(FFMPEG, "/v.mp4", "/a.wav",
                AudioProfile.LIVE_DEFAULT, -0.125, "/out.mp4");
        assertEquals("-0.1250", valueAfter(args, "-itsoffset"));
        assertEquals("copy", valueAfter(args, "-c:v"));
        assertEquals("/out.mp4", args.getLast());
    }

    @Test
    void argumentsAreNeverConcatenatedIntoAShellString() {
        List<String> args = FFmpegCommandBuilder.buildStreamCommand(FFMPEG, liveProfile(), 1920, 1080,
                List.of("rtmp://host/app/key with space"), 5000, 0);
        // The URL stays a single argv element, so spaces cannot split it.
        assertTrue(args.contains("rtmp://host/app/key with space"));
    }

    @Test
    void rejectsEmptyDestinationList() {
        assertThrows(IllegalArgumentException.class, () -> FFmpegCommandBuilder.buildStreamCommand(
                FFMPEG, liveProfile(), 1920, 1080, List.of(), 5000, 0));
    }

    @Test
    @DisplayName("the declared input size is the capture size, not the output size")
    void inputSizeMatchesTheCompositorNotTheOutput() {
        // Declaring the output size on the raw input does not scale the picture:
        // FFmpeg slices the byte stream by that number, so a mismatch produces
        // torn, scrambled video. This is the bug that broke a 1920x1080 canvas
        // streaming at 3440x1440.
        EncodeProfile profile = new EncodeProfile(
                VideoProfile.liveDefault(VideoEncoder.X264, 3440, 1440, 60, 8000),
                AudioProfile.LIVE_DEFAULT);
        List<String> args = FFmpegCommandBuilder.buildStreamCommand(
                FFMPEG, profile, 1920, 1080, List.of("rtmp://a/k"), 5000, 0);

        assertEquals("1920x1080", valueAfter(args, "-video_size"), "input must be the capture size");
        assertEquals("scale=3440:1440:flags=bicubic", valueAfter(args, "-vf"),
                "the output resolution is reached by scaling");
    }

    @Test
    void noScaleFilterWhenSizesAlreadyMatch() {
        List<String> args = FFmpegCommandBuilder.buildStreamCommand(
                FFMPEG, liveProfile(), 1920, 1080, List.of("rtmp://a/k"), 5000, 0);
        assertEquals("1920x1080", valueAfter(args, "-video_size"));
        assertFalse(args.contains("-vf"), "no needless scaling when the sizes agree");
    }

    @Test
    void profileSanitisesHostileValues() {
        VideoProfile p = new VideoProfile(VideoEncoder.X264, 1921, 1081, 0, RateControl.CBR,
                -5, -1, -1, -1, null, null, 99);
        assertEquals(0, p.width() % 2, "odd width must be corrected for yuv420p");
        assertEquals(0, p.height() % 2);
        assertTrue(p.fps() >= 1);
        assertTrue(p.bitrateKbps() >= 100);
        assertEquals(2.0, p.keyframeSeconds());
        assertTrue(p.bFrames() <= 8);
    }
}
