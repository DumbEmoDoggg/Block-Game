#version 330 core

out vec4 fragColor;

uniform float uTime;

void main() {
    // Flash alpha between 0.15 and 0.45 at ~1 Hz
    float alpha = 0.30 + 0.15 * sin(uTime * 6.0);
    fragColor = vec4(1.0, 1.0, 1.0, alpha);
}
