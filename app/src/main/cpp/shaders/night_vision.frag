#version 310 es
precision highp float;

uniform sampler2D uVideoTexture;
in vec2 vTexCoord;
out vec4 fragColor;

void main() {
    vec4 col = texture(uVideoTexture, vTexCoord);
    
    // Perceptual luminance
    float luma = dot(col.rgb, vec3(0.299, 0.587, 0.114));
    
    // Logarithmic amplification (Night Vision effect)
    float boostedLuma = log(1.0 + luma * 9.0) / log(10.0);
    
    // Tactical green tint
    vec3 nightVisionTint = vec3(0.1, 0.9, 0.3) * boostedLuma;
    
    // Subtle digital noise
    float noise = fract(sin(dot(vTexCoord, vec2(12.9898, 78.233))) * 43758.5453) * 0.05;
    
    fragColor = vec4(nightVisionTint + noise, 1.0);
}
