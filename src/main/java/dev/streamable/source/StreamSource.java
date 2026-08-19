package dev.streamable.source;

import dev.streamable.source.transform.SourceTransform;

import java.util.UUID;

/** Common behaviour of anything the compositor can draw. */
public interface StreamSource {

    /** Stable identity. Renaming a source must never break references to it. */
    UUID id();

    String name();

    void setName(String name);

    SourceKind kind();

    SourceTransform transform();

    void setTransform(SourceTransform transform);

    /** Hidden sources are skipped by the compositor but keep their settings. */
    boolean visible();

    void setVisible(boolean visible);

    /** Locked sources cannot be selected or dragged in the editor. */
    boolean locked();

    void setLocked(boolean locked);

    /** 0..1 multiplier applied to the source's alpha when compositing. */
    float opacity();

    void setOpacity(float opacity);

    OutputRouting routing();

    void setRouting(OutputRouting routing);
}
