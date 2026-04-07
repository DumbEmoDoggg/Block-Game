#version 330 core

in vec2 vUV;

uniform sampler2D uIcons;

out vec4 fragColor;

void main() {
    fragColor = texture(uIcons, vUV);
}
