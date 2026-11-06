#version 330 core

in vec2 stripUv;   // x: distance along the ribbon, y: across it in [-1, 1]
in vec4 stripColor;

out vec4 finalColor;

uniform sampler2D DepthSampler;

layout(std140) uniform GlowStripUniforms {
    vec4 GlobalParams; // time, glow, softness, core width
    vec4 ScreenParams; // width, height, reverse-z, halo padding
    vec4 BloomParams;  // emissive pass, hdr gain, chromatic, flow speed
};

float hash21(vec2 value) {
    vec3 value3 = fract(vec3(value.xyx) * 0.1031);
    value3 += dot(value3, value3.yzx + 33.33);
    return fract((value3.x + value3.y) * value3.z);
}

void main() {
    float time = GlobalParams.x;
    float glow = GlobalParams.y;
    float softness = GlobalParams.z;
    float coreWidth = max(GlobalParams.w, 0.02);
    float emissivePass = BloomParams.x;
    float hdrGain = BloomParams.y;
    float chromatic = BloomParams.z;
    float flowSpeed = BloomParams.w;

    // across == 1.0 is the edge of the width the caller asked for; the quad runs
    // out to the padding so the halo has somewhere to fall off.
    float pad = max(ScreenParams.w, 1.0);
    float across = abs(stripUv.y);

    // Same light model as the particles: a hard filament, an inverse-square halo
    // with a long tail, and a wide soft skirt underneath both.
    float core = 1.0 - smoothstep(0.0, coreWidth, across);
    float falloff = across * across * 6.0;
    float halo = glow / (1.0 + falloff);
    float bleed = glow * 0.3 / (1.0 + across * 2.0);
    float edgeFade = 1.0 - smoothstep(pad * 0.55, pad, across);

    float chromaSpread = 0.16 * chromatic;
    float haloRed = glow / (1.0 + falloff * (1.0 - chromaSpread));
    float haloBlue = glow / (1.0 + falloff * (1.0 + chromaSpread));
    vec3 chromaTint = clamp(
        vec3(haloRed, halo, haloBlue) / max(halo, 1e-3),
        0.55,
        1.7
    );

    // A bright node travelling along the ribbon. Without it a long strip reads as
    // a static painted stroke no matter how much glow is piled on.
    float flow = 0.82 + 0.3 * sin((stripUv.x * 5.0 - time * flowSpeed) * 6.2831853);

    vec2 screenUv = gl_FragCoord.xy / ScreenParams.xy;
    float sceneDepth = texture(DepthSampler, screenUv).r;
    float depthDifference;
    float clearDepth;
    if (ScreenParams.z > 0.5) {
        depthDifference = gl_FragCoord.z - sceneDepth;
        clearDepth = 1.0 - step(0.000001, sceneDepth);
    } else {
        depthDifference = sceneDepth - gl_FragCoord.z;
        clearDepth = step(0.999999, sceneDepth);
    }
    if (clearDepth < 0.5 && depthDifference < -0.000001) {
        discard;
    }
    float soft = clearDepth > 0.5
            ? 1.0
            : smoothstep(0.0, softness, max(depthDifference, 0.0));

    float energy = (core + halo + bleed) * edgeFade * stripColor.a * soft;
    float alpha = clamp(energy, 0.0, 1.0);

    vec3 base = stripColor.rgb;
    vec3 lit = mix(base, vec3(1.0), pow(core, 3.0) * 0.9);
    float brightness = (1.0 + glow * (1.5 * core + 0.8 * (halo + bleed))) * flow;
    vec3 color = lit * mix(vec3(1.0), chromaTint, chromatic) * brightness;

    if (emissivePass > 0.5) {
        if (energy <= 0.002) {
            discard;
        }
        finalColor = vec4(color * energy * hdrGain, 1.0);
        return;
    }

    if (alpha <= 0.004) {
        discard;
    }
    float dither = (hash21(gl_FragCoord.xy + fract(time)) - 0.5) / 255.0;
    finalColor = vec4(color * alpha + dither, alpha);
}
