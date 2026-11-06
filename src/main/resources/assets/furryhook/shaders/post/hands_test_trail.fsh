#version 330 core

in vec2 uv;
out vec4 finalColor;

uniform sampler2D PrevSampler;
uniform sampler2D SceneSampler;
uniform sampler2D MaskSampler;

layout(std140) uniform HandsTestTrailUniforms {
    vec2 TexSize;
    float Time;
    float Intensity;
    float Speed;
    float Length;
    float TrailSoftness;
    float TrailBlur;
    float Smoke;
    float Activity;
    float TrailFade;
    float Slash;
    float SlashDir;
    float SwingHand;
    vec2 CamShift;
    vec4 GlowColor;
};

vec2 texelSize = vec2(0.0);

float sampleMask(vec2 sampleUv) {
    // furryhook's hand mask stores scene color in .rgb and coverage in .a
    // (unlike the reference this was ported from, which duplicated the
    // mask value into every channel).
    return texture(MaskSampler, clamp(sampleUv, vec2(0.0), vec2(1.0))).a;
}

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);

    float a = hash12(i);
    float b = hash12(i + vec2(1.0, 0.0));
    float c = hash12(i + vec2(0.0, 1.0));
    float d = hash12(i + vec2(1.0, 1.0));

    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;

    for (int i = 0; i < 4; i++) {
        v += noise(p) * a;
        p = p * 2.04 + vec2(19.17, 7.31);
        a *= 0.5;
    }

    return v;
}

vec3 vividColor(vec3 color) {
    float peak = max(max(color.r, color.g), color.b);
    if (peak < 0.05) return GlowColor.rgb;

    vec3 vivid = color / max(peak, 0.20);
    return clamp(mix(color, vivid, 0.36) * 1.05, 0.0, 1.0);
}

void addSource(vec2 sourceUv, float weight, inout vec3 color, inout float alpha) {
    float mask = sampleMask(sourceUv);
    if (mask <= 0.001) return;

    vec3 sceneColor = texture(SceneSampler, clamp(sourceUv, vec2(0.0), vec2(1.0))).rgb;
    float sourceWeight = mask * weight;
    color += vividColor(sceneColor) * sourceWeight;
    alpha += sourceWeight;
}

