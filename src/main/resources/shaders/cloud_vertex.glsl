#version 330 core

layout (location = 0) in vec3 aPosition;

out float vFogDist;

uniform mat4 model;
uniform mat4 view;
uniform mat4 projection;

void main() {
    vec4 eyePos = view * model * vec4(aPosition, 1.0);
    gl_Position = projection * eyePos;
    vFogDist = length(eyePos.xyz);
}
