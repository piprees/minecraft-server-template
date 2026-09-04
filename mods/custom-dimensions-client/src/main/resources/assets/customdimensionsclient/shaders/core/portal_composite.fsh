#version 150

uniform sampler2D Sampler0;
uniform vec2 ScreenSize;

out vec4 fragColor;

// The second pass used this pass's projection, so the pixel under the quad is
// already in the right place: the read is the fragment's own screen position.
void main() {
    fragColor = vec4(texture(Sampler0, gl_FragCoord.xy / ScreenSize).rgb, 1.0);
}
