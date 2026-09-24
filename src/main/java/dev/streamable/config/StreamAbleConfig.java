package dev.streamable.config;

import dev.streamable.source.BrowserSource;
import dev.streamable.source.SourceList;

import java.util.ArrayList;
import java.util.List;

/**
 * Root of the Stream-able configuration.
 *
 * <p>Versioned so future changes can migrate rather than reset. Everything is
 * repaired by {@link #validate()} after loading, because the file is
 * user-editable and a bad value must degrade to a sane default instead of
 * preventing the game from starting.</p>
 */
public final class StreamAbleConfig {

    /** Bumped whenever the on-disk shape changes in a way that needs migration. */
    public static final int CURRENT_SCHEMA_VERSION = 2;

    public int schemaVersion = CURRENT_SCHEMA_VERSION;

    public RecordingSettings recording = new RecordingSettings();
    public StreamingSettings streaming = new StreamingSettings();
    public InterfaceSettings ui = new InterfaceSettings();
    public VideoSettings video = new VideoSettings();
    public RuntimeSettings runtime = new RuntimeSettings();
    public MicrophoneSettings microphone = new MicrophoneSettings();
    public List<BrowserSourceSettings> browserSources = new ArrayList<>();

    /** Set once a legacy Record-able configuration has been imported. */
    public boolean legacyRecordableImported = false;

    public void validate() {
        if (recording == null) {
            recording = new RecordingSettings();
        }
        if (streaming == null) {
            streaming = new StreamingSettings();
        }
        if (ui == null) {
            ui = new InterfaceSettings();
        }
        if (video == null) {
            video = new VideoSettings();
        }
        if (runtime == null) {
            runtime = new RuntimeSettings();
        }
        if (microphone == null) {
            microphone = new MicrophoneSettings();
        }
        if (browserSources == null) {
            browserSources = new ArrayList<>();
        }
        browserSources.removeIf(s -> s == null);
        recording.validate();
        streaming.validate();
        ui.validate();
        video.validate();
        runtime.validate();
        microphone.validate();
        schemaVersion = CURRENT_SCHEMA_VERSION;
    }

    /** Rebuilds the runtime source list, skipping entries that cannot be read. */
    public SourceList buildSourceList() {
        SourceList list = new SourceList();
        List<BrowserSource> sources = new ArrayList<>(browserSources.size());
        for (BrowserSourceSettings settings : browserSources) {
            try {
                sources.add(settings.toSource());
            } catch (RuntimeException e) {
                dev.streamable.StreamAbleLog.CORE.warn(
                        "Skipping malformed browser source '{}' in the config: {}",
                        settings.name, e.toString());
            }
        }
        list.addAll(sources);
        return list;
    }

    /** Captures the current source list back into the config for saving. */
    public void captureSourceList(SourceList list) {
        List<BrowserSourceSettings> saved = new ArrayList<>();
        for (BrowserSource source : list.snapshot()) {
            saved.add(BrowserSourceSettings.from(source));
        }
        browserSources = saved;
    }
}