void main() {
    vec2 texCoord = uv;
    texelSize = 1.0 / max(TexSize, vec2(1.0));

    float topDistance = 1.0 - texCoord.y;
    float topEdgeFade = smoothstep(0.012, 0.085, topDistance);
    float intensityC = clamp(Intensity, 0.0, 1.5);
    float speedC = clamp(Speed, 0.35, 2.4);
    float lengthC = clamp(Length, 0.1, 1.0);
    float softness = clamp(TrailSoftness, 0.55, 2.0);
    float blurRadius = clamp(TrailBlur, 0.45, 2.5);
    float smokeC = clamp(Smoke, 0.0, 0.8);
    float activityC = clamp(Activity, 0.0, 1.0);
    float fadeSetting = clamp(TrailFade, 0.55, 0.96);
    float slashC = clamp(Slash, 0.0, 1.0);
    float slashDirection = SlashDir < 0.0 ? -1.0 : 1.0;
    float handMask = SwingHand == 0.0
        ? 0.0
        : (SwingHand > 0.0
            ? smoothstep(0.40, 0.58, texCoord.x)
            : 1.0 - smoothstep(0.42, 0.60, texCoord.x));
    float slashReturn = smoothstep(0.20, 0.78, 1.0 - slashC);
    vec2 slashAxis = normalize(mix(vec2(0.88 * slashDirection, -0.48),
                                   vec2(-0.58 * slashDirection, 0.24),
                                   slashReturn));
    vec2 slashNormal = vec2(-slashAxis.y, slashAxis.x);

    float n = fbm(texCoord * vec2(34.0, 29.0) + vec2(Time * 0.13 * speedC, -Time * 0.10 * speedC));
    vec2 curl = vec2(
        fbm(texCoord * 28.0 + vec2(Time * 0.20 * speedC, 3.1)),
        fbm(texCoord * 31.0 + vec2(8.4, -Time * 0.17 * speedC))
    ) - 0.5;

    vec2 prevUv = texCoord + CamShift;
    prevUv += curl * texelSize * (1.9 + blurRadius * 1.35);

    float idle = (1.0 - activityC);
    vec2 sway = vec2(
        sin(Time * 0.85 * speedC),
        cos(Time * 0.55 * speedC) * 0.32
    );
    prevUv += sway * texelSize * idle * (6.5 + blurRadius * 5.5 + lengthC * 4.0);
    vec4 prev = texture(PrevSampler, clamp(prevUv, vec2(0.0), vec2(1.0)));
    vec2 edge = step(vec2(0.0), prevUv) * step(prevUv, vec2(1.0));
    prev *= edge.x * edge.y;

    float fade = mix(max(0.72, fadeSetting - 0.04), fadeSetting, min(softness, 1.8) / 1.8);
    fade = mix(fade - activityC * 0.010, 0.955, slashC * handMask * 0.18);
    prev.rgb *= fade;
    prev.a *= fade;
    prev *= topEdgeFade;
    if (prev.a < 0.006) {
        prev = vec4(0.0);
    }

    vec3 sourceColor = vec3(0.0);
    float sourceAlpha = 0.0;

    float spread = 2.2 + blurRadius * 2.85 + lengthC * 7.5;
    for (int i = 0; i < 18; i++) {
        float fi = float(i);
        float angle = fi * 2.399963 + Time * (0.18 + speedC * 0.09) + n * 2.6;
        vec2 dir = vec2(cos(angle), sin(angle));
        float dist = 1.1 + fi * spread / 7.2;
        vec2 sourceUv = texCoord - dir * texelSize * dist - curl * texelSize * (2.0 + fi * 0.26);
        float weight = (18.0 - fi) / 18.0;
        addSource(sourceUv, weight, sourceColor, sourceAlpha);
    }

    if (slashC > 0.001) {
        float slashLength = 7.5 + blurRadius * 6.0 + lengthC * 12.0;
        for (int i = 0; i < 26; i++) {
            float t = float(i) / 25.0;
            float arc = sin(t * 3.1415927);
            float curve = t - 0.45;
            vec2 slashOffset = slashAxis * texelSize * ((t - 0.18) * slashLength * 8.0);
            slashOffset += slashNormal * texelSize * ((curve * curve * 30.0 - 4.5) * arc);
            slashOffset += curl * texelSize * (1.4 + t * 2.0);
            vec2 sourceUv = texCoord - slashOffset * (0.72 + slashC * 0.42);
            float weight = slashC * handMask * arc * (1.55 - t * 0.45) * (1.00 + activityC * 0.55);
            addSource(sourceUv, weight, sourceColor, sourceAlpha);
        }
    }

    float body = sampleMask(texCoord);
    float outside = 1.0 - body * 0.97;
    float wisps = mix(0.45, 1.16, fbm(texCoord * 104.0 + vec2(-Time * 0.34 * speedC, Time * 0.23 * speedC)));

    float newAlpha = smoothstep(0.022, 0.20, sourceAlpha / 3.6);
    newAlpha *= outside * wisps;
    newAlpha *= (0.22 + intensityC * 0.24 + smokeC * 0.30 + activityC * 0.10 + slashC * handMask * 0.28);
    newAlpha *= topEdgeFade;

    vec3 newColor = sourceAlpha > 0.001 ? sourceColor / sourceAlpha : GlowColor.rgb;
    vec3 slashColor = clamp(mix(newColor, GlowColor.rgb, 0.32) * (1.0 + slashC * handMask * 0.45), 0.0, 1.0);
    newColor = mix(newColor, slashColor, slashC * handMask * 0.70);
    vec3 outColor = mix(prev.rgb, newColor, clamp(newAlpha * (2.9 + slashC * 1.4), 0.0, 0.86));
    float outAlpha = clamp(prev.a + newAlpha * (1.0 - prev.a), 0.0, 0.82 + slashC * 0.08);
    if (outAlpha < 0.005) {
        outColor = vec3(0.0);
        outAlpha = 0.0;
    }

    finalColor = vec4(outColor, outAlpha);
}
