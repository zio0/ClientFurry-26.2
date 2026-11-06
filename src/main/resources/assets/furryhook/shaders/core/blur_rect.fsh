#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <furryhook:ui_common.glsl>
#moj_import <furryhook:ui_fragment.glsl>

uniform sampler2D Sampler0;

in vec2 localPos;
in vec2 screenUv;
in vec4 tintColor;
flat in vec2 halfSize;
flat in float cornerRadius;
flat in float overallAlpha;
flat in vec2 lightDir;
flat in float bevelWidth;
flat in float specularStrength;
flat in float dispersion;
flat in float frost;

out vec4 fragColor;

const vec2 DEFAULT_LIGHT = vec2(-0.5547, -0.8321);

vec3 sampleBackdrop(vec2 uv) {
    return texture(Sampler0, clamp(uv, vec2(0.0), vec2(1.0))).rgb;
}

void main() {
    vec2 half_ = max(halfSize, vec2(0.5));
    float radius = clamp(cornerRadius, 0.0, min(half_.x, half_.y));
    float dist = ui_roundedBoxSdfUniform(localPos, half_, radius);
    vec2 normal = ui_roundedBoxNormal(localPos, half_, radius);

    // How far inside the bevel this fragment sits: 0 at the rim, 1 past it.
    float bevel = min(bevelWidth, min(half_.x, half_.y));
    float depth = bevel > 0.0 ? clamp(-dist / bevel, 0.0, 1.0) : 1.0;
    float rim = 1.0 - depth;

    // Quarter-circle bevel profile. The slope of that surface runs away at the
    // rim and flattens to nothing in the middle of the panel, which is what
    // makes the edge read as a thick piece of glass instead of a gradient.
    float slope = rim / sqrt(max(1.0 - rim * rim, 0.02));
    float bend = min(slope, 4.0) * bevel * 0.22;

    // Derivative work stays in uniform control flow, ahead of every discard,
    // so the local -> uv Jacobian is defined across the whole quad.
    vec2 refraction = ui_localOffsetToUv(normal * bend, localPos, screenUv);
    vec2 grainLocal = vec2(
        ui_hash21(gl_FragCoord.xy) - 0.5,
        ui_hash21(gl_FragCoord.xy + 17.31) - 0.5
    ) * frost * 2.0;
    vec2 grain = ui_localOffsetToUv(grainLocal, localPos, screenUv);

    float mask = ui_coverage(dist);
    if (mask <= 0.003) {
        discard;
    }

    // Backdrop was blurred once per glass run by GuiBackdrop (dual-kawase at
    // reduced resolution); a single bilinear tap replaces the old per-pixel
    // 33x33 gaussian loop.
    vec3 blurred;
    if (bevel > 0.0) {
        // Refraction plus a touch of chromatic dispersion: the three channels
        // leave the bevel at slightly different angles, so the rim picks up the
        // faint colour fringe real glass has.
        float spread = dispersion * 0.45;
        vec2 base = screenUv + grain;
        blurred.r = sampleBackdrop(base + refraction * (1.0 + spread)).r;
        blurred.g = sampleBackdrop(base + refraction).g;
        blurred.b = sampleBackdrop(base + refraction * (1.0 - spread)).b;
    } else {
        blurred = sampleBackdrop(screenUv);
    }

    vec4 tint = tintColor * ColorModulator;
    vec3 panel = mix(blurred, tint.rgb, clamp(tint.a, 0.0, 1.0));

    if (specularStrength > 0.0) {
        vec2 light = dot(lightDir, lightDir) > 0.0001 ? normalize(lightDir) : DEFAULT_LIGHT;
        float facing = dot(normal, light);
        float falloff = rim * rim * sqrt(rim);
        // Near edge takes the direct highlight, the far edge picks up a tighter
        // internal reflection; the pair is what sells the thickness.
        float highlight = pow(max(facing, 0.0), 2.5) + 0.45 * pow(max(-facing, 0.0), 9.0);
        float shade = pow(max(-facing, 0.0), 2.0) * falloff * 0.22;
        panel *= 1.0 - shade;
        panel += vec3(specularStrength * falloff * highlight * 0.85);
    }

    panel += vec3(ui_dither(gl_FragCoord.xy));

    float alpha = mask * overallAlpha;
    if (alpha <= 0.003) {
        discard;
    }
    fragColor = vec4(clamp(panel, 0.0, 1.0), alpha);
}
