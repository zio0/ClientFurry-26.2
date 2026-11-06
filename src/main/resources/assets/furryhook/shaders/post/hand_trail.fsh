#version 330 core

in vec2 uv;
out vec4 finalColor;

uniform sampler2D PrevSampler;
uniform sampler2D InjectSampler;

layout(std140) uniform HandTrailUniforms {
    vec2 TexelSize;
    float Decay;
    float Rise;
    float Time;
    float EmissionFloor;
    float Turbulence;
    float Chaos;
    float Delta;
};

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(vec2 p) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int index = 0; index < 4; index++) {
        value += amplitude * vnoise(p);
        p = p * 2.0 + vec2(1.7, 9.2);
        amplitude *= 0.5;
    }
    return value;
}

void main() {
    float delta = clamp(Delta, 0.0, 0.1);
    float turbulenceFreq = mix(1.0, 2.2, clamp(Turbulence / 3.0, 0.0, 1.0));
    vec2 flowUv = uv * vec2(6.0, 4.0) * turbulenceFreq;

    // Per-pixel flow instead of one screen-wide sway: different parts of the
    // plume drift independently, so higher Turbulence breaks the shape up
    // rather than sliding the whole silhouette sideways as one block. The
    // field is a velocity (UV/second) scaled by Delta below, NOT a raw
    // per-frame offset -- without that scaling this jump was framerate
    // dependent and at high Turbulence/high FPS it displaced samples by a
    // large fraction of the screen every single frame, letting the plume
    // random-walk out into a huge unrelated blob within a couple of seconds.
    vec2 flow = vec2(
        fbm(flowUv + vec2(0.0, -Time * 1.4)),
        fbm(flowUv + vec2(37.1, -Time * 1.1))
    ) - 0.5;
    vec2 swayVelocity = flow * 0.55 * Turbulence;
    vec2 sway = swayVelocity * delta;

    // Rise is a UV-heights-per-second rate, so speed reads the same at any
    // framerate instead of being ~1 texel/frame regardless of Delta.
    vec2 sourceUv = uv + sway + vec2(0.0, -Rise * delta);

    // Pull the sample toward last frame's local horizontal centroid. Reapplied
    // every frame this narrows the plume into a taper as it rises instead of
    // dragging the full item silhouette upward unchanged (the "duplicated
    // item" look), and the pull strengthens with Rise so a faster flame
    // tapers sooner.
    float centroidNum = 0.0001 * sourceUv.x;
    float centroidDen = 0.0001;
    for (int index = -1; index <= 1; index++) {
        float offset = float(index) * TexelSize.x * 3.0;
        float weight = texture(PrevSampler, sourceUv + vec2(offset, 0.0)).a;
        centroidNum += (sourceUv.x + offset) * weight;
        centroidDen += weight;
    }
    float taperPull = clamp(Rise * delta * 2.5, 0.0, 0.4);
    sourceUv.x = mix(sourceUv.x, centroidNum / centroidDen, taperPull);

    float previous = texture(PrevSampler, sourceUv).a;

    // Exponential decay so trail length reads the same at any framerate;
    // Decay (0..1, from the Flame Trail setting) maps to a 0.15s-1.2s
    // lifetime instead of being applied as a flat per-frame multiplier
    // (which never actually shrinks the buffer when Decay == 1.0).
    float lifetime = mix(0.15, 1.2, clamp(Decay, 0.0, 1.0));
    float decayFactor = exp(-delta / max(lifetime, 0.02));

    // Subtractive cooling with noise breaks the plume into separate tongues
    // as it fades, instead of one solid blob shrinking in place.
    float coolNoise = fbm(flowUv * 1.7 + vec2(11.0, Time * 1.8));
    float cooling = (0.35 + coolNoise * 0.9) * delta * (2.6 / max(lifetime, 0.02));
    float advectedAlpha = max(previous * decayFactor - cooling, 0.0);

    // Emission comes from a thin ring at the silhouette edge (a "burner"),
    // not the full filled shape, so the base doesn't read as a flat copy of
    // the item.
    float coverage = texture(InjectSampler, uv).a;
    float ring = smoothstep(0.16, 0.5, coverage) * (1.0 - smoothstep(0.72, 0.94, coverage));
    float chaosSpread = max(Chaos, 0.001);
    float tongues = smoothstep(
        0.6 - 0.22 * chaosSpread,
        0.6 + 0.24 * chaosSpread,
        fbm(flowUv * 1.6 + vec2(5.0, -Time * 2.2 * mix(0.6, 1.6, clamp(Chaos, 0.0, 1.0))))
    );
    float emission = clamp(
        ring * (EmissionFloor + (1.0 - EmissionFloor) * tongues) * 2.0,
        0.0,
        1.0
    );

    float value = max(emission, advectedAlpha);
    finalColor = vec4(1.0, 1.0, 1.0, clamp(value, 0.0, 1.0));
}
