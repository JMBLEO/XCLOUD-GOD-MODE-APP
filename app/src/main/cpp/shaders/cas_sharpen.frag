#version 310 es
precision highp float;

uniform sampler2D uTexture;
uniform float uSharpness;
in vec2 vTexCoord;
out vec4 fragColor;

void main() {
    vec2 texSize = vec2(textureSize(uTexture, 0));
    vec2 off = 1.0 / texSize;

    vec3 a = texture(uTexture, vTexCoord + vec2(-off.x, -off.y)).rgb;
    vec3 b = texture(uTexture, vTexCoord + vec2(0.0, -off.y)).rgb;
    vec3 c = texture(uTexture, vTexCoord + vec2(off.x, -off.y)).rgb;
    vec3 d = texture(uTexture, vTexCoord + vec2(-off.x, 0.0)).rgb;
    vec3 e = texture(uTexture, vTexCoord).rgb;
    vec3 f = texture(uTexture, vTexCoord + vec2(off.x, 0.0)).rgb;
    vec3 g = texture(uTexture, vTexCoord + vec2(-off.x, off.y)).rgb;
    vec3 h = texture(uTexture, vTexCoord + vec2(0.0, off.y)).rgb;
    vec3 i = texture(uTexture, vTexCoord + vec2(off.x, off.y)).rgb;

    vec3 mn = min(min(min(d, e), min(f, b)), min(min(a, c), min(g, min(h, i))));
    vec3 mx = max(max(max(d, e), max(f, b)), max(max(a, c), max(g, max(h, i))));
    
    vec3 amp = clamp(min(mn, 2.0 - mx) / max(mx, 0.001), 0.0, 1.0);
    amp = sqrt(amp) * uSharpness;

    vec3 weights = 1.0 / (4.0 + amp);
    vec3 result = ((b + d + f + h) * amp + e) * weights;

    fragColor = vec4(result, 1.0);
}
