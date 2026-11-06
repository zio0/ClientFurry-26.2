#version 330 core

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;

layout(std140) uniform Projection {
    mat4 ProjMat;
};

out vec2 stripUv;
out vec4 stripColor;

void main() {
    // Position arrives already in view space - the ribbon is billboarded on the
    // CPU so its width stays in world units while always facing the camera.
    gl_Position = ProjMat * vec4(Position, 1.0);
    stripUv = UV0;
    stripColor = Color;
}
