#version 330 core

in vec2 uv;
out vec4 finalColor;

uniform sampler2D MaskSampler;

layout(std140) uniform HandFillUniforms {
    vec4 FillColor;
    vec4 FillParams;
};

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

vec2 hash22(vec2 p) {
    p = vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)));
    return fract(sin(p) * 43758.5453123);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

void main() {
    float mask = texture(MaskSampler, uv).a;
    if (mask < 0.01) {
        discard;
    }

    float time = FillParams.x;
    vec2 position = uv * vec2(7.0, 11.0);

    // Charcoal base: dark, uneven surface instead of a flat fill.
    float base = noise(position * 0.8 + vec2(time * 0.05, time * 0.03));
    vec3 color = mix(FillColor.rgb * 0.05, FillColor.rgb * 0.2, base);

    // Embers drift upward through a grid of cells; each cell re-seeds its
    // spark position and flicker phase from its own integer index, so
    // sparks flicker in place instead of streaking.
    vec2 emberSpace = position * 2.2 + vec2(0.0, -time * 1.3);
    vec3 glow = vec3(0.0);
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 cellId = floor(emberSpace) + vec2(float(x), float(y));
            vec2 jitter = hash22(cellId);
            vec2 sparkPos = cellId + jitter;
            float dist = length(sparkPos - emberSpace);
            float flicker = hash21(cellId + floor(time * 3.0 + jitter.x * 7.0));
            float brightness = smoothstep(0.16, 0.0, dist) * flicker;
            glow += FillColor.rgb * (1.6 + brightness) * brightness;
        }
    }
    color += glow;

    finalColor = vec4(
        clamp(color, 0.0, 1.0),
        clamp(mask * FillColor.a, 0.0, 1.0)
    );
}
