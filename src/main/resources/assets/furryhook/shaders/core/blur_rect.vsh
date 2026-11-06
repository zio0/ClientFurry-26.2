#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <furryhook:ui_common.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

out vec2 localPos;
out vec2 screenUv;
out vec4 tintColor;
flat out vec2 halfSize;
flat out float cornerRadius;
flat out float overallAlpha;
flat out vec2 lightDir;
flat out float bevelWidth;
flat out float specularStrength;
flat out float dispersion;
flat out float frost;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position.xy, 0.0, 1.0);
    // Backdrop UV straight from clip space: works identically on OpenGL and
    // Vulkan backends (no gl_FragCoord origin assumptions).
    screenUv = gl_Position.xy / gl_Position.w * 0.5 + 0.5;
    localPos = UV0;
    tintColor = Color;
    halfSize = vec2(UV1) / UI_SIZE_SCALE * 0.5;
    cornerRadius = float(UV2.x) / UI_RADIUS_SCALE;
    overallAlpha = float(UV2.y & 255) / 255.0;

    // Liquid glass parameters (mirrors win.furryhook.utils.render.gui.LiquidGlass):
    //   UV2.y high byte -> frost, Position.z -> bevel px | specular,
    //   Normal.xy -> light direction, Normal.z -> chromatic dispersion.
    // All of them read zero on the plain glass path, which restores the old
    // single-tap behaviour exactly.
    frost = float((UV2.y >> 8) & 255) / 255.0;
    vec2 liquid = ui_unpackDual12Raw(Position.z);
    bevelWidth = liquid.x / UI_RADIUS_SCALE;
    specularStrength = liquid.y / 1000.0;
    lightDir = Normal.xy;
    dispersion = clamp(Normal.z, 0.0, 1.0);
}
