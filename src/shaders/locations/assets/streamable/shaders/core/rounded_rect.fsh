#version 330
#extension GL_ARB_separate_shader_objects : require

// Minecraft 26.3 shader conventions: explicit locations (matching the vertex
// shader) and the reordered DynamicTransforms block.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 localPos;
layout(location = 2) flat in vec2 halfSize;
layout(location = 3) flat in float radius;
layout(location = 4) flat in float border;
layout(location = 5) flat in float softness;

layout(location = 0) out vec4 fragColor;

float roundedBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + vec2(r);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    float r = min(radius, min(halfSize.x, halfSize.y));
    float d = roundedBox(localPos, halfSize, r);
    float alpha;
    if (softness > 0.0) {
        // Soft drop shadow: a wide falloff around the shape.
        alpha = 1.0 - smoothstep(-softness, softness, d);
    } else {
        // Anti-aliased edge one screen pixel wide, at any GUI scale.
        float aa = max(fwidth(d), 1e-4);
        alpha = clamp(0.5 - d / aa, 0.0, 1.0);
        if (border > 0.0) {
            float inner = clamp(0.5 - (d + border) / aa, 0.0, 1.0);
            alpha = alpha - inner;
        }
    }
    vec4 color = vertexColor;
    color.a *= alpha;
    if (color.a <= 0.002) {
        discard;
    }
    fragColor = color * ColorModulator;
}
