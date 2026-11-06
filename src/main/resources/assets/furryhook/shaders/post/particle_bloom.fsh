#version 330 core

in vec2 uv;
out vec4 finalColor;

uniform sampler2D BloomSampler;

layout(std140) uniform ParticleBloomUniforms {
    vec4 BloomComposite; // intensity, anamorphic, texel x, time
};

const float STREAK_WEIGHTS[5] = float[5](0.28, 0.22, 0.16, 0.1, 0.06);

float hash21(vec2 value) {
    vec3 value3 = fract(vec3(value.xyx) * 0.1031);
    value3 += dot(value3, value3.yzx + 33.33);
    return fract((value3.x + value3.y) * value3.z);
}

void main() {
    float intensity = BloomComposite.x;
    float anamorphic = BloomComposite.y;
    float texelX = BloomComposite.z;
    float time = BloomComposite.w;

    vec3 bloom = texture(BloomSampler, uv).rgb;

    // Horizontal-only smear on top of the isotropic blur. Lenses flare wider
    // than they do tall, and this is the cheap half of that look: five taps a
    // side on an already half-res, already blurred buffer.
    if (anamorphic > 0.001) {
        vec3 streak = vec3(0.0);
        float total = 0.0;
        for (int index = 0; index < 5; index++) {
            float offset = float(index + 1) * texelX * 6.0;
            float weight = STREAK_WEIGHTS[index];
            streak += texture(BloomSampler, uv + vec2(offset, 0.0)).rgb * weight;
            streak += texture(BloomSampler, uv - vec2(offset, 0.0)).rgb * weight;
            total += weight * 2.0;
        }
        streak /= max(total, 1e-4);
        bloom += streak * anamorphic * vec3(0.85, 0.9, 1.0);
    }

    vec3 light = bloom * intensity;
    if (max(light.r, max(light.g, light.b)) <= 0.002) {
        discard;
    }

    // The composite writes into an 8-bit target, so a wide soft falloff would
    // band hard without this.
    light += (hash21(gl_FragCoord.xy + fract(time)) - 0.5) / 255.0;
    finalColor = vec4(max(light, vec3(0.0)), 1.0);
}
