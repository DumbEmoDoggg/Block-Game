#version 330 core

in vec2 vTexCoord;
in vec3 vNormal;
in float vSkyLight;
in float vFogDist;

out vec4 fragColor;

// water_still.png: 16x512 sprite sheet, 32 frames of 16x16 stacked vertically.
uniform sampler2D uWaterTexture;
uniform float     uTime;

uniform vec3  lightDir;
uniform float ambientStrength;
uniform vec3  uFogColor;
uniform float uFogStart;
uniform float uFogEnd;

// Minecraft default-biome water tint (#3F76E4)
const vec3  WATER_TINT  = vec3(0.247, 0.463, 0.894);
const float WATER_ALPHA = 0.78;

// Animation: 32 frames cycling at 8 frames per second
const float FRAME_COUNT = 32.0;
const float FPS         = 8.0;

const float CAVE_LIGHT = 0.08;

void main() {
    // Select the current animation frame and offset V into the sprite sheet
    float frame     = floor(mod(uTime * FPS, FRAME_COUNT));
    float vAnimated = vTexCoord.y + frame / FRAME_COUNT;

    vec4 texColor = texture(uWaterTexture, vec2(vTexCoord.x, vAnimated));

    // Apply Minecraft-style water tint
    vec3 tinted = texColor.rgb * WATER_TINT;

    // Directional lighting
    float diffuse  = max(dot(normalize(vNormal), normalize(-lightDir)), 0.0);
    float skyLight = ambientStrength + (1.0 - ambientStrength) * diffuse;
    float light    = mix(CAVE_LIGHT, skyLight, vSkyLight);

    vec3 lit = tinted * light;

    // Distance fog
    float fogFactor = clamp((vFogDist - uFogStart) / (uFogEnd - uFogStart), 0.0, 1.0);
    vec3  finalRgb  = mix(lit, uFogColor, fogFactor);

    // Fade out alpha at fog distance so water blends smoothly with the sky
    fragColor = vec4(finalRgb, WATER_ALPHA * (1.0 - fogFactor));
}
