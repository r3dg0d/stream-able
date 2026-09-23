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
 *       {@code pipe:0}; audio tracks are captured to WAV in parallel and muxed
 *       in afterwards with a measured {@code -itsoffset}, which is how
 *       Record-able keeps long recordings free of drift.</li>
 *   <li><b>Streaming</b> runs in a separate process per distinct
 *       {@link EncodeProfile}. Video arrives raw on {@code pipe:0}; audio
 *       arrives live over a loopback TCP connection.</li>
 * </ul>
 *
 * <h2>Resolution</h2>
 * <p>Frames reach FFmpeg <em>already at the output resolution</em>: the GPU
 * output transform (Fit/Fill/Center Crop/...) scales them before readback. So
 * no scale filter is normally emitted, the aspect ratio is never forced to 16:9
 * and the sample aspect ratio is pinned to 1:1. Should the input ever differ
 * from the profile, FFmpeg fits it with padding - it never stretches.</p>
 *
 * <p>Every command emits FFmpeg's machine-readable {@code -progress} report on
 * stdout, which is where Stream Health's real bitrate, encode FPS and speed
 * come from.</p>
 */
public final class FFmpegCommandBuilder {

    private FFmpegCommandBuilder() {
    }

    private static void addGlobalArgs(List<String> args, String ffmpegExecutable, VideoEncoder encoder) {
        args.add(ffmpegExecutable);
        args.add("-hide_banner");
        args.add("-loglevel");
        args.add("info");
        args.add("-nostats");
        args.add("-progress");
        args.add("pipe:1");
        args.add("-stats_period");
        args.add("0.5");
        args.add("-nostdin");
        args.addAll(EncoderArgs.deviceArgs(encoder));
    }

