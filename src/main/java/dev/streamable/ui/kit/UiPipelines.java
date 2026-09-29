package dev.streamable.ui.kit;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
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
 *
 * <p>Minecraft 26.2 replaced {@code VertexFormatElement} statics and
 * {@code withVertexFormat(..., Mode)} with {@code GpuFormat} attributes,
 * {@code withVertexBinding} and {@link PrimitiveTopology}. UV1/UV2 stay
 * {@code RG16_SINT} so they match {@code VertexConsumer#setUv1}/{@code setUv2}
 * (half-size and radius are packed as 1/16 GUI pixels, see the vertex shader).</p>
 */
public final class UiPipelines {

    /** Position, colour, local position (UV0), half size (UV1), radius and mode (UV2). */
    public static final VertexFormat ROUNDED_FORMAT = VertexFormat.builder(0)
            .addAttribute("Position", GpuFormat.RGB32_FLOAT)
            .addAttribute("Color", GpuFormat.RGBA8_UNORM)
            .addAttribute("UV0", GpuFormat.RG32_FLOAT)
            .addAttribute("UV1", GpuFormat.RG16_SINT)
            .addAttribute("UV2", GpuFormat.RG16_SINT)
            .build();

    public static final RenderPipeline ROUNDED_RECT = RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("streamable", "pipeline/rounded_rect"))
            .withVertexShader(Identifier.fromNamespaceAndPath("streamable", "core/rounded_rect"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("streamable", "core/rounded_rect"))
            .withVertexBinding(0, ROUNDED_FORMAT)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .build();

    private UiPipelines() {
    }
}
