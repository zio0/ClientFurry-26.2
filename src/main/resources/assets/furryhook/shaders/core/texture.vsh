#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <furryhook:ui_common.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in float LineWidth;

out vec2 texCoord;
out vec4 tintColor;
out vec2 localPos;
flat out vec2 halfSize;
flat out float cornerRadius;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position.xy, 0.0, 1.0);
    texCoord = UV0;
    tintColor = Color;
    // UV1 = centered local coords (1/8 px fixed-point); every vertex of the
    // quad carries the same magnitude, so its abs() doubles as the flat
    // half-size without a dedicated size attribute.
    // UV2.x = corner radius (1/16 px fixed-point). Deliberately not carried
    // via LineWidth: that attribute isn't reliably wired for QUADS topology
    // on every backend.
    localPos = vec2(UV1) / UI_SIZE_SCALE;
    halfSize = abs(vec2(UV1)) / UI_SIZE_SCALE;
    cornerRadius = float(UV2.x) / UI_RADIUS_SCALE;
}
