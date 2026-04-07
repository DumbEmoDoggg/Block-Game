#version 330 core

in vec3 vColor;
in vec3 vNormal;
in float vSkyLight;

out vec4 fragColor;

uniform vec3  lightDir;         // normalised world-space direction toward the sun (points away from blocks)
uniform float ambientStrength;  // [0..1] minimum sky-light level

const float CAVE_LIGHT = 0.08; // brightness of completely underground/cave faces

void main() {
    // Face brightness is already baked into vColor by the CPU mesh builder.
    // We still apply a subtle directional term so the scene reacts to the sun.
    float diffuse  = max(dot(normalize(vNormal), normalize(-lightDir)), 0.0);
    float skyLight = ambientStrength + (1.0 - ambientStrength) * diffuse;

    // vSkyLight = 1.0 for sky-exposed blocks, 0.0 for underground/cave blocks.
    // Mix between a very dim cave ambient and full sky lighting accordingly.
    float light     = mix(CAVE_LIGHT, skyLight, vSkyLight);

    fragColor = vec4(vColor * light, 1.0);
}
