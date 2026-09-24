package dev.streamable.ui.studio;

import dev.streamable.StreamAbleClient;
import dev.streamable.diagnostics.HealthReport;
import dev.streamable.ffmpeg.FFmpegManager;
import dev.streamable.ffmpeg.VideoEncoder;
import dev.streamable.runtime.ManagedRuntime;
import dev.streamable.runtime.RuntimeState;
import dev.streamable.streaming.StreamDestination;
import dev.streamable.ui.kit.Dropdown;
import dev.streamable.ui.kit.TextField;
import dev.streamable.ui.kit.Theme;
import dev.streamable.video.Resolution;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * What every Studio page shares: the client, the screen, the record / live
 * actions, formatting, and small factories for bound form controls.
 */
public final class Studio {

    public record StatusItem(String text, int color) {
    }

    private final StreamAbleClient client;
    private final StudioScreen screen;

    /** Page-local selections that survive a rebuild of the tree. */
    java.util.UUID selectedDestination;

    Studio(StreamAbleClient client, StudioScreen screen) {
        this.client = client;
        this.screen = screen;
    }

    public StreamAbleClient client() {
        return client;
    }

    public StudioScreen screen() {
        return screen;
    }

    public void toast(String message) {
        screen.toast(message, Theme.SUCCESS);
    }

    public void error(String message) {
        screen.toast(message, Theme.DANGER);
    }

    /** Persists the configuration (debounced by the client). */
    public void changed() {
        client.markDirty();
    }

    /** A microphone setting changed: the DSP worker reconfigures on the next block. */
    public void micChanged() {
        client.config().microphone.touch();
        client.microphone().reconcile();
        client.markDirty();
    }

    // ---- actions ----------------------------------------------------------------------

    public void toggleRecording() {
        if (client.recording().isActive()) {
            Path file = client.stopRecording();
            toast(file == null ? "Recording stopped." : "Saved " + file.getFileName());
            return;
        }
        String error = client.startRecording();
        if (error != null) {
            error(error);
        } else {
            toast("Recording started.");
        }
    }

    public void toggleStreaming() {
        if (client.streaming().isLive()) {
            screen.confirm("End the stream?", "Every destination stops receiving video.", "End Stream", true,
                    () -> {
                        client.stopStreaming();
                        toast("Stream ended.");
                    });
            return;
        }
        long ready = client.streaming().destinations().stream().filter(StreamDestination::isReadyToStream).count();
        if (ready == 0) {
            error("Add a destination with a stream key first (Destinations page).");
            return;
        }
        String error = client.startStreaming();
        if (error != null) {
            error(error);
        } else {
            toast("Going live to " + ready + (ready == 1 ? " destination." : " destinations."));
        }
    }

    public void copyToClipboard(String text, String confirmation) {
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
        toast(confirmation);
    }

    // ---- status ------------------------------------------------------------------------

    public boolean runtimesNeedAttention() {
        if (!client.ffmpeg().resolution().isAvailable()) {
            return true;
        }
        for (ManagedRuntime runtime : client.runtimes().all()) {
            RuntimeState state = runtime.state();
            if (state == RuntimeState.FAILED || state == RuntimeState.UPDATE_AVAILABLE) {
                return true;
            }
        }
        return false;
    }

    /** Status bar items, in priority order (the last ones drop off on narrow windows). */
    List<StatusItem> statusItems() {
        List<StatusItem> items = new ArrayList<>();
        FFmpegManager.Resolution ffmpeg = client.ffmpeg().resolution();
        if (ffmpeg.isAvailable()) {
            items.add(new StatusItem("FFmpeg " + shortVersion(ffmpeg.version()), Theme.SUCCESS));
        } else {
            RuntimeState state = client.ffmpegRuntime().state();
            items.add(new StatusItem(state == RuntimeState.FAILED || state == RuntimeState.NOT_INSTALLED
                    ? "FFmpeg not available" : "FFmpeg " + client.ffmpegRuntime().progress().summary(),
                    state == RuntimeState.FAILED ? Theme.DANGER : Theme.WARNING));
        }
        items.add(encoderStatus());
        Resolution canvas = client.canvasResolution();
        items.add(new StatusItem("Canvas " + canvas.label() + " (" + canvas.marketedRatio() + ")", Theme.ACCENT));
        items.add(new StatusItem("Rec " + client.recordingOutput().label() + " · Stream "
                + client.streamingOutput().label() + " "
                + client.config().video.streaming.effectiveMode().displayName(), Theme.ACCENT));
        items.add(new StatusItem(Minecraft.getInstance().getFps() + " FPS", Theme.TEXT_MUTED));
        items.add(microphoneStatus());
        if (client.streaming().isLive()) {
            HealthReport report = client.healthReport();
            items.add(new StatusItem("Network " + report.network().label(), conditionColor(report.network())));
            items.add(new StatusItem(client.health().framesDropped() + " dropped",
                    client.health().framesDropped() > 0 ? Theme.WARNING : Theme.SUCCESS));
        }
        return items;
    }

