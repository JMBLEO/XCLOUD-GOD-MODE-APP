#version 310 es
precision highp float;

in vec2 v_texCoord;
out vec4 fragColor;

uniform sampler2D u_hdrTexture;

// ACES tone mapping curve fitted by Krzysztof Narkowicz
vec3 acesFilm(vec3 x) {
    float a = 2.51f;
    float b = 0.03f;
    float c = 2.43f;
    float d = 0.59f;
    float e = 0.14f;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0f, 1.0f);
}

void main() {
    vec4 hdrColor = texture(u_hdrTexture, v_texCoord);
    
    // Apply ACES tone mapping
    vec3 mapped = acesFilm(hdrColor.rgb);
    
    // Gamma correction (gamma 2.2)
    mapped = pow(mapped, vec3(1.0f / 2.2f));
    
    fragColor = vec4(mapped, hdrColor.a);
}
