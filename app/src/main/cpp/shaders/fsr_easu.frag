#version 310 es
precision highp float;

uniform sampler2D uSourceTexture;
in vec2 vTexCoord;
out vec4 outColor;

void main() {
    vec2 texSize = vec2(textureSize(uSourceTexture, 0));
    vec2 invSize = 1.0 / texSize;
    vec2 pos = vTexCoord * texSize - 0.5;
    vec2 f = fract(pos);

    vec4 c0 = texture(uSourceTexture, vTexCoord + vec2(-invSize.x, 0.0));
    vec4 c1 = texture(uSourceTexture, vTexCoord + vec2(invSize.x, 0.0));
    vec4 c2 = texture(uSourceTexture, vTexCoord + vec2(0.0, -invSize.y));
    vec4 c3 = texture(uSourceTexture, vTexCoord + vec2(0.0, invSize.y));
    vec4 center = texture(uSourceTexture, vTexCoord);

    float gradH = length((c1.rgb - center.rgb) + (center.rgb - c0.rgb));
    float gradV = length((c3.rgb - center.rgb) + (center.rgb - c2.rgb));

    vec2 weight = vec2(1.0 / (gradH + 0.001), 1.0 / (gradV + 0.001));
    vec3 resolved = (c0.rgb * weight.x + c1.rgb * weight.x + c2.rgb * weight.y + c3.rgb * weight.y) 
                    / (2.0 * (weight.x + weight.y));

    outColor = vec4(mix(center.rgb, resolved, 0.45), 1.0);
}
