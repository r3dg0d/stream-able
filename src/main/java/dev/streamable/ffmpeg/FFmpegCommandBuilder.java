package dev.streamable.ffmpeg;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds FFmpeg argument lists.
 *
 * <p>Commands are always produced as a {@code List<String>} destined for
 * {@link ProcessBuilder}, never as a single shell string: a stream key or a
 * path containing a space or a quote must not be able to change the meaning of
 * the command line.</p>
 *
 * <h2>Process topology</h2>
 * <ul>
 *   <li><b>Recording</b> runs in its own FFmpeg process. Video arrives raw on
 *       {@code pipe:0}; audio is muxed in afterwards from the captured WAV with
 *       a measured {@code -itsoffset}. This is the pipeline Record-able already
 *       proved out, including its multi-track and chapter support.</li>
 *   <li><b>Streaming</b> runs in a separate process per distinct
 *       {@link EncodeProfile}. Video arrives raw on {@code pipe:0}; audio
 *       arrives live over a loopback TCP connection. Keeping streaming separate
 *       from recording means a network failure can never corrupt the file on
 *       disk, and stopping the recording does not touch the broadcast.</li>
 * </ul>
 *
 * <p>Live audio uses TCP rather than a FIFO because named pipes are not
 * portable: {@code mkfifo} does not exist on Windows and Java cannot create a
 * Win32 named pipe without native code. A loopback socket behaves identically
 * on both platforms.</p>
 */
public final class FFmpegCommandBuilder {

    private FFmpegCommandBuilder() {
    }

    /**
     * Raw video input: the compositor's program frames, pushed to stdin.
     *
     * <p>The declared size must be the size the compositor actually produces.
     * FFmpeg slices the byte stream into frames purely by this number, so a
     * mismatch does not scale the picture - it reinterprets frame boundaries and
     * produces torn, scrambled video. Scaling to a different output resolution
     * is a separate filter step.</p>
     */
    private static void addRawVideoInput(List<String> args, int width, int height, int fps, String pixelFormat) {
        args.add("-f");
        args.add("rawvideo");
        args.add("-pix_fmt");
        args.add(pixelFormat);
        args.add("-video_size");
        args.add(width + "x" + height);
        args.add("-framerate");
        args.add(Integer.toString(fps));
        // A generous queue absorbs scheduling jitter without stalling our writer.
        args.add("-thread_queue_size");
        args.add("512");
        args.add("-i");
        args.add("pipe:0");
    }

    /** Live PCM audio input over loopback TCP; FFmpeg connects to our listener. */
    private static void addLiveAudioInput(List<String> args, AudioProfile audio, int port, double offsetSeconds) {
        args.add("-f");
        args.add("s16le");
        args.add("-ar");
        args.add(Integer.toString(audio.sampleRate()));
        args.add("-ac");
        args.add(Integer.toString(audio.channels()));
        args.add("-thread_queue_size");
        args.add("512");
        if (offsetSeconds != 0.0) {
            args.add("-itsoffset");
            args.add(String.format(Locale.ROOT, "%.4f", offsetSeconds));
        }
        args.add("-i");
        args.add("tcp://127.0.0.1:" + port);
    }

    /** Encoder-specific video arguments, including rate control. */
    /**
     * Scales the captured frames when the requested output resolution differs
     * from what the compositor produces.
     *
     * <p>Capture always happens at the program-canvas size; the output
     * resolution is an encoder setting. Doing the resize here - rather than
     * pretending the input is a different size - is what keeps a 1920x1080
     * canvas streaming correctly at 3440x1440 or vice versa.</p>
     */
    static void addScaleIfNeeded(List<String> args, int sourceWidth, int sourceHeight, VideoProfile video) {
        if (sourceWidth == video.width() && sourceHeight == video.height()) {
            return;
        }
        args.add("-vf");
        args.add("scale=" + video.width() + ":" + video.height() + ":flags=bicubic");
    }

