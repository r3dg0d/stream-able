#version 330

// Stream-able UI: signed-distance rounded rectangles.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

in vec3 Position;
in vec4 Color;
in vec2 UV0;     // position relative to the rectangle centre, GUI pixels
in ivec2 UV1;    // half width, half height, in 1/16 GUI pixels
in ivec2 UV2;    // radius in 1/16 px; mode: border width (1/16 px) or 16384 + shadow softness

out vec4 vertexColor;
out vec2 localPos;
flat out vec2 halfSize;
flat out float radius;
flat out float border;
flat out float softness;

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
