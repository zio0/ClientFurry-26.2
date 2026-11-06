#version 330 core

in vec2 uv;
out vec4 finalColor;

uniform sampler2D SceneSampler;
uniform sampler2D BlurredSampler;
uniform sampler2D DepthSampler;

#moj_import <furryhook:block_outline_common.glsl>

layout(std140) uniform RtxReflectionUniforms {
    mat4 InvViewProjection;
    mat4 ViewProjection;
    vec4 CameraOrigin;   // xyz = camera world position (wrapped), w = 1 when clip depth is 0..1
    vec4 SurfaceParams;  // x = wetness, y = puddle coverage, z = darkening, w = up-facing gate
    vec4 MarchParams;    // x = step count, y = ray length in blocks, z = hit thickness, w = step growth
    vec4 StyleParams;    // x = fresnel amount, y = sharpness, z = strength, w = 1 / fade distance
    vec4 RippleParams;   // x = ripple amplitude, y = phase, z = ripple density, w = wave amplitude
    vec4 SkyFallback;    // rgb = tint used when the ray leaves the screen, a = its confidence
    vec4 ScreenParams;   // x = puddle scale, y = wave drift, z = unused, w = unused
};

const int MAX_STEPS = 64;
const int REFINE_STEPS = 5;

vec3 reconstruct(vec2 coords, float depth) {
    float clipZ = CameraOrigin.w > 0.5 ? depth : depth * 2.0 - 1.0;
    vec4 ndc = vec4(coords * 2.0 - 1.0, clipZ, 1.0);
    vec4 position = InvViewProjection * ndc;
    return position.xyz / position.w;
}

/**
 * Projects a camera-relative point into the same uv space the pass reads, which
 * is the exact inverse of reconstruct(). Returns w so the caller can reject
 * points at or behind the near plane, where dividing by it is meaningless.
 */
vec3 project(vec3 relative) {
    vec4 clip = ViewProjection * vec4(relative, 1.0);
    return vec3(clip.xy / max(clip.w, 1.0e-4) * 0.5 + 0.5, clip.w);
}

// ------------------------------------------------------------------ puddles

// Water does not coat a surface evenly, it pools. Thresholding a world-space
// noise field against the wetness is what turns "the whole floor is a mirror"
// into patches that grow together as it keeps raining - and being world-space,
// a puddle stays where it is when you walk around it.
float puddleMask(vec2 ground, float coverage) {
    vec2 p = ground * ScreenParams.x;
    float field = valueNoise(vec3(p, 0.0)) * 0.62
            + valueNoise(vec3(p * 2.7, 11.3)) * 0.38;
    float threshold = mix(0.86, 0.04, coverage);
    return smoothstep(threshold, threshold + 0.14, field);
}

// ------------------------------------------------------------------ surface

/**
 * Slope of one expanding impact ring, so the reflection bends where a drop lands
 * instead of only getting a bright rim drawn on it. The profile is a Gaussian
 * packet whose derivative is analytic, which is far cheaper than finite
 * differencing the whole ring field three times.
 */
vec2 rippleSlope(vec2 ground, float size, float rate, float seed, float density, float phase) {
    vec2 p = ground / size;
    vec2 id = floor(p);
    vec2 key = id + seed;
    if (hash21(key + 3.9) > density) {
        return vec2(0.0);
    }

    vec2 center = vec2(0.2 + 0.6 * hash21(key), 0.2 + 0.6 * hash21(key + 5.1));
    float life = fract(phase * rate + hash21(key + 11.7));
    float radius = life * size * 0.45;
    vec2 offset = (p - id - center) * size;
    float dist = length(offset);
    if (dist < 1.0e-4) {
        return vec2(0.0);
    }

    const float WIDTH = 0.22;
    // The raw derivative of a Gaussian this narrow peaks near 4, which would lay a
    // ring almost flat on its side. Normalized so the peak is ~0.5 and the setting
    // reads as an amplitude in a range where 1.0 is "hard rain", not "unusable".
    const float NORMALIZE = 0.13;

    float x = dist - radius;
    float envelope = exp(-(x * x) / (WIDTH * WIDTH))
            * (1.0 - life) * (1.0 - life)
            * smoothstep(0.0, 0.12, life);
    // d/ddist of envelope * exp(-x^2/w^2)
    return (offset / dist) * (envelope * -2.0 * x / (WIDTH * WIDTH)) * NORMALIZE;
}

/** Slow surface wobble, so even still water is not a perfect sheet of glass. */
vec2 waveSlope(vec2 ground, float drift) {
    vec2 q = ground * 0.55 + vec2(drift * 0.13, drift * 0.09);
    const float EPSILON = 0.18;
    float center = valueNoise(vec3(q, 0.0));
    float dx = valueNoise(vec3(q + vec2(EPSILON, 0.0), 0.0)) - center;
    float dz = valueNoise(vec3(q + vec2(0.0, EPSILON), 0.0)) - center;
    return vec2(dx, dz) / EPSILON;
}

