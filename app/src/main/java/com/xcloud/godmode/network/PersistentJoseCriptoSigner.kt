package com.xcloud.godmode.network

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore

/**
 * PersistentJoseCriptoSigner: Extends JoseCripto logic with AndroidKeyStore persistence.
 * This ensures the device signature remains consistent across app restarts, preventing
 * "Session Invalid" errors on the Azure backend.
 */
object PersistentJoseCriptoSigner {
    private const val KEY_ALIAS = "GodModeXalSigningKey_v2"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    fun getKeyPair(): KeyPair {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val privateKey = keyStore.getKey(KEY_ALIAS, null) as java.security.PrivateKey
            val publicKey = keyStore.getCertificate(KEY_ALIAS).publicKey
            return KeyPair(publicKey, privateKey)
        }

        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER
        )
        
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
        keyPairGenerator.initialize(spec as java.security.spec.AlgorithmParameterSpec)
        return keyPairGenerator.generateKeyPair()
    }
}
