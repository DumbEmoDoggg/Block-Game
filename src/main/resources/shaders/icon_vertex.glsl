#version 330 core

// 2-D position in NDC (Normalized Device Coordinates, range [-1,+1])
layout (location = 0) in vec2 aPosition;
// UV coordinates into the icon atlas texture
layout (location = 1) in vec2 aUV;

out vec2 vUV;

void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    vUV = aUV;
}
