#version 330 core

in vec2 uv;
out vec4 finalColor;

uniform sampler2D OcclusionSampler;

#moj_import <furryhook:block_outline_common.glsl>
#moj_import <furryhook:rtx_ray_common.glsl>

// Loop bounds have to be compile-time in enough drivers that it is not worth
// finding out which; the real count comes from the uniform and breaks out early.
const int MAX_SAMPLES = 64;

// Radial accumulation toward the light. Every sample walks one step closer to the
// light in screen space and contributes what the occlusion buffer holds there,
// weighted by an exponential decay - so a pixel with a clear line to the light
// gathers the full chain and a pixel behind a wall gathers black.
//
// This pass is additive: the pipeline blends it onto the scene rather than
// compositing a copy, which is why there is no SceneSampler here.
void main() {
    int samples = int(RayParams.x);
    if (samples < 1) {
        finalColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    vec2 delta = (uv - LightScreen.xy) * (RayParams.y / float(samples));
    // Starting the walk at a random fraction of a step is what turns the
    // concentric rings of a fixed-stride march into noise the eye reads as grain.
    vec2 coord = uv - delta * hash21(gl_FragCoord.xy);

    float illumination = 1.0;
    vec3 accumulated = vec3(0.0);
    for (int i = 0; i < MAX_SAMPLES; i++) {
        if (i >= samples) {
            break;
        }
        coord -= delta;
        // Walking toward an off-screen light runs the chain out of the buffer;
        // clamp-to-edge would smear the border pixel down the whole shaft, so
        // those samples contribute nothing instead.
        vec2 inside = step(vec2(0.0), coord) * step(coord, vec2(1.0));
        accumulated += textureLod(OcclusionSampler, coord, 0.0).rgb
                * (illumination * inside.x * inside.y);
        illumination *= RayParams.z;
    }

    accumulated *= RayExtra.z * LightScreen.w / float(samples);
    finalColor = vec4(max(accumulated, vec3(0.0)) + ditherRgb(gl_FragCoord.xy), 1.0);
}
