package dev.streamable.ui.studio;

import dev.streamable.StreamAbleClient;
import dev.streamable.audio.AudioBus;
import dev.streamable.audio.dsp.Db;
import dev.streamable.ui.kit.IconButton;
import dev.streamable.ui.kit.Icons;
import dev.streamable.ui.kit.Painter;
import dev.streamable.ui.kit.Slider;
import dev.streamable.ui.kit.Theme;
import dev.streamable.ui.kit.UiNode;

import java.util.Locale;

/**
 * One mixer channel: name, mute, volume and a peak meter.
 *
 * <p>The microphone meter reads the processed chain output directly, so it
 * moves in the Studio even before a recording starts. The other buses show
 * the mixer's rolling peak, which only exists while an output is running.</p>
 */
final class MixerStrip extends UiNode {

    private static final int HEIGHT = 32;

    private final StreamAbleClient client;
    private final AudioBus bus;
    private final IconButton mute;
    private final Slider volume;
    private double shownDb = Db.FLOOR_DB;

    MixerStrip(Studio studio, AudioBus bus) {
        this.client = studio.client();
        this.bus = bus;
        this.mute = add(new IconButton(() -> bus.muted() ? Icons.Icon.MIC_OFF : Icons.Icon.SPEAKER,
                "Mute " + bus.kind().displayName(), () -> bus.setMuted(!bus.muted()))
                .activeWhen(bus::muted, Theme.DANGER));
        this.volume = add(new Slider(bus.kind().displayName(), 0, 1.5, 0.01, bus::volume,
                v -> bus.setVolume((float) v))
                .format(v -> v <= 0.001 ? "-∞ dB" : String.format(Locale.ROOT, "%+.1f dB", 20 * Math.log10(v)))
                .defaultValue(1.0));
        tooltip(switch (bus.kind()) {
            case GAME -> "Minecraft's own sound, captured from its audio output.";
            case MICROPHONE -> "Your microphone after the processing chain on the Audio page.";
            case VOICE_CHAT -> "Other players' voices from Plasmo Voice or Simple Voice Chat, when installed.";
            case BROWSER -> "Browser source audio (limited by the embedded browser; see Sources).";
        });
    }

    @Override
    public int preferredHeight(int availableWidth) {
        return HEIGHT;
    }

    @Override
    protected void layout() {
        mute.setBounds(x + width - 16, y + 1, 16, 14);
        volume.setBounds(x, y, width - 22, 24);
    }

    private double levelDb() {
        if (bus.kind() == AudioBus.Kind.MICROPHONE && client.microphone().isCapturing()) {
            return client.microphone().processor().chain().outputLevel().peakDb();
        }
        if (!client.audioMixer().isActive()) {
            return Db.FLOOR_DB;
        }
        return Db.fromLinear(bus.peakLevel() * bus.effectiveGain());
    }

    @Override
    protected void renderSelf(Painter p) {
        double db = levelDb();
        shownDb = db > shownDb ? db : shownDb + (db - shownDb) * Math.min(1, p.delta() * 8);
        int barY = y + 27;
        int barW = width - 22;
        p.roundRect(x + 4, barY, barW - 8, 3, 1.5f, 0xFF1E2230);
        float f = (float) Math.clamp((shownDb + 60) / 60.0, 0, 1);
        int color = shownDb > -6 ? Theme.DANGER : shownDb > -18 ? Theme.WARNING : Theme.SUCCESS;
        if (bus.muted()) {
            color = Theme.TEXT_DISABLED;
        }
        p.roundRect(x + 4, barY, (barW - 8) * f, 3, 1.5f, color);
    }
}
