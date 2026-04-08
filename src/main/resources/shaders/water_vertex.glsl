#version 330 core

layout (location = 0) in vec3 aPosition;
layout (location = 1) in vec2 aTexCoord;
layout (location = 2) in vec3 aNormal;
layout (location = 3) in float aSkyLight;

out vec2 vTexCoord;
out vec3 vNormal;
out float vSkyLight;
out float vFogDist;

uniform mat4 model;
uniform mat4 view;
uniform mat4 projection;

void main() {
    vec4 eyePos = view * model * vec4(aPosition, 1.0);
    gl_Position = projection * eyePos;
    vTexCoord = aTexCoord;
    vNormal   = aNormal;
    vSkyLight = aSkyLight;
    vFogDist  = length(eyePos.xyz);
}
