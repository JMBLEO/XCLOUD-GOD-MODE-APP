package com.xcloud.godmode.bridge

import android.util.Log
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HardwareCryptoEngine: Hardware-accelerated UDP payload decryption.
 * Coordinates with ARMv8-A Cryptographic Extensions (AESE / AESD SIMD NEON) in NDK
 * with seamless zero-copy ByteBuffer fallback.
 */
object HardwareCryptoEngine {
    private const val TAG = "HardwareCrypto"
    private var isNativeNeonLoaded = false

    init {
        try {
            System.loadLibrary("godmode_core")
            isNativeNeonLoaded = true
        } catch (_: Throwable) {
            isNativeNeonLoaded = false
        }
    }

    /**
     * Decrypts an incoming UDP video/audio Access Unit payload using AES-128-GCM or AES-CTR.
     */
    fun decryptMicroPacket(
        cipherBuffer: ByteBuffer,
        plainBuffer: ByteBuffer,
        key: ByteArray,
        iv: ByteArray,
        tagLengthBits: Int = 128
    ): Boolean {
        if (isNativeNeonLoaded) {
            try {
                val success = nativeDecryptNeon(cipherBuffer, plainBuffer, key, iv)
                if (success) return true
            } catch (e: Throwable) {
                Log.d(TAG, "Native NEON decryption fallback to Java crypto", e)
            }
        }

        return try {
            val secretKey = SecretKeySpec(key, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(tagLengthBits, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            cipher.doFinal(cipherBuffer, plainBuffer)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Micro-packet decryption failed", e)
            false
        }
    }

    private external fun nativeDecryptNeon(
        cipherBuffer: ByteBuffer,
        plainBuffer: ByteBuffer,
        key: ByteArray,
        iv: ByteArray
    ): Boolean
}
