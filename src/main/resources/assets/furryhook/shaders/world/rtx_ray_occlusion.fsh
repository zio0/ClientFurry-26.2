#version 330 core

in vec2 uv;
out vec4 finalColor;

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;

#moj_import <furryhook:block_outline_common.glsl>
#moj_import <furryhook:rtx_ray_common.glsl>

// Half-res occlusion buffer. Everything the light can reach the camera through
// keeps its color; everything solid is punched to black. The composite pass then
// only has to smear this toward the light and the gaps between the geometry come
// out as shafts on their own - no per-sample shadow test, which is the reason
// this whole effect costs one blur instead of a shadow map.
void main() {
    float depth = texture(DepthSampler, uv).r;
    vec3 scene = texture(SceneSampler, uv).rgb;

    // Only open sky seeds a full shaft. Bright emissive geometry - lava, a
    // glowstone wall, a beacon - gets a fraction of one, so those throw light
    // through the same gaps instead of reading as flat textures.
    float mask = depth <= 0.000001 ? 1.0 : 0.0;
    float luminance = dot(scene, vec3(0.2126, 0.7152, 0.0722));
    mask = max(mask, smoothstep(RayExtra.x, 1.0, luminance) * RayExtra.y);

    // The radial decay in the composite shapes the shafts, but on its own it lets
    // the entire sky throw them and the frame just washes out. Weighting the seed
    // by angular distance to the light is what keeps the effect pointed at the sun
    // instead of at everything.
    vec2 toLight = (uv - LightScreen.xy) * vec2(LightScreen.z, 1.0);
    float spread = exp(-dot(toLight, toLight) * RayParams.w);

    vec3 source = mix(scene, RayColor.rgb, RayColor.a);
    finalColor = vec4(source * (mask * spread), 1.0);
}
