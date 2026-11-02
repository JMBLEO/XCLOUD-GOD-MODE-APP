#version 310 es
precision highp float;

uniform sampler2D uVideoTexture;
uniform float uTime;
in vec2 vTexCoord;
out vec4 fragColor;

float rand(vec2 co) {
    return fract(sin(dot(co.xy + uTime, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    vec4 color = texture(uVideoTexture, vTexCoord);
    float noise = (rand(vTexCoord) - 0.5) / 255.0;
    vec3 ditheredColor = color.rgb + vec3(noise);
    fragColor = vec4(ditheredColor, color.a);
}