// --------------------------------------------------------------------- main

void main() {
    vec3 scene = texture(SceneSampler, uv).rgb;
    float depth = texture(DepthSampler, uv).r;
    bool sky = depth <= 0.000001;

    // Derivatives are undefined inside non-uniform control flow, so the surface
    // frame is built for every pixel - including sky ones, whose garbage values
    // are simply never used below.
    vec3 relative = reconstruct(uv, sky ? 1.0 : depth);
    vec3 ddxPosition = dFdx(relative);
    vec3 ddyPosition = dFdy(relative);
    vec3 faceCross = cross(ddxPosition, ddyPosition);
    float faceLength = length(faceCross);
    float viewLength = length(relative);
    vec3 viewDir = viewLength > 1.0e-4 ? relative / viewLength : vec3(0.0, 0.0, -1.0);
    vec3 geoNormal = faceLength > 1.0e-9 ? faceCross / faceLength : vec3(0.0, 1.0, 0.0);
    if (dot(geoNormal, viewDir) > 0.0) {
        geoNormal = -geoNormal;
    }
    // Across a silhouette the derivative spans two surfaces and the normal it
    // produces is noise. A noisy normal on a mirror is a screenful of fireflies,
    // so those pixels are faded out of the effect entirely. The threshold is loose
    // enough to leave a floor seen at a grazing angle alone - that is where the
    // derivative is legitimately large, and it is also the angle at which a wet
    // floor looks best.
    float edgeFade = 1.0 - smoothstep(
            0.09,
            0.26,
            (length(ddxPosition) + length(ddyPosition)) / max(viewLength, 1.0)
    );

    if (sky) {
        finalColor = vec4(scene, 1.0);
        return;
    }

    // Only the tops of blocks hold water. This is also the gate that keeps the
    // effect off walls, ceilings and the undersides of overhangs.
    float upFacing = smoothstep(SurfaceParams.w, SurfaceParams.w + 0.05, geoNormal.y);
    vec3 hitPos = CameraOrigin.xyz + relative;
    float wet = SurfaceParams.x
            * upFacing
            * edgeFade
            * puddleMask(hitPos.xz, SurfaceParams.y);
    if (wet <= 0.003) {
        finalColor = vec4(scene, 1.0);
        return;
    }

    // Wetness and reflection are faded apart on purpose. Ground fifty blocks out
    // still reads as wet - that is just a darker texture and costs nothing - but
    // marching a reflection off it buys a few pixels of detail for the same price
    // as one at your feet, so the march is what gets dropped.
    float reflectFade = 1.0 - smoothstep(0.75, 1.15, viewLength * StyleParams.w);

    vec2 slope = waveSlope(hitPos.xz, ScreenParams.y) * RippleParams.w;
    if (RippleParams.x > 0.0) {
        // Two grids at different scales and rates, so impacts do not read as a
        // lattice. Same layout the rain pass uses for its rings, which is what
        // makes a drop's splash and its reflection wobble land in one place.
        slope += (rippleSlope(hitPos.xz, 1.3, 1.0, 0.0, RippleParams.z, RippleParams.y)
                + rippleSlope(hitPos.xz, 2.7, 0.6, 31.4, RippleParams.z, RippleParams.y))
                * RippleParams.x;
    }
    vec3 normal = normalize(geoNormal + vec3(-slope.x, 0.0, -slope.y));

    vec3 reflectDir = reflect(viewDir, normal);
    vec3 reflColor = SkyFallback.rgb;
    // The sky fallback is only honest for a ray that could plausibly reach the
    // sky. A grazing ray at a wet floor two hundred blocks out mostly walks off
    // the screen side, not up - so confidence there has to fade with the same
    // range curve the march itself uses, otherwise the fallback paints a flat
    // sky-coloured band along the horizon on any surface that turned wet.
    float confidence = SkyFallback.a * reflectFade;

    // A ray bent back into the surface it left has nothing to find, and neither
    // does one for a puddle too far away to be worth marching.
    if (dot(reflectDir, geoNormal) > 0.01 && reflectFade > 0.002) {
        int steps = int(MarchParams.x);
        float thickness = MarchParams.z;
        float growth = MarchParams.w;
        // Geometric stride: the first block of the ray is where the accuracy has
        // to be, the last fifty are a backdrop. Solved so the whole chain sums to
        // the configured ray length.
        float stride = MarchParams.y * (growth - 1.0) / (pow(growth, float(steps)) - 1.0);

        // Half a step out, jittered, so the stair-stepping of a coarse march comes
        // out as noise the roughness blur hides rather than as a visible ladder.
        float jitter = hash21(gl_FragCoord.xy + RippleParams.y);
        float travelled = stride * (0.35 + 0.5 * jitter);
        float previous = 0.0;
        // Lifted along the normal so the first sample does not immediately hit the
        // surface the ray started on.
        vec3 origin = relative + normal * 0.035;

        for (int i = 0; i < MAX_STEPS; i++) {
            if (i >= steps) {
                break;
            }
            vec3 point = origin + reflectDir * travelled;
            vec3 projected = project(point);
            if (projected.z <= 0.05
                    || projected.x < 0.0 || projected.x > 1.0
                    || projected.y < 0.0 || projected.y > 1.0) {
                // The ray walked off the screen without hitting anything. Looking
                // down at a wet floor this happens almost every frame: the
                // reflected ray goes up in world space and leaves through the top
                // of the viewport before it finds a wall. The flat SkyFallback
                // colour is the sky as a fixed number, which at night is nearly
                // black - so the reflection reads as "no reflection at all". If
                // the exit is through the top edge, the sky is actually on screen
                // right there, and sampling it gives the true colour a puddle
                // would mirror instead of a dead fallback.
                vec2 edgeUv = clamp(projected.xy, vec2(0.001), vec2(0.999));
                if (projected.y > 1.0) {
                    edgeUv.y = 0.999;
                } else if (projected.y < 0.0) {
                    edgeUv.y = 0.001;
                }
                float edgeDepth = textureLod(DepthSampler, edgeUv, 0.0).r;
                if (edgeDepth <= 0.000001) {
                    reflColor = textureLod(SceneSampler, edgeUv, 0.0).rgb;
                    confidence = 1.0;
                }
                break;
            }

            float sampleDepth = textureLod(DepthSampler, projected.xy, 0.0).r;
            if (sampleDepth <= 0.000001) {
                // The reflected ray found open sky. That is a real hit, not a
                // miss - the sky is exactly what a puddle should be showing.
                reflColor = textureLod(SceneSampler, projected.xy, 0.0).rgb;
                confidence = 1.0;
                break;
            }

            float sceneDistance = length(reconstruct(projected.xy, sampleDepth));
            float rayDistance = length(point);
            // Behind the visible surface, but not so far behind that the ray has
            // tunnelled past a thin object and is reporting a wall two rooms away.
            if (rayDistance > sceneDistance && rayDistance - sceneDistance < thickness + stride) {
                vec2 hitUv = projected.xy;
                float low = previous;
                float high = travelled;
                for (int refine = 0; refine < REFINE_STEPS; refine++) {
                    float middle = (low + high) * 0.5;
                    vec3 probe = origin + reflectDir * middle;
                    vec3 probeUv = project(probe);
                    if (probeUv.z <= 0.05) {
                        break;
                    }
                    float probeDepth = max(textureLod(DepthSampler, probeUv.xy, 0.0).r, 0.000001);
                    if (length(probe) > length(reconstruct(probeUv.xy, probeDepth))) {
                        high = middle;
                        hitUv = probeUv.xy;
                    } else {
                        low = middle;
                    }
                }

                // Rough water blurs what it reflects, and so does distance: a
                // reflection thirty blocks down the ray is carrying far less
                // detail than one at your feet whether the surface is rough or not.
                float roughness = clamp(
                        (1.0 - StyleParams.y) + high * StyleParams.w * 0.55,
                        0.0,
                        1.0
                );
                reflColor = mix(
                        textureLod(SceneSampler, hitUv, 0.0).rgb,
                        textureLod(BlurredSampler, hitUv, 0.0).rgb,
                        roughness
                );
                // Screen-space reflection can only show what is on screen, so it
                // has to admit it near the border instead of hard-cutting there.
                vec2 border = smoothstep(vec2(0.0), vec2(0.13), hitUv)
                        * (1.0 - smoothstep(vec2(0.87), vec2(1.0), hitUv));
                confidence = border.x * border.y;
                break;
            }

            previous = travelled;
            stride *= growth;
            travelled += stride;
        }
    }

    // Schlick against water's F0. This is the whole difference between "wet" and
    // "chrome": looking straight down at a puddle you mostly see the bottom,
    // looking along it you see the sky.
    float cosTheta = clamp(dot(-viewDir, normal), 0.0, 1.0);
    float fresnel = 0.02 + 0.98 * pow(1.0 - cosTheta, 5.0);
    fresnel = mix(0.45, fresnel, StyleParams.x);
    float reflectivity = clamp(fresnel * StyleParams.z, 0.0, 1.0) * confidence * reflectFade;

    // Water filling the micro-relief of a surface is what darkens it, and this one
    // multiply sells "wet" harder than the reflection on top of it does. It has to
    // fade with the same range curve as the reflection, though - far ground that
    // no longer gets a reflection put back on top would otherwise crush to a black
    // strip at the horizon on any dark scene (worst at night, when the fallback
    // sky is nearly black too).
    vec3 wetScene = scene * (1.0 - SurfaceParams.z * wet * reflectFade);
    vec3 result = mix(wetScene, reflColor, reflectivity * wet);
    finalColor = vec4(result + ditherRgb(gl_FragCoord.xy), 1.0);
}
