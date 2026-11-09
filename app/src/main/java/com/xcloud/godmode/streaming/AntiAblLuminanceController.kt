package com.xcloud.godmode.streaming

/**
 * Phase 93: Anti-ABL (Auto Brightness Limiter) OLED Luminance Controller.
 * Injects a GPU compression curve on scenes with high Average Picture Level (APL > 0.70)
 * to prevent aggressive thermal auto-dimming hardware triggers on mobile AMOLED displays.
 */
class AntiAblLuminanceController {

    companion object {
        val ANTI_ABL_FRAGMENT_SHADER = """
            #version 300 es
            precision mediump float;
            
            in vec2 vTexCoord;
            out vec4 fragColor;
            
            uniform sampler2D uTexture;
            uniform float uMaxLuminanceCeiling; // 0.85 default
            
            // Rec.709 luma coefficients
            const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
            
            void main() {
                vec4 color = texture(uTexture, vTexCoord);
                float luma = dot(color.rgb, LUMA);
                
                if (luma > uMaxLuminanceCeiling) {
                    // Soft shoulder compression: compress top highlights smoothly
                    float excess = luma - uMaxLuminanceCeiling;
                    float compressedLuma = uMaxLuminanceCeiling + (excess / (1.0 + excess * 2.0));
                    color.rgb *= (compressedLuma / max(luma, 0.001));
                }
                
                fragColor = color;
            }
        """.trimIndent()
    }

    data class AblSettings(
        val isAntiAblEnabled: Boolean = true,
        val luminanceCeiling: Float = 0.85f,
        val isOledScreen: Boolean = true
    )

    fun calculateCeiling(isOled: Boolean, isHdrActive: Boolean): Float {
        return when {
            isOled && isHdrActive -> 0.80f // More aggressive clamp for HDR on OLED
            isOled -> 0.88f
            else -> 1.0f // No clamp needed on LCD
        }
    }
}