    /**
     * Raw video input: the compositor's output frames, pushed to stdin.
     *
     * <p>The declared size must be the size the compositor actually produces.
     * FFmpeg slices the byte stream into frames purely by this number, so a
     * mismatch does not scale the picture - it reinterprets frame boundaries and
     * produces torn, scrambled video.</p>
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

    /**
     * The video filter chain: optional safety fit, 1:1 SAR, then conversion to
     * the pixel format and colour matrix the encoder needs.
     */
    static String videoFilter(int sourceWidth, int sourceHeight, VideoProfile video) {
        StringBuilder chain = new StringBuilder();
        if (sourceWidth != video.width() || sourceHeight != video.height()) {
            // Only reached if a caller hands over frames at another size. Fit
            // with padding: the picture is never distorted.
            chain.append("scale=").append(video.width()).append(':').append(video.height())
                    .append(":force_original_aspect_ratio=decrease:flags=bicubic,")
                    .append("pad=").append(video.width()).append(':').append(video.height())
                    .append(":(ow-iw)/2:(oh-ih)/2:color=black,");
        }
        chain.append("setsar=1,").append(EncoderArgs.formatFilter(video.encoder()));
        return chain.toString();
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
        boolean constantQuality = video.rateControl() == RateControl.CONSTANT_QUALITY;
        String quality = Integer.toString(video.quality());

        switch (encoder.family()) {
            case NVIDIA -> {
                args.add("-tune");
                args.add("ll");
                args.add("-rc-lookahead");
                args.add("0");
                if (constantQuality) {
                    args.add("-rc");
                    args.add("vbr");
                    args.add("-cq");
                    args.add(quality);
                    args.add("-b:v");
                    args.add("0");
                } else {
                    args.add("-rc");
                    args.add(video.rateControl() == RateControl.CBR ? "cbr" : "vbr");
                    addBitrate(args, bitrate, maxBitrate, bufferSize);
                }
            }
            case AMD -> {
                args.add("-usage");
                args.add("lowlatency");
                if (constantQuality) {
                    args.add("-rc");
                    args.add("cqp");
                    args.add("-qp_i");
                    args.add(quality);
                    args.add("-qp_p");
                    args.add(quality);
                } else {
                    args.add("-rc");
                    args.add(video.rateControl() == RateControl.CBR ? "cbr" : "vbr_peak");
                    addBitrate(args, bitrate, maxBitrate, bufferSize);
                }
            }
            case INTEL -> {
                if (constantQuality) {
                    args.add("-global_quality");
                    args.add(quality);
                } else {
                    addBitrate(args, bitrate, maxBitrate, bufferSize);
                }
                args.add("-low_power");
                args.add("0");
            }
            case VAAPI -> {
                if (constantQuality) {
                    args.add("-rc_mode");
                    args.add("CQP");
                    args.add("-qp");
                    args.add(quality);
                } else {
                    args.add("-rc_mode");
                    args.add(video.rateControl() == RateControl.CBR ? "CBR" : "VBR");
                    addBitrate(args, bitrate, maxBitrate, bufferSize);
                }
            }
            case SOFTWARE -> {
                if (encoder == VideoEncoder.X264 || encoder == VideoEncoder.X265) {
                    args.add("-tune");
                    args.add("zerolatency");
                }
                if (constantQuality) {
                    args.add("-crf");
                    args.add(quality);
                    if (encoder == VideoEncoder.VP9 || encoder == VideoEncoder.AV1_SVT) {
                        args.add("-b:v");
                        args.add("0");
                    }
                } else {
                    addBitrate(args, bitrate, maxBitrate, bufferSize);
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
        args.addAll(EncoderArgs.colourTags());
    }

    private static void addBitrate(List<String> args, String bitrate, String maxBitrate, String bufferSize) {
        args.add("-b:v");
        args.add(bitrate);
        args.add("-maxrate");
        args.add(maxBitrate);
        args.add("-bufsize");
        args.add(bufferSize);
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

    private static void addVideoOutputArgs(List<String> args, int sourceWidth, int sourceHeight, VideoProfile video) {
        args.add("-fps_mode");
        args.add("cfr");
        args.add("-r");
        args.add(Integer.toString(video.fps()));
        args.add("-vf");
        args.add(videoFilter(sourceWidth, sourceHeight, video));
        addVideoCodecArgs(args, video);
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
     * @param sourceWidth      width of the frames written to stdin (normally the profile width)
     * @param sourceHeight     height of the frames written to stdin
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
        List<String> args = buildEncodeHead(ffmpegExecutable, profile, sourceWidth, sourceHeight, audioPort,
                audioOffsetSecs);
        if (publishUrls.size() == 1) {
            args.add("-flvflags");
            args.add("no_duration_filesize");
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
     * The encode part of a streaming command, without an output: shared by the
     * live command and the destination tester (which substitutes a safe output).
     */
    public static List<String> buildEncodeHead(String ffmpegExecutable, EncodeProfile profile,
                                               int sourceWidth, int sourceHeight,
                                               int audioPort, double audioOffsetSecs) {
        VideoProfile video = profile.video();
        AudioProfile audio = profile.audio();
        List<String> args = new ArrayList<>();
        addGlobalArgs(args, ffmpegExecutable, video.encoder());
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
        addVideoOutputArgs(args, sourceWidth, sourceHeight, video);
        if (hasAudio) {
            addAudioCodecArgs(args, audio);
        } else {
            args.add("-an");
        }
        return args;
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
            target.append("[f=flv:onfail=ignore:flvflags=no_duration_filesize]").append(escapeTeeUrl(url));
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
        addGlobalArgs(args, ffmpegExecutable, video.encoder());
        args.add("-y");
        addRawVideoInput(args, sourceWidth, sourceHeight, video.fps(), "rgb24");
        addVideoOutputArgs(args, sourceWidth, sourceHeight, video);
        args.add("-an");
        if (outputFile.toLowerCase(Locale.ROOT).endsWith(".mp4")
                || outputFile.toLowerCase(Locale.ROOT).endsWith(".mov")) {
            args.add("-movflags");
            args.add("+faststart");
        }
        args.add(outputFile);
        return List.copyOf(args);
    }

    /** One captured audio track to mux into a recording. */
    public record AudioTrack(String file, double offsetSeconds, String title) {
    }

    /**
     * Builds the final remux that joins the encoded video with the captured
     * audio tracks, shifting each by its measured start gap.
     */
    public static List<String> buildMuxCommand(String ffmpegExecutable, String videoFile, List<AudioTrack> tracks,
                                               AudioProfile audio, String outputFile) {
        List<String> args = new ArrayList<>();
        args.add(ffmpegExecutable);
        args.add("-nostdin");
        args.add("-hide_banner");
        args.add("-loglevel");
        args.add("info");
        args.add("-y");
        args.add("-i");
        args.add(videoFile);
        for (AudioTrack track : tracks) {
            if (track.offsetSeconds() != 0.0) {
                args.add("-itsoffset");
                args.add(String.format(Locale.ROOT, "%.4f", track.offsetSeconds()));
            }
            args.add("-i");
            args.add(track.file());
        }
        args.add("-map");
        args.add("0:v:0");
        for (int i = 0; i < tracks.size(); i++) {
            args.add("-map");
            args.add((i + 1) + ":a:0");
        }
        args.add("-c:v");
        args.add("copy");
        addAudioCodecArgs(args, audio);
        for (int i = 0; i < tracks.size(); i++) {
            String title = tracks.get(i).title();
            if (title != null && !title.isBlank()) {
                args.add("-metadata:s:a:" + i);
                args.add("title=" + title);
            }
        }
        if (outputFile.toLowerCase(Locale.ROOT).endsWith(".mp4")
                || outputFile.toLowerCase(Locale.ROOT).endsWith(".mov")) {
            args.add("-movflags");
            args.add("+faststart");
        }
        args.add(outputFile);
        return List.copyOf(args);
    }

    /** Single-track convenience form. */
    public static List<String> buildMuxCommand(String ffmpegExecutable, String videoFile, String audioFile,
                                               AudioProfile audio, double audioOffsetSeconds, String outputFile) {
        return buildMuxCommand(ffmpegExecutable, videoFile,
                List.of(new AudioTrack(audioFile, audioOffsetSeconds, "")), audio, outputFile);
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