    static void addVideoCodecArgs(List<String> args, VideoProfile video) {
        VideoEncoder encoder = video.encoder();
        args.add("-c:v");
        args.add(encoder.ffmpegName());

        if (!video.preset().isEmpty()) {
            args.add("-preset");
            args.add(video.preset());
        }

        String bitrate = video.bitrateKbps() + "k";
        String maxBitrate = video.maxBitrateKbps() + "k";
        String bufferSize = video.bufferSizeKbits() + "k";

        switch (encoder.family()) {
            case NVIDIA -> {
                args.add("-rc");
                args.add(video.rateControl() == RateControl.CBR ? "cbr" : "vbr");
                // Low-latency tuning keeps the encoder from buffering frames,
                // which matters for both stream latency and A/V drift.
                args.add("-tune");
                args.add("ll");
                args.add("-b:v");
                args.add(bitrate);
                args.add("-maxrate");
                args.add(maxBitrate);
                args.add("-bufsize");
                args.add(bufferSize);
                args.add("-rc-lookahead");
                args.add("0");
            }
            case AMD -> {
                args.add("-usage");
                args.add("lowlatency");
                args.add("-rc");
                args.add(video.rateControl() == RateControl.CBR ? "cbr" : "vbr_peak");
                args.add("-b:v");
                args.add(bitrate);
                args.add("-maxrate");
                args.add(maxBitrate);
                args.add("-bufsize");
                args.add(bufferSize);
            }
            case INTEL -> {
                args.add("-b:v");
                args.add(bitrate);
                args.add("-maxrate");
                args.add(maxBitrate);
                args.add("-bufsize");
                args.add(bufferSize);
                args.add("-low_power");
                args.add("0");
            }
            case VAAPI, SOFTWARE -> {
                if (encoder == VideoEncoder.X264 || encoder == VideoEncoder.X265) {
                    args.add("-tune");
                    args.add("zerolatency");
                }
                args.add("-b:v");
                args.add(bitrate);
                args.add("-maxrate");
                args.add(maxBitrate);
                args.add("-bufsize");
                args.add(bufferSize);
                if (video.rateControl() == RateControl.CBR && encoder == VideoEncoder.X264) {
                    // x264 has no true CBR switch; pinning min=max=target with a
                    // NAL-HRD buffer is the standard way to emulate it for RTMP.
                    args.add("-minrate");
                    args.add(bitrate);
                    args.add("-nal-hrd");
                    args.add("cbr");
                }
            }
        }

        if (encoder.codec() == VideoEncoder.Codec.H264 && !video.h264Profile().isEmpty()) {
            args.add("-profile:v");
            args.add(video.h264Profile());
        }

        args.add("-g");
        args.add(Integer.toString(video.keyframeIntervalFrames()));
        args.add("-keyint_min");
        args.add(Integer.toString(video.keyframeIntervalFrames()));
        args.add("-bf");
        args.add(Integer.toString(video.bFrames()));
        args.add("-pix_fmt");
        args.add("yuv420p");
    }

    static void addAudioCodecArgs(List<String> args, AudioProfile audio) {
        args.add("-c:a");
        args.add(audio.codec().ffmpegName());
        args.add("-ar");
        args.add(Integer.toString(audio.sampleRate()));
        args.add("-ac");
        args.add(Integer.toString(audio.channels()));
        if (audio.codec() != AudioCodec.FLAC && audio.codec() != AudioCodec.PCM) {
            args.add("-b:a");
            args.add(audio.bitrateKbps() + "k");
        }
    }

    /**
     * Builds the live streaming command for one encode profile and one or more
     * destinations.
     *
     * <p>With a single destination FFmpeg writes the FLV muxer directly. With
     * several, the {@code tee} muxer fans the <em>already encoded</em> packets
     * out, so N destinations still cost exactly one encode. Every tee slave
     * carries {@code onfail=ignore} so that one dead ingest cannot tear down
     * the healthy ones.</p>
     *
     * @param ffmpegExecutable path to the ffmpeg binary
     * @param profile          encode settings shared by all destinations
     * @param publishUrls      full publish URLs (ingest + key), one per destination
     * @param audioPort        loopback port for live PCM, or {@code <= 0} for no audio
     * @param audioOffsetSecs  measured audio start offset, applied via {@code -itsoffset}
     */
    public static List<String> buildStreamCommand(String ffmpegExecutable,
                                                  EncodeProfile profile,
                                                  int sourceWidth,
                                                  int sourceHeight,
                                                  List<String> publishUrls,
                                                  int audioPort,
                                                  double audioOffsetSecs) {
        if (publishUrls == null || publishUrls.isEmpty()) {
            throw new IllegalArgumentException("At least one publish URL is required");
        }
        VideoProfile video = profile.video();
        AudioProfile audio = profile.audio();

        List<String> args = new ArrayList<>();
        args.add(ffmpegExecutable);
        args.add("-hide_banner");
        args.add("-loglevel");
        args.add("info");
        args.add("-stats");
        args.add("-nostdin");

        addRawVideoInput(args, sourceWidth, sourceHeight, video.fps(), "rgb24");
        boolean hasAudio = audioPort > 0;
        if (hasAudio) {
            addLiveAudioInput(args, audio, audioPort, audioOffsetSecs);
        }

        args.add("-map");
        args.add("0:v:0");
        if (hasAudio) {
            args.add("-map");
            args.add("1:a:0");
        }

        args.add("-fps_mode");
        args.add("cfr");
        args.add("-r");
        args.add(Integer.toString(video.fps()));
        addScaleIfNeeded(args, sourceWidth, sourceHeight, video);

        addVideoCodecArgs(args, video);
        if (hasAudio) {
            addAudioCodecArgs(args, audio);
        } else {
            args.add("-an");
        }

        if (publishUrls.size() == 1) {
            args.add("-f");
            args.add("flv");
            args.add(publishUrls.getFirst());
        } else {
            args.add("-f");
            args.add("tee");
            args.add(buildTeeTarget(publishUrls));
        }
        return List.copyOf(args);
    }

