#version 330

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

in vec4 vertexColor;
in vec2 localPos;
flat in vec2 halfSize;
flat in float radius;
flat in float border;
flat in float softness;

out vec4 fragColor;

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
