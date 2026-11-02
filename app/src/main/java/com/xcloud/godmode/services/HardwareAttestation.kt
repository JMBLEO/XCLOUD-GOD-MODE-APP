package com.xcloud.godmode.services

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate

class HardwareAttestation {
    fun generateAttestedDeviceKey(): List<X509Certificate>? {
        return try {
            val keyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore"
            )
            
            val challenge = "GodModeHardwareBindingNonce".toByteArray(Charsets.UTF_8)
            val spec = KeyGenParameterSpec.Builder(
                "GodModeAttestedKey",
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge)
            .setIsStrongBoxBacked(true) // Require StrongBox if available
            .build()

            keyPairGenerator.initialize(spec)
            keyPairGenerator.generateKeyPair()

            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val certificates = keyStore.getCertificateChain("GodModeAttestedKey")

            certificates?.map { it as X509Certificate }
        } catch (e: Exception) {
            null
        }
    }
}
