#version 330 core

in vec2 vUV;

uniform sampler2D uIcons;
uniform vec4 uColor;  // color/alpha multiplier; default (1,1,1,1) = no tint

out vec4 fragColor;

void main() {
    fragColor = texture(uIcons, vUV) * uColor;
}
