#version 330
#extension GL_ARB_separate_shader_objects : require

// Stream-able UI: signed-distance rounded rectangles.
// Minecraft 26.3 shader conventions: explicit locations on every input/output
// (they must match ROUNDED_FORMAT's attribute order) and the reordered
// DynamicTransforms block (TextureMat before ColorModulator).
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;     // position relative to the rectangle centre, GUI pixels
layout(location = 3) in ivec2 UV1;    // half width, half height, in 1/16 GUI pixels
layout(location = 4) in ivec2 UV2;    // radius in 1/16 px; mode: border width (1/16 px) or 16384 + shadow softness

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 localPos;
layout(location = 2) flat out vec2 halfSize;
layout(location = 3) flat out float radius;
layout(location = 4) flat out float border;
layout(location = 5) flat out float softness;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    localPos = UV0;
    halfSize = vec2(UV1) / 16.0;
    radius = float(UV2.x) / 16.0;
    if (UV2.y >= 16384) {
        softness = float(UV2.y - 16384) / 16.0;
        border = 0.0;
    } else {
        softness = 0.0;
        border = float(UV2.y) / 16.0;
    }
}
