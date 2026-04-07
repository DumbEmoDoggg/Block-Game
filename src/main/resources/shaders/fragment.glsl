#version 330 core

in vec3 vColor;
in vec3 vNormal;

out vec4 fragColor;

uniform vec3  lightDir;         // normalised world-space direction toward the sun (points away from blocks)
uniform float ambientStrength;  // [0..1] minimum light level

void main() {
    // Face brightness is already baked into vColor by the CPU mesh builder.
    // We still apply a subtle directional term so the scene reacts to the sun.
    float diffuse = max(dot(normalize(vNormal), normalize(-lightDir)), 0.0);
    float light   = ambientStrength + (1.0 - ambientStrength) * diffuse;
    fragColor = vec4(vColor * light, 1.0);
}
