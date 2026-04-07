#version 330 core

in vec3 vColor;

out vec4 fragColor;

void main() {
    // For the crosshair aColor is vec3(0,0,0) because no attrib is bound for it;
    // we output a semi-transparent white so the crosshair is always visible.
    // For hotbar quads the real block color is passed in via vColor.
    vec3 c = (length(vColor) < 0.001) ? vec3(1.0) : vColor;
    fragColor = vec4(c, 0.85);
}
