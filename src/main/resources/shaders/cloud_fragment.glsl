#version 330 core

in float vFogDist;

out vec4 fragColor;

uniform vec3  uFogColor;
uniform float uFogStart;
uniform float uFogEnd;

void main() {
    float fogFactor = clamp((vFogDist - uFogStart) / (uFogEnd - uFogStart), 0.0, 1.0);
    vec3  cloudColor = vec3(1.0, 1.0, 1.0);
    vec3  finalRgb   = mix(cloudColor, uFogColor, fogFactor);
    float alpha      = mix(0.88, 0.0, fogFactor);
    fragColor = vec4(finalRgb, alpha);
}
