package com.xcloud.godmode.services

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Phase 67: Biometric Vault Guard.
 * Uses AndroidX BiometricPrompt (Class 3 Strong Biometrics) + Hardware KeyStore AES-256-GCM
 * to authenticate identity before launching remote consoles (xHome) or exporting sensitive credentials.
 */
class BiometricVaultGuard(private val context: Context) {

    companion object {
        private const val KEY_ALIAS = "GodModeBiometricKey"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }

    sealed class AuthResult {
        object Success : AuthResult()
        data class Error(val code: Int, val message: String) : AuthResult()
        object Cancelled : AuthResult()
        object NotAvailable : AuthResult()
    }

    /**
     * Checks if biometric authentication can be performed on the current device.
     */
    fun canAuthenticate(): Boolean {
        val biometricManager = BiometricManager.from(context)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return biometricManager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Generates or retrieves the hardware AES key in Android KeyStore.
     */
    fun getOrCreateHardwareKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry != null) return entry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val keyGenSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false) // Can be set true when crypto object gating is needed
            .build()

        keyGenerator.init(keyGenSpec)
        return keyGenerator.generateKey()
    }

    /**
     * Prompts the user with the system BiometricPrompt dialog.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String = "Autenticación de Bóveda",
        subtitle: String = "Confirma tu identidad para acceder a la consola",
        onResult: (AuthResult) -> Unit
    ) {
        if (!canAuthenticate()) {
            onResult(AuthResult.NotAvailable)
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onResult(AuthResult.Success)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    onResult(AuthResult.Cancelled)
                } else {
                    onResult(AuthResult.Error(errorCode, errString.toString()))
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                // Transient biometric mismatch; prompt allows retry automatically
            }
        })

        prompt.authenticate(promptInfo)
    }
}
