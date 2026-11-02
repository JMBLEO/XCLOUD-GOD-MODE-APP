#version 310 es
precision highp float;
uniform samplerExternalOES currentFrame;
uniform samplerExternalOES previousFrame;
uniform float blendFactor; // 0.5 for intermediate 120Hz frame

in vec2 vTexCoord;
out vec4 outColor;

void main() {
    vec4 colorCurrent = texture(currentFrame, vTexCoord);
    vec4 colorPrevious = texture(previousFrame, vTexCoord);
    outColor = mix(colorPrevious, colorCurrent, blendFactor);
}
