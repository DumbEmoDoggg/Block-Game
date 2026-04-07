#version 330 core

in vec2 vTexCoord;
in vec3 vNormal;
in float vSkyLight;

out vec4 fragColor;

uniform sampler2D uTexture;
uniform vec3  lightDir;         // normalised world-space direction toward the sun (points away from blocks)
uniform float ambientStrength;  // [0..1] minimum sky-light level

// Per-face brightness multipliers baked into the lighting calculation
// (top = 1.0, north/south = 0.8, east/west = 0.65, bottom = 0.5)
const float CAVE_LIGHT = 0.08; // brightness of completely underground/cave faces

void main() {
    vec4 texColor = texture(uTexture, vTexCoord);

    // Directional lighting from the sun
    float diffuse  = max(dot(normalize(vNormal), normalize(-lightDir)), 0.0);
    float skyLight = ambientStrength + (1.0 - ambientStrength) * diffuse;

    // vSkyLight = 1.0 for sky-exposed blocks, 0.0 for underground/cave blocks.
    // Mix between a very dim cave ambient and full sky lighting accordingly.
    float light = mix(CAVE_LIGHT, skyLight, vSkyLight);

    fragColor = vec4(texColor.rgb * light, texColor.a);
}
