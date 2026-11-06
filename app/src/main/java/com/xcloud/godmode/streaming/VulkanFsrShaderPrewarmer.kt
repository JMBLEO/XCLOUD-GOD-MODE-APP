package com.xcloud.godmode.streaming

import android.content.Context
import android.util.Log

/**
 * VulkanFsrShaderPrewarmer: Pre-compiles AMD FSR 1.0 SPIR-V shaders to prevent
 * frame drops (stutter) during the first few seconds of streaming.
 */
object VulkanFsrShaderPrewarmer {
    private const val TAG = "FsrPrewarmer"

    init {
        System.loadLibrary("godmode_native")
    }

    /**
     * Triggers native shader pre-warming. Should be called when "Save" is pressed
     * in settings or during app startup.
     */
    fun prewarm(context: Context) {
        Log.d(TAG, "Initiating Vulkan FSR Shader Pre-warming...")
        try {
            val success = nativePrewarmFsrShaders()
            if (success) {
                Log.i(TAG, "Vulkan FSR Shaders pre-compiled successfully.")
            } else {
                Log.w(TAG, "Vulkan FSR Shader pre-warming failed or not supported by driver.")
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native shader pre-warmer not linked: ${e.message}")
        }
    }

    private external fun nativePrewarmFsrShaders(): Boolean
}
