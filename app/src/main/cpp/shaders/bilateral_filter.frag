#version 310 es
precision highp float;

uniform sampler2D uFrameTexture;
in vec2 vTexCoord;
out vec4 fragColor;

const float spatialSigma = 1.5;
const float rangeSigma = 0.08;

void main() {
    vec2 texelSize = 1.0 / vec2(textureSize(uFrameTexture, 0));
    vec4 centerCol = texture(uFrameTexture, vTexCoord);
    
    vec4 sumCol = vec4(0.0);
    float sumWeight = 0.0;

    vec2 offsets[5] = vec2[](
        vec2(0.0, 0.0),
        vec2(-1.0, 0.0), vec2(1.0, 0.0),
        vec2(0.0, -1.0), vec2(0.0, 1.0)
    );

    for (int i = 0; i < 5; ++i) {
        vec4 sampleCol = texture(uFrameTexture, vTexCoord + offsets[i] * texelSize);
        float distSpatial = length(offsets[i]);
        float distRange = length(sampleCol.rgb - centerCol.rgb);

        float weight = exp(-(distSpatial * distSpatial) / (2.0 * spatialSigma * spatialSigma)
                           -(distRange * distRange) / (2.0 * rangeSigma * rangeSigma));

        sumCol += sampleCol * weight;
        sumWeight += weight;
    }

    fragColor = sumCol / sumWeight;
}
