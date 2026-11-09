package com.xcloud.godmode.streaming

/**
 * Phase 74: Alternative Spatial Upscaling Shaders (Lanczos3 & Standalone CAS).
 * Provides specialized GLSL shader kernels for edge reconstruction:
 * - Lanczos3: 36-sample windowed sinc filter for smooth anti-aliased interpolation.
 * - CAS Standalone: 5-tap Contrast Adaptive Sharpening with dynamic contrast clamping.
 */
object LanczosAndCasShaderPipeline {

    /**
     * GLSL Fragment Shader for Lanczos-3 (Windowed sinc filter).
     */
    val LANCZOS3_FRAGMENT_SHADER = """
        #version 300 es
        precision highp float;
        
        in vec2 vTexCoord;
        out vec4 fragColor;
        
        uniform sampler2D uTexture;
        uniform vec2 uSourceResolution;
        
        const float PI = 3.14159265359;
        
        float sinc(float x) {
            if (abs(x) < 0.0001) return 1.0;
            x *= PI;
            return sin(x) / x;
        }
        
        float lanczosWeight(float x) {
            if (abs(x) >= 3.0) return 0.0;
            return sinc(x) * sinc(x / 3.0);
        }
        
        void main() {
            vec2 texelSize = 1.0 / uSourceResolution;
            vec2 pos = vTexCoord * uSourceResolution - 0.5;
            vec2 f = fract(pos);
            vec2 base = floor(pos) + 0.5;
            
            vec4 colorSum = vec4(0.0);
            float weightSum = 0.0;
            
            for (int y = -2; y <= 3; y++) {
                float wy = lanczosWeight(float(y) - f.y);
                for (int x = -2; x <= 3; x++) {
                    float wx = lanczosWeight(float(x) - f.x);
                    float w = wx * wy;
                    vec2 uv = (base + vec2(float(x), float(y))) * texelSize;
                    colorSum += texture(uTexture, uv) * w;
                    weightSum += w;
                }
            }
            
            fragColor = clamp(colorSum / weightSum, 0.0, 1.0);
        }
    """.trimIndent()

    /**
     * GLSL Fragment Shader for Standalone CAS (Contrast Adaptive Sharpening).
     */
    val STANDALONE_CAS_FRAGMENT_SHADER = """
        #version 300 es
        precision mediump float;
        
        in vec2 vTexCoord;
        out vec4 fragColor;
        
        uniform sampler2D uTexture;
        uniform vec2 uTexelSize;
        uniform float uSharpness; // 0.0 (None) to 1.0 (Maximum)
        
        void main() {
            // 5-tap cross neighborhood
            vec3 a = texture(uTexture, vTexCoord + vec2(0.0, -uTexelSize.y)).rgb;
            vec3 b = texture(uTexture, vTexCoord + vec2(-uTexelSize.x, 0.0)).rgb;
            vec3 e = texture(uTexture, vTexCoord).rgb;
            vec3 d = texture(uTexture, vTexCoord + vec2(uTexelSize.x, 0.0)).rgb;
            vec3 c = texture(uTexture, vTexCoord + vec2(0.0, uTexelSize.y)).rgb;
            
            // Contrast bounds
            vec3 minColor = min(min(a, b), min(c, min(d, e)));
            vec3 maxColor = max(max(a, b), max(c, max(d, e)));
            
            // Adaptive weight based on local contrast
            vec3 amp = clamp(min(minColor, 1.0 - maxColor) / maxColor, 0.0, 1.0);
            vec3 w = -sqrt(amp) * (uSharpness * 0.25);
            
            // Filter convolution
            vec3 outColor = (e + (a + b + c + d) * w) / (1.0 + 4.0 * w);
            fragColor = vec4(clamp(outColor, minColor, maxColor), 1.0);
        }
    """.trimIndent()
}
