package dev.streamable.ui.kit;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * The Studio's one custom GUI pipeline: signed-distance rounded rectangles.
 *
 * <p>Vanilla GUI drawing only has axis-aligned fills, which is what makes mod
 * screens look blocky. This pipeline draws rounded panels, pills, borders and
 * soft shadows anti-aliased at any GUI scale, and it is submitted through
 * Minecraft's own deferred GUI renderer, so it layers correctly with text,
 * items and tooltips. It inherits blending and depth state from the vanilla GUI
 * pipeline.</p>
 */
public final class UiPipelines {

    /** Position, colour, local position (UV0), half size (UV1), radius and mode (UV2). */
    public static final VertexFormat ROUNDED_FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("Color", VertexFormatElement.COLOR)
            .add("UV0", VertexFormatElement.UV0)
            .add("UV1", VertexFormatElement.UV1)
            .add("UV2", VertexFormatElement.UV2)
            .build();

    public static final RenderPipeline ROUNDED_RECT = RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("streamable", "pipeline/rounded_rect"))
            .withVertexShader(Identifier.fromNamespaceAndPath("streamable", "core/rounded_rect"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("streamable", "core/rounded_rect"))
            .withVertexFormat(ROUNDED_FORMAT, VertexFormat.Mode.QUADS)
            .build();

    private UiPipelines() {
    }
}
