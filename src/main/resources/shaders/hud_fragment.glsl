#version 330 core

in vec3 vColor;

out vec4 fragColor;

void main() {
    // Used exclusively for the selected-slot highlight overlay; output at low opacity.
    vec3 c = vColor;
    fragColor = vec4(c, 0.40);
}
