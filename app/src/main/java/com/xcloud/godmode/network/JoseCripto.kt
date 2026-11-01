package com.xcloud.godmode.network

import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.util.*
import org.json.JSONObject

/**
 * JoseCripto handles ECDSA P-256 key generation and signing for Xbox Authentication (XAL).
 * It generates signatures in IEEE P1363 format (64 bytes) and creates Proof-of-Possession (PoP) tokens.
 */
object JoseCripto {
    private const val ALGORITHM = "SHA256withECDSA"
    
    // Persistent KeyPair for the session
    val keyPair: KeyPair by lazy {
        val keyGen = KeyPairGenerator.getInstance("EC")
        keyGen.initialize(ECGenParameterSpec("secp256r1"))
        keyGen.generateKeyPair()
    }

    fun getPublicJwk(): JSONObject {
        val publicKey = keyPair.public as ECPublicKey
        val point: ECPoint = publicKey.w
        
        val x = point.affineX.toByteArray().stripLeadingZero()
        val y = point.affineY.toByteArray().stripLeadingZero()

        // Ensure 32 bytes
        val x32 = if (x.size < 32) ByteArray(32 - x.size) + x else if (x.size > 32) x.copyOfRange(x.size - 32, x.size) else x
        val y32 = if (y.size < 32) ByteArray(32 - y.size) + y else if (y.size > 32) y.copyOfRange(y.size - 32, y.size) else y

        return JSONObject().apply {
            put("kty", "EC")
            put("crv", "P-256")
            put("x", Base64.encodeToString(x32, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            put("y", Base64.encodeToString(y32, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            put("use", "sig")
            put("alg", "ES256")
        }
    }

    fun generateSignatureHeader(
        method: String,
        url: String,
        body: String,
        authorizationToken: String = ""
    ): String {
        val timestamp = getWindowsFileTime()
        val nonce = UUID.randomUUID().toString()
        val version = byteArrayOf(0, 0, 0, 1)

        // Data to sign: Version + \0 + Timestamp + \0 + Nonce + \0 + Method + \0 + Path + \0 + Auth + \0 + Body + \0
        val uri = java.net.URI(url)
        val pathAndQuery = uri.path + (if (uri.query != null) "?" + uri.query else "")
        
        val bos = java.io.ByteArrayOutputStream()
        bos.write(version)
        bos.write(0)
        
        val tsBuf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(timestamp)
        bos.write(tsBuf.array())
        bos.write(0)
        
        bos.write(nonce.toByteArray())
        bos.write(0)
        
        bos.write(method.uppercase().toByteArray())
        bos.write(0)
        
        bos.write(pathAndQuery.toByteArray())
        bos.write(0)
        
        bos.write(authorizationToken.toByteArray())
        bos.write(0)
        
        bos.write(body.toByteArray())
        bos.write(0)

        val signature = Signature.getInstance(ALGORITHM)
        signature.initSign(keyPair.private)
        signature.update(bos.toByteArray())
        val derSignature = signature.sign()
        val ieeeSig = derToP1363(derSignature)

        // Header: Binary(Version(4) + Timestamp(8) + Nonce(16) + Signature(64)) -> Base64
        // Note: Xbox Live usually uses a 16-byte binary nonce in the header, 
        // but it accepts the UUID string if it's within limits. 
        // To be safe, we'll use a 16-byte random nonce for the header.
        val binaryNonce = ByteArray(16)
        SecureRandom().nextBytes(binaryNonce)
        
        // Re-signing with the same nonce as in the header if needed, but usually the signed nonce
        // and header nonce just need to be correlated. Actually, standard XAL:
        // Header contains Version, Timestamp, Nonce, and Signature.
        // The Signature is calculated using the SAME nonce as in the header.
        
        val nonceString = Base64.encodeToString(binaryNonce, Base64.NO_WRAP)
        
        // Re-construct signature base with the nonce string used in the header
        val bos2 = java.io.ByteArrayOutputStream()
        bos2.write(version)
        bos2.write(0)
        bos2.write(tsBuf.array())
        bos2.write(0)
        bos2.write(nonceString.toByteArray())
        bos2.write(0)
        bos2.write(method.uppercase().toByteArray())
        bos2.write(0)
        bos2.write(pathAndQuery.toByteArray())
        bos2.write(0)
        bos2.write(authorizationToken.toByteArray())
        bos2.write(0)
        bos2.write(body.toByteArray())
        bos2.write(0)

        val signature2 = Signature.getInstance(ALGORITHM)
        signature2.initSign(keyPair.private)
        signature2.update(bos2.toByteArray())
        val ieeeSig2 = derToP1363(signature2.sign())

        val headerBuffer = ByteBuffer.allocate(4 + 8 + 16 + 64).order(ByteOrder.BIG_ENDIAN)
        headerBuffer.put(version)
        headerBuffer.putLong(timestamp)
        headerBuffer.put(binaryNonce)
        headerBuffer.put(ieeeSig2)
        
        return Base64.encodeToString(headerBuffer.array(), Base64.NO_WRAP)
    }

    private fun getWindowsFileTime(): Long {
        return (System.currentTimeMillis() * 10000) + 116444736000000000L
    }

    private fun derToP1363(der: ByteArray): ByteArray {
        var offset = 2 
        if (der[offset].toInt() == 0x02) offset++ else throw Exception("Invalid DER")
        val rLen = der[offset++].toInt()
        val r = der.copyOfRange(offset, offset + rLen).stripLeadingZero()
        offset += rLen
        if (der[offset].toInt() == 0x02) offset++ else throw Exception("Invalid DER")
        val sLen = der[offset++].toInt()
        val s = der.copyOfRange(offset, offset + sLen).stripLeadingZero()

        val p1363 = ByteArray(64)
        System.arraycopy(r, 0, p1363, 32 - r.size, r.size)
        System.arraycopy(s, 0, p1363, 64 - s.size, s.size)
        return p1363
    }

    private fun ByteArray.stripLeadingZero(): ByteArray {
        return if (this.size > 1 && this[0].toInt() == 0) this.copyOfRange(1, this.size) else this
    }
}