    private StatusItem encoderStatus() {
        if (client.streaming().isLive()) {
            return new StatusItem(client.health().encoderName(), Theme.SUCCESS);
        }
        if (!client.encoderProbe().isComplete()) {
            return new StatusItem("Testing encoders...", Theme.WARNING);
        }
        VideoEncoder best = client.encoderProbe().bestEncoder(true);
        return best == null ? new StatusItem("No stream encoder", Theme.DANGER)
                : new StatusItem(best.displayName(), best.isHardware() ? Theme.SUCCESS : Theme.TEXT_MUTED);
    }

    private StatusItem microphoneStatus() {
        var settings = client.config().microphone;
        if (!client.config().recording.captureMicrophone) {
            return new StatusItem("Mic off", Theme.TEXT_MUTED);
        }
        if (settings.muted) {
            return new StatusItem("Mic muted", Theme.DANGER);
        }
        if (settings.pushToTalk) {
            return new StatusItem("Mic push-to-talk", Theme.INFO);
        }
        return new StatusItem(client.microphone().isCapturing() ? "Mic on" : "Mic idle",
                client.microphone().isCapturing() ? Theme.SUCCESS : Theme.TEXT_MUTED);
    }

    public static int conditionColor(HealthReport.Condition condition) {
        return switch (condition) {
            case GOOD -> Theme.SUCCESS;
            case FAIR -> Theme.WARNING;
            case POOR -> Theme.DANGER;
            case OFFLINE -> Theme.TEXT_MUTED;
        };
    }

    public static int severityColor(HealthReport.Severity severity) {
        return switch (severity) {
            case OK -> Theme.SUCCESS;
            case INFO -> Theme.INFO;
            case WARNING -> Theme.WARNING;
            case CRITICAL -> Theme.DANGER;
        };
    }

    // ---- formatting ---------------------------------------------------------------------

    /** "n8.1.3" from "ffmpeg version n8.1.3-20260922 Copyright ...". */
    public static String shortVersion(String version) {
        if (version == null || version.isBlank()) {
            return "";
        }
        String[] words = version.split("\\s+");
        for (int i = 0; i + 1 < words.length; i++) {
            if (words[i].equals("version")) {
                String v = words[i + 1];
                int dash = v.indexOf('-');
                return dash > 0 ? v.substring(0, dash) : v;
            }
        }
        return words[0];
    }

    /** m:ss, or h:mm:ss past an hour. */
    public static String clock(long millis) {
        long s = Math.max(0, millis) / 1000;
        return s >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    public static String bytes(long bytes) {
        if (bytes < 0) {
            return "unknown";
        }
        if (bytes >= 1L << 30) {
            return String.format(Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30));
        }
        return String.format(Locale.ROOT, "%.0f MB", bytes / (double) (1L << 20));
    }

    public static String db(double value) {
        return String.format(Locale.ROOT, "%.1f dB", value);
    }

    public static String fixed(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    // ---- bound controls -------------------------------------------------------------------

    /**
     * A numeric field bound to an int. Partial or out-of-range input is ignored
     * while typing rather than fighting the user; the value is committed as
     * soon as it parses and lies within the range.
     */
    public TextField intField(String label, IntSupplier get, IntConsumer set, int min, int max) {
        return new TextField(label, () -> Integer.toString(get.getAsInt()), text -> {
            try {
                int value = Integer.parseInt(text.trim());
                if (value >= min && value <= max) {
                    set.accept(value);
                    changed();
                }
            } catch (NumberFormatException ignored) {
                // Still typing.
            }
        }).numeric();
    }

    public TextField doubleField(String label, DoubleSupplier get, DoubleConsumer set, double min, double max) {
        return new TextField(label, () -> trimNumber(get.getAsDouble()), text -> {
            try {
                double value = Double.parseDouble(text.trim());
                if (value >= min && value <= max) {
                    set.accept(value);
                    changed();
                }
            } catch (NumberFormatException ignored) {
                // Still typing.
            }
        }).numeric();
    }

    private static String trimNumber(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format(Locale.ROOT, "%.2f", value);
    }

    /** A drop-down over an enum. */
    public <E extends Enum<E>> Dropdown enumDropdown(String label, E[] values, Function<E, String> name,
                                                    Supplier<E> get, Consumer<E> set) {
        List<String> names = Arrays.stream(values).map(name).toList();
        return new Dropdown(label, () -> names, () -> get.get().ordinal(), index -> {
            set.accept(values[index]);
            changed();
        });
    }

    /** A drop-down over a list of strings, storing the chosen string. */
    public Dropdown stringDropdown(String label, Supplier<List<String>> options, Supplier<String> get,
                                   Consumer<String> set) {
        return new Dropdown(label, options, () -> options.get().indexOf(get.get()), index -> {
            List<String> values = options.get();
            if (index >= 0 && index < values.size()) {
                set.accept(values.get(index));
                changed();
            }
        });
    }
}
