// Shared by both halves of the RTX light-shaft effect: the half-res occlusion
// pass that decides which pixels are allowed to seed a shaft, and the full-res
// composite that smears them toward the light.

layout(std140) uniform RtxRayUniforms {
    vec4 LightScreen;  // xy = light position in uv, z = aspect ratio, w = on-screen fade
    vec4 RayColor;     // rgb = tint, a = how far the shaft is pulled toward the tint
    vec4 RayParams;    // x = sample count, y = march density, z = per-sample decay, w = angular falloff
    vec4 RayExtra;     // x = emissive threshold, y = emissive weight, z = strength, w = time
};
