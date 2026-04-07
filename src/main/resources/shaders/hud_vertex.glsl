#version 330 core

// 2-D position in NDC (Normalized Device Coordinates, range [-1,+1])
layout (location = 0) in vec2 aPosition;
// Optional per-vertex color (used by hotbar quads; crosshair ignores it)
layout (location = 1) in vec3 aColor;

out vec3 vColor;

void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    vColor = aColor;
}
