#version 330 core

in vec2 uv;
out vec4 finalColor;

uniform sampler2D SceneSampler;
uniform sampler2D SmokeSampler;
uniform sampler2D MaskSampler;

layout(std140) uniform HandsTestFireUniforms {
    vec2 TexSize;
    float Time;
    float Intensity;
    float HandSoftness;
    float HandBlur;
    float Smoke;
    float Activity;
    vec4 GlowColor;
};

vec2 texelSize = vec2(0.0);

float sampleMask(vec2 sampleUv) {
    // furryhook's hand mask stores scene color in .rgb and coverage in .a.
    return texture(MaskSampler, clamp(sampleUv, vec2(0.0), vec2(1.0))).a;
}

float edgeMask(vec2 sampleUv) {
    float c = sampleMask(sampleUv);
    float e = 0.0;
    e += abs(c - sampleMask(sampleUv + vec2( texelSize.x, 0.0)));
    e += abs(c - sampleMask(sampleUv + vec2(-texelSize.x, 0.0)));
    e += abs(c - sampleMask(sampleUv + vec2(0.0,  texelSize.y)));
    e += abs(c - sampleMask(sampleUv + vec2(0.0, -texelSize.y)));
    return clamp(e * 0.9, 0.0, 1.0);
}

void main() {
    vec2 texCoord = uv;
    texelSize = 1.0 / max(TexSize, vec2(1.0));

    float topDistance = 1.0 - texCoord.y;
    float topEdgeFade = smoothstep(0.012, 0.085, topDistance);
    vec4 scene = texture(SceneSampler, texCoord);
    vec4 smokeTex = texture(SmokeSampler, texCoord);

    float intensityC = clamp(Intensity, 0.0, 1.5);
    float softness = clamp(HandSoftness, 0.4, 2.5);
    float blurSetting = clamp(HandBlur, 0.2, 3.0);
    float smokeSetting = clamp(Smoke, 0.0, 0.8);
    float mask = sampleMask(texCoord);
    float edge = edgeMask(texCoord);

    float activityC = clamp(Activity, 0.0, 1.0);
    float trail = clamp(smokeTex.a, 0.0, 1.0);
    float softMix = clamp((softness - 0.4) / 2.1, 0.0, 1.0);
    float blurMix = clamp((blurSetting - 0.2) / 2.8, 0.0, 1.0);
    float bloom = pow(trail, mix(0.78, 0.48, softMix));
    float aura = smoothstep(0.012, mix(0.24, 0.38, blurMix), trail);
    float smokeAlpha = clamp(max(bloom * 0.62, aura * 0.38), 0.0, 0.76);
    smokeAlpha *= (0.84 + intensityC * 0.46 + smokeSetting * 0.35 + activityC * 0.12 + softMix * 0.14);
    smokeAlpha = clamp(smokeAlpha, 0.0, 0.86);
    smokeAlpha = smokeAlpha < 0.005 ? 0.0 : smokeAlpha;
    smokeAlpha *= 1.0 - mask * 0.18;
    smokeAlpha *= topEdgeFade;

    vec3 smokeColor = clamp(smokeTex.rgb * (1.18 + smokeSetting * 0.24), 0.0, 1.0);
    vec3 outColor = scene.rgb;
    outColor += smokeColor * smokeAlpha;
    outColor += smokeColor * edge * bloom * (0.11 + intensityC * 0.10) * (1.0 - softMix * 0.28) * topEdgeFade;

    finalColor = vec4(clamp(outColor, 0.0, 1.0), scene.a);
}
