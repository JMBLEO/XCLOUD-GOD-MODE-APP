package com.xcloud.godmode.network

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import org.json.JSONObject

/**
 * Proof-of-Possession (PoP) signature model containing headers and parameters.
 */
data class PopSignatureResult(
    val signatureInputHeader: String,
    val signatureHeader: String,
    val consolidatedSignatureHeader: String,
    val keyId: String,
    val createdEpochSeconds: Long,
    val publicJwk: JSONObject
)

/**
 * Enterprise-grade JOSE / HTTP Message Signatures (RFC 9421) and Proof-of-Possession (PoP)
 * cryptographic service for Xbox Cloud Gaming / GSSV v2 authorization.
 */
object JoseSignatureService {
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    val activeKeyPair: KeyPair by lazy {
        try {
            JoseCripto.keyPair
        } catch (_: Throwable) {
            // JVM / Fallback key generator
            val keyPairGen = java.security.KeyPairGenerator.getInstance("EC")
            keyPairGen.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            keyPairGen.generateKeyPair()
        }
    }

    /**
     * Extracts public JWK from active keypair.
     */
    fun getPublicJwk(): JSONObject {
        return try {
            val publicKey = activeKeyPair.public as ECPublicKey
            val point = publicKey.w
            val x = point.affineX.toByteArray().stripLeadingZero()
            val y = point.affineY.toByteArray().stripLeadingZero()

            val x32 = if (x.size < 32) ByteArray(32 - x.size) + x else if (x.size > 32) x.copyOfRange(x.size - 32, x.size) else x
            val y32 = if (y.size < 32) ByteArray(32 - y.size) + y else if (y.size > 32) y.copyOfRange(y.size - 32, y.size) else y

            JSONObject().apply {
                put("kty", "EC")
                put("crv", "P-256")
                put("x", Base64.encodeToString(x32, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
                put("y", Base64.encodeToString(y32, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
                put("use", "sig")
                put("alg", "ES256")
            }
        } catch (_: Throwable) {
            JSONObject().apply {
                put("kty", "EC")
                put("crv", "P-256")
                put("use", "sig")
                put("alg", "ES256")
            }
        }
    }

    /**
     * Calculates RFC 7638 SHA-256 JWK Thumbprint for EC P-256 public key.
     */
    fun getJwkThumbprint(): String {
        return try {
            val jwk = getPublicJwk()
            val canonicalJwk = JSONObject().apply {
                put("crv", jwk.optString("crv", "P-256"))
                put("kty", jwk.optString("kty", "EC"))
                put("x", jwk.optString("x"))
                put("y", jwk.optString("y"))
            }.toString()

            val digest = MessageDigest.getInstance("SHA-256").digest(canonicalJwk.toByteArray(StandardCharsets.UTF_8))
            Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (_: Throwable) {
            "godmode-p256-key"
        }
    }

    /**
     * Generates RFC 9421 / GSSV PoP Signature headers.
     * Schema:
     * Signature-Input: sig1=("@method" "@path" "authorization");created=...;keyid="...";alg="ES256"
     * Signature: sig1=:<Base64>:
     */
    fun generatePopSignature(
        method: String,
        path: String,
        authorizationToken: String,
        createdSeconds: Long = System.currentTimeMillis() / 1000
    ): PopSignatureResult {
        val normalizedMethod = method.lowercase()
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        val keyId = getJwkThumbprint()

        val sigParams = "(\"@method\" \"@path\" \"authorization\");created=$createdSeconds;keyid=\"$keyId\";alg=\"ES256\""

        val canonicalBase = buildString {
            append("\"@method\": ").append(normalizedMethod).append("\n")
            append("\"@path\": ").append(normalizedPath).append("\n")
            append("\"authorization\": ").append(authorizationToken.trim()).append("\n")
            append("\"@signature-params\": ").append(sigParams)
        }

        val rawSignature = signData(canonicalBase.toByteArray(StandardCharsets.UTF_8))
        val p1363Signature = try {
            derToP1363(rawSignature)
        } catch (_: Throwable) {
            rawSignature
        }

        val signatureBase64 = Base64.encodeToString(p1363Signature, Base64.NO_WRAP)
        val signatureInputHeader = "sig1=$sigParams"
        val signatureHeader = "sig1=:$signatureBase64:"
        val consolidatedHeader = "sig1=(\"@method\" \"@path\" \"authorization\");created=$createdSeconds;keyid=\"$keyId\";alg=\"ES256\";sig=\"$signatureBase64\""

        return PopSignatureResult(
            signatureInputHeader = signatureInputHeader,
            signatureHeader = signatureHeader,
            consolidatedSignatureHeader = consolidatedHeader,
            keyId = keyId,
            createdEpochSeconds = createdSeconds,
            publicJwk = getPublicJwk()
        )
    }

    private fun signData(data: ByteArray): ByteArray {
        val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
        signer.initSign(activeKeyPair.private)
        signer.update(data)
        return signer.sign()
    }

    private fun derToP1363(der: ByteArray): ByteArray {
        var offset = 2
        if (der[offset].toInt() == 0x02) offset++ else throw IllegalArgumentException("Invalid DER")
        val rLen = der[offset++].toInt()
        val r = der.copyOfRange(offset, offset + rLen).stripLeadingZero()
        offset += rLen
        if (der[offset++].toInt() != 0x02) throw IllegalArgumentException("Invalid DER")
        val sLen = der[offset++].toInt()
        val s = der.copyOfRange(offset, offset + sLen).stripLeadingZero()

        val r32 = if (r.size < 32) ByteArray(32 - r.size) + r else if (r.size > 32) r.copyOfRange(r.size - 32, r.size) else r
        val s32 = if (s.size < 32) ByteArray(32 - s.size) + s else if (s.size > 32) s.copyOfRange(s.size - 32, s.size) else s

        return r32 + s32
    }

    private fun ByteArray.stripLeadingZero(): ByteArray {
        return if (this.isNotEmpty() && this[0] == 0.toByte()) {
            this.copyOfRange(1, this.size)
        } else {
            this
        }
    }
}
