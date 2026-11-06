#version 330 core

in vec2 uv;
out vec4 finalColor;

uniform sampler2D BlurredSampler;
uniform sampler2D MaskSampler;

layout(std140) uniform HandElectricUniforms {
    vec4 ArcColor;
    float Time;
    float Flicker;
    float Brightness;
    float Turbulence;
    float Chaos;
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

// Ridged noise: the crease where the noise crosses 0.5 becomes a thin line, which
// is the filament an arc is drawn along.
float ridged(vec2 p) {
    return 1.0 - abs(2.0 * vnoise(p) - 1.0);
}

// Three ridged octaves, each one warped by the previous, so the filaments bend and
// fork instead of running as a regular lattice.
float ridgeFbm(vec2 p, float seed) {
    float sum = 0.0;
    float amplitude = 0.55;
    float total = 0.0;
    vec2 q = p + seed;
    for (int i = 0; i < 3; i++) {
        float r = ridged(q);
        sum += r * amplitude;
        total += amplitude;
        // Domain warp by the octave just evaluated.
        q = q * 2.07 + vec2(r * 1.7, -r * 1.3) + 3.1;
        amplitude *= 0.55;
    }
    return sum / total;
}

void main() {
    float maskAlpha = texture(MaskSampler, uv).a;
    if (maskAlpha > 0.5) {
        discard;
    }

    float field = texture(BlurredSampler, uv).a;
    if (field < 0.004) {
        discard;
    }

    vec2 res = vec2(textureSize(MaskSampler, 0));
    vec2 texel = 1.0 / res;
    float aspect = res.x / max(res.y, 1.0);
    vec2 auv = vec2(uv.x * aspect, uv.y);

    vec3 baseColor = ArcColor.rgb;
    float turbulence = clamp(Turbulence, 0.0, 3.0);
    float chaos = clamp(Chaos, 0.0, 2.0);
    float flicker = clamp(Flicker, 0.0, 2.0);

    // Gradient of the blurred silhouette: normal points away from the arm, tangent
    // runs along its edge. Arcs are warped along these, so they wrap the hand
    // instead of sitting in a screen-space grid behind it.
    float gx = texture(BlurredSampler, uv + vec2(texel.x, 0.0)).a
             - texture(BlurredSampler, uv - vec2(texel.x, 0.0)).a;
    float gy = texture(BlurredSampler, uv + vec2(0.0, texel.y)).a
             - texture(BlurredSampler, uv - vec2(0.0, texel.y)).a;
    vec2 gradient = vec2(gx, gy);
    float gradientLength = length(gradient);
    vec2 normalDir = gradientLength > 1.0e-5 ? gradient / gradientLength : vec2(0.0, 1.0);
    vec2 tangentDir = vec2(-normalDir.y, normalDir.x);

    // Arcs re-strike on a discrete tick; between ticks the strike decays, so each
    // pattern reads as a bolt that fires and dies rather than a drifting texture.
    float chargeRate = mix(3.0, 14.0, flicker * 0.5);
    float chargeTime = Time * chargeRate;
    float charge = floor(chargeTime);
    float sub = fract(chargeTime);
    float strike = 0.35 + 0.65 * exp(-sub * 3.2);
    float chargeSeed = charge * 17.31;

    float scale = mix(18.0, 46.0, turbulence / 3.0);
    vec2 flow = tangentDir * Time * 0.35 + normalDir * (0.6 - field) * 1.6;

    // Two independent layers: heavy bolts, and a fine crackle riding on top of them.
    vec2 p1 = auv * scale + flow;
    vec2 p2 = auv * scale * 2.6 - flow * 1.7;
    float sharpMain = mix(7.0, 22.0, chaos * 0.5);
    float sharpFine = sharpMain * 1.6;

    float ridgeMain = ridgeFbm(p1, chargeSeed);
    float ridgeFine = ridgeFbm(p2, chargeSeed * 1.7 + 9.0);

    // Same filament sampled twice: a razor-thin white core and a wide bleed around
    // it. The bleed is what gives every arc its own glow, per pixel, no extra pass.
    float coreMain = pow(clamp(ridgeMain, 0.0, 1.0), sharpMain);
    float bleedMain = pow(clamp(ridgeMain, 0.0, 1.0), sharpMain * 0.22);
    float coreFine = pow(clamp(ridgeFine, 0.0, 1.0), sharpFine);
    float bleedFine = pow(clamp(ridgeFine, 0.0, 1.0), sharpFine * 0.25);

    // A coarse ridge gates where arcs appear so they fork in branches instead of
    // crackling uniformly over the entire field.
    float branchNoise = vnoise(auv * scale * 0.16 + chargeSeed * 0.7);
    float branch = smoothstep(0.28, 0.72, branchNoise);

    // Discharge lives in the shell around the silhouette, densest just off the skin.
    float shell = smoothstep(0.02, 0.30, field) * (1.0 - smoothstep(0.55, 1.0, field));
    float rim = smoothstep(0.35, 0.95, field);

    float segment = mix(0.55, 1.0, hash21(vec2(charge, floor(auv.y * 26.0))));

    float bolts = (coreMain * 1.0 + coreFine * 0.55) * branch * segment * strike;
    bolts *= mix(0.35, 1.0, shell) * smoothstep(0.01, 0.12, field);

    float bloom = (bleedMain * 0.30 + bleedFine * 0.18) * branch * shell * strike;

    // Broad corona: the soft colored light the whole arm sits in.
    float corona = pow(clamp(field, 0.0, 1.0), 1.5) * 0.55
                 + pow(clamp(field, 0.0, 1.0), 0.45) * 0.22;
    corona *= 0.85 + 0.15 * sin(Time * 6.0 + uv.y * 12.0);

    // Rare bright sparks popping on the charge tick.
    vec2 sparkCell = floor(auv * 38.0);
    float sparkHash = hash21(sparkCell + charge * 7.37);
    float sparkShape = exp(-length(fract(auv * 38.0) - 0.5) * 7.0);
    float spark = step(0.972, sparkHash) * sparkShape * shell * exp(-sub * 6.0);

    vec3 hotCore = mix(baseColor, vec3(1.0), 0.88);
    vec3 color = baseColor * (corona * 1.15 + bloom * 1.9 + rim * 0.35)
               + hotCore * bolts * 2.6
               + vec3(1.0) * spark * 3.2;
    color *= Brightness;

    float intensity = corona * 0.34
                    + bloom * 0.65
                    + bolts * 1.15
                    + spark * 1.4;
    intensity = clamp(intensity * ArcColor.a * Brightness, 0.0, 1.0);

    vec2 pixel = uv * res;
    float dither = (hash21(pixel + charge) + hash21(pixel + 19.7) - 1.0) * (0.5 / 255.0);
    intensity = clamp(
        intensity + dither * smoothstep(0.0, 0.05, intensity),
        0.0,
        1.0
    );
    finalColor = vec4(color, intensity);
}
