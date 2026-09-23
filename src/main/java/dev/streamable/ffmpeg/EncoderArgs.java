package dev.streamable.ffmpeg;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Per-encoder plumbing that is not rate control: hardware device setup and the
 * pixel-format conversion each encoder expects.
 *
 * <p>Getting this wrong fails silently in the worst way - an encoder that is
 * "listed" but can never start. VA-API in particular needs a render device and
 * an explicit upload into GPU memory; without them {@code h264_vaapi} refuses
 * every frame, which is why earlier builds could never actually use it.</p>
 */
public final class EncoderArgs {

    /** Colour conversion to BT.709 limited range, the standard for HD and above. */
    static final String BT709_CONVERSION = "scale=out_color_matrix=bt709:out_range=tv";

    private EncoderArgs() {
    }

    /** Global options that must precede the inputs (device initialisation). */
    public static List<String> deviceArgs(VideoEncoder encoder) {
        if (encoder.family() == VideoEncoder.Family.VAAPI) {
            return List.of("-vaapi_device", vaapiDevice());
        }
        return List.of();
    }

    /** Filter chain tail that converts RGB frames into what the encoder accepts. */
    public static String formatFilter(VideoEncoder encoder) {
        return switch (encoder.family()) {
            case VAAPI -> BT709_CONVERSION + ",format=nv12,hwupload";
            case INTEL -> BT709_CONVERSION + ",format=nv12";
            default -> BT709_CONVERSION + ",format=yuv420p";
        };
    }

    /** Colour metadata so players decode with the matrix the frames were converted with. */
    public static List<String> colourTags() {
        return List.of("-colorspace", "bt709", "-color_primaries", "bt709",
                "-color_trc", "bt709", "-color_range", "tv");
    }

    /** First DRM render node, which is what VA-API encodes on. */
    public static String vaapiDevice() {
        for (int i = 128; i < 136; i++) {
            Path node = Path.of("/dev/dri/renderD" + i);
            if (Files.exists(node)) {
                return node.toString();
            }
        }
        return "/dev/dri/renderD128";
    }
}
