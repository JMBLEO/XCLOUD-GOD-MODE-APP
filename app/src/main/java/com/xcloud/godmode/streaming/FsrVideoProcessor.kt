package com.xcloud.godmode.streaming

import android.opengl.GLES20
import android.util.Log

enum class FsrMode {
    OFF,
    EASU_RCAS_DUAL_PASS,
    OPTIMIZED_SINGLE_PASS
}

/**
 * FsrVideoProcessor: Manages OpenGL ES shaders for 2-stage FidelityFX Super Resolution (FSR 1.0)
 * spatial reconstruction and contrast adaptive sharpening.
 */
class FsrVideoProcessor {
    companion object {
        private const val TAG = "FsrVideoProcessor"

        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        const val EASU_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            uniform vec2 uSourceResolution;
            uniform vec2 uTargetResolution;

            void main() {
                // Directional edge reconstruction kernel
                vec2 texel = 1.0 / uSourceResolution;
                vec4 c = texture2D(uTexture, vTexCoord);
                vec4 n = texture2D(uTexture, vTexCoord + vec2(0.0, -texel.y));
                vec4 s = texture2D(uTexture, vTexCoord + vec2(0.0, texel.y));
                vec4 e = texture2D(uTexture, vTexCoord + vec2(texel.x, 0.0));
                vec4 w = texture2D(uTexture, vTexCoord + vec2(-texel.x, 0.0));

                vec4 minColor = min(c, min(min(n, s), min(e, w)));
                vec4 maxColor = max(c, max(max(n, s), max(e, w)));

                vec4 filtered = (n + s + e + w + (c * 2.0)) / 6.0;
                gl_FragColor = clamp(filtered, minColor, maxColor);
            }
        """

        const val RCAS_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            uniform vec2 uTargetResolution;
            uniform float uSharpness; // 0.0 (sharpest) to 1.0 (smooth)

            void main() {
                vec2 texel = 1.0 / uTargetResolution;
                vec4 c = texture2D(uTexture, vTexCoord);
                vec4 n = texture2D(uTexture, vTexCoord + vec2(0.0, -texel.y));
                vec4 s = texture2D(uTexture, vTexCoord + vec2(0.0, texel.y));
                vec4 e = texture2D(uTexture, vTexCoord + vec2(texel.x, 0.0));
                vec4 w = texture2D(uTexture, vTexCoord + vec2(-texel.x, 0.0));

                // Contrast Adaptive Sharpening weight
                float weight = -0.18 * (1.0 - uSharpness);
                vec4 sharpened = (c + (n + s + e + w) * weight) / (1.0 + 4.0 * weight);
                gl_FragColor = vec4(sharpened.rgb, 1.0);
            }
        """
    }

    private var easuProgram = 0
    private var rcasProgram = 0
    private var isInitialized = false

    var currentMode: FsrMode = FsrMode.OFF
    var sharpness: Float = 0.85f // Recommended default

    fun initialize() {
        if (isInitialized) return
        try {
            easuProgram = createProgram(VERTEX_SHADER, EASU_FRAGMENT_SHADER)
            rcasProgram = createProgram(VERTEX_SHADER, RCAS_FRAGMENT_SHADER)
            isInitialized = true
            Log.i(TAG, "FSR 1.0 GLSL Shaders compiled and linked successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compile FSR GLSL shaders", e)
            isInitialized = false
        }
    }

    fun release() {
        if (easuProgram != 0) {
            GLES20.glDeleteProgram(easuProgram)
            easuProgram = 0
        }
        if (rcasProgram != 0) {
            GLES20.glDeleteProgram(rcasProgram)
            rcasProgram = 0
        }
        isInitialized = false
    }

    private fun createProgram(vert: String, frag: String): Int {
        val vertShader = loadShader(GLES20.GL_VERTEX_SHADER, vert)
        val fragShader = loadShader(GLES20.GL_FRAGMENT_SHADER, frag)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertShader)
        GLES20.glAttachShader(program, fragShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val error = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Could not link GL program: $error")
        }
        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val error = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Could not compile shader $type: $error")
        }
        return shader
    }
}
