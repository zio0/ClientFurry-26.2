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

// Worley/cellular noise: distance to the nearest and second-nearest feature
// point, plus a per-cell random id. Used to build hard angular facets in
// place of the smooth organic noise the other fill types use.
void worley(vec2 p, out float nearest, out float second, out float id) {
    vec2 cell = floor(p);
    vec2 local = fract(p);
    nearest = 8.0;
    second = 8.0;
    id = 0.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 neighbor = vec2(float(x), float(y));
            vec2 point = neighbor + hash22(cell + neighbor);
            float dist = length(point - local);
            if (dist < nearest) {
                second = nearest;
                nearest = dist;
                id = hash21(cell + neighbor);
            } else if (dist < second) {
                second = dist;
            }
        }
    }
}

void main() {
    float mask = texture(MaskSampler, uv).a;
    if (mask < 0.01) {
        discard;
    }

    float time = FillParams.x;
    vec2 position = uv * vec2(9.0, 14.0);
    position += vec2(sin(time * 0.15), cos(time * 0.12)) * 0.6;

    float nearest;
    float second;
    float id;
    worley(position, nearest, second, id);

    // Hard edges between facets (Worley F2-F1) so the surface reads as
    // angular cut glass rather than a smooth liquid.
    float edge = smoothstep(0.0, 0.06, second - nearest);
    float facetShade = mix(0.45, 1.25, id);

    // A single highlight band sweeps across the whole hand over time, like
    // light catching a rotating gem.
    float sweep = sin((uv.x + uv.y) * 6.0 - time * 1.4) * 0.5 + 0.5;
    float sheen = pow(sweep, 6.0);

    vec3 color = FillColor.rgb * facetShade;
    color = mix(color * 0.25, color, edge);
    color += vec3(1.0) * sheen * 0.6;

    finalColor = vec4(
        clamp(color, 0.0, 1.0),
        clamp(mask * FillColor.a, 0.0, 1.0)
    );
}