    /**
     * Assembles the {@code tee} muxer target string.
     *
     * <p>Format: {@code [f=flv:onfail=ignore]url1|[f=flv:onfail=ignore]url2}.
     * Characters that are structural to the tee syntax are escaped so a URL
     * containing them cannot inject an extra output.</p>
     */
    static String buildTeeTarget(List<String> publishUrls) {
        StringBuilder target = new StringBuilder();
        for (String url : publishUrls) {
            if (!target.isEmpty()) {
                target.append('|');
            }
            target.append("[f=flv:onfail=ignore]").append(escapeTeeUrl(url));
        }
        return target.toString();
    }

    /**
     * Escapes the characters the tee muxer treats as syntax in the URL portion
     * of a slave specification.
     *
     * <p>Only {@code \\} and {@code |} are escapable there: FFmpeg tokenises the
     * slave list on {@code |} with backslash escaping, and everything after the
     * {@code [options]} block is taken literally. In particular {@code :} must
     * <em>not</em> be escaped, or every {@code rtmp://} URL would be corrupted.
     * Structural brackets are rejected during validation instead
     * ({@link #isTeeSafe(String)}), because escaping them is not supported.</p>
     */
    static String escapeTeeUrl(String url) {
        StringBuilder escaped = new StringBuilder(url.length() + 8);
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '\\' || c == '|') {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    /**
     * Whether a publish URL can safely take part in a {@code tee} fan-out.
     *
     * <p>Square brackets would be read as the start of a slave option block and
     * cannot be escaped, so such a URL is given its own encoder instead of being
     * grouped.</p>
     */
    public static boolean isTeeSafe(String url) {
        return url != null && url.indexOf('[') < 0 && url.indexOf(']') < 0;
    }

    /**
     * Builds the local recording command: raw frames in, encoded video file out.
     *
     * <p>Audio is deliberately excluded here and muxed afterwards by
     * {@link #buildMuxCommand}, which is how Record-able achieves its precise
     * A/V alignment - the real audio start offset is only known once capture
     * has actually begun.</p>
     */
    public static List<String> buildRecordingCommand(String ffmpegExecutable,
                                                     VideoProfile video,
                                                     int sourceWidth,
                                                     int sourceHeight,
                                                     String outputFile) {
        List<String> args = new ArrayList<>();
        args.add(ffmpegExecutable);
        args.add("-hide_banner");
        args.add("-loglevel");
        args.add("info");
        args.add("-stats");
        args.add("-y");

        addRawVideoInput(args, sourceWidth, sourceHeight, video.fps(), "rgb24");

        args.add("-fps_mode");
        args.add("cfr");
        args.add("-r");
        args.add(Integer.toString(video.fps()));
        addScaleIfNeeded(args, sourceWidth, sourceHeight, video);

        addVideoCodecArgs(args, video);
        args.add("-an");

        if (outputFile.toLowerCase(Locale.ROOT).endsWith(".mp4")
                || outputFile.toLowerCase(Locale.ROOT).endsWith(".mov")) {
            args.add("-movflags");
            args.add("+faststart");
        }
        args.add(outputFile);
        return List.copyOf(args);
    }

    /**
     * Builds the final remux that joins the encoded video with the captured
     * audio track, shifting audio by the measured start gap.
     *
     * @param audioOffsetSeconds positive delays the audio, negative advances it
     */
    public static List<String> buildMuxCommand(String ffmpegExecutable,
                                               String videoFile,
                                               String audioFile,
                                               AudioProfile audio,
                                               double audioOffsetSeconds,
                                               String outputFile) {
        List<String> args = new ArrayList<>();
        args.add(ffmpegExecutable);
        args.add("-nostdin");
        args.add("-hide_banner");
        args.add("-loglevel");
        args.add("info");
        args.add("-y");
        args.add("-i");
        args.add(videoFile);
        if (audioOffsetSeconds != 0.0) {
            args.add("-itsoffset");
            args.add(String.format(Locale.ROOT, "%.4f", audioOffsetSeconds));
        }
        args.add("-i");
        args.add(audioFile);
        args.add("-map");
        args.add("0:v:0");
        args.add("-map");
        args.add("1:a:0");
        args.add("-c:v");
        args.add("copy");
        addAudioCodecArgs(args, audio);
        args.add(outputFile);
        return List.copyOf(args);
    }

    /**
     * Builds the crash-recovery remux for an unfinished recording: stream copy
     * into a clean container so a truncated file becomes playable.
     */
    public static List<String> buildRecoveryRemuxCommand(String ffmpegExecutable, String brokenFile, String outputFile) {
        return List.of(ffmpegExecutable, "-nostdin", "-hide_banner", "-loglevel", "warning",
                "-y", "-err_detect", "ignore_err", "-i", brokenFile,
                "-c", "copy", "-movflags", "+faststart", outputFile);
    }
}
