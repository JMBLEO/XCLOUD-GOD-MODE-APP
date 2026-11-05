package com.xcloud.godmode.network

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun safeElapsedRealtime(): Long =
    runCatching { SystemClock.elapsedRealtime() }.getOrElse { System.currentTimeMillis() }

// ============================================================================
// 1. DESCRIPTORES INMUTABLES PARA INYECCIÓN AL MEDIA PLANE (CERO CREDENCIALES)
// ============================================================================
data class IceTransportParameters(
    val remoteIp: String,
    val remotePort: Int,
    val localUfrag: String,
    val localPwd: String,
    val remoteUfrag: String,
    val remotePwd: String,
    val remoteDtlsFingerprint: String
)

data class NegotiatedVideoProfile(
    val width: Int,
    val height: Int,
    val targetFps: Int,
    val isHevcEnabled: Boolean,
    val videoPayloadType: Int,
    val audioPayloadType: Int,
    val videoSsrc: Long,
    val audioSsrc: Long
)

data class TransportConfiguration(
    val transportEpoch: Long,
    val receiveBufferBytes: Int = 4 * 1024 * 1024,
    val sendBufferBytes: Int = 1 * 1024 * 1024,
    val dscpTrafficClass: Int = 0x2E
)

/**
 * Único contrato que cruza del Control Plane hacia el selector de los 6 motores.
 * No contiene Refresh Tokens, MSA Tokens ni XSTS Tokens.
 */
data class StreamSessionContext(
    val sessionId: String,
    val transportEpoch: Long,
    val ephemeralStreamTicket: String,
    val ice: IceTransportParameters,
    val profile: NegotiatedVideoProfile,
    val transport: TransportConfiguration
)

/**
 * Instantánea completa para recuperación en caliente ante caídas de motor o red.
 */
data class SessionCheckpoint(
    val sessionId: String,
    val sessionPath: String,
    val regionBaseUri: String,
    val titleId: String,
    val transportEpoch: Long,
    val createdRealtimeMs: Long,
    val streamContext: StreamSessionContext
)

data class AuthHealthScore(
    val msaScore: Int,
    val xstsScore: Int,
    val gssvScore: Int,
    val refreshTokenValid: Boolean,
    val overallScore: Int
)

// ============================================================================
// 2. SESSION BLACKBOX RECORDER (AUDITORÍA FORENSE DEL CONTROL PLANE CON CRC32)
// ============================================================================
enum class ControlPlaneStage {
    LOGIN_ROOT,
    TOKEN_VAULT,
    TOKEN_REFRESH,
    ENDPOINT_DISCOVERY,
    REGION_SCORING,
    SESSION_NEGOTIATION,
    ICE_SDP_EXCHANGE,
    CHECKPOINT_RECOVERY,
    KEEPALIVE_HEARTBEAT
}

data class BlackboxEvent(
    val sequence: Long,
    val elapsedRealtimeMs: Long,
    val transportEpoch: Long,
    val stage: ControlPlaneStage,
    val code: Int,
    val detail: String,
    val crc32: Long
)

class SessionBlackboxRecorder {
    companion object {
        private const val CAPACITY = 512
    }

    private val ring = arrayOfNulls<BlackboxEvent>(CAPACITY)
    private val writeSeq = AtomicLong(0L)

    fun record(epoch: Long, stage: ControlPlaneStage, code: Int, detail: String) {
        val seq = writeSeq.incrementAndGet()
        val now = safeElapsedRealtime()
        val crc = CRC32().apply {
            update("$seq|$now|$epoch|${stage.name}|$code|$detail".toByteArray(Charsets.UTF_8))
        }.value

        val event = BlackboxEvent(
            sequence = seq,
            elapsedRealtimeMs = now,
            transportEpoch = epoch,
            stage = stage,
            code = code,
            detail = detail.take(120),
            crc32 = crc
        )
        ring[(seq % CAPACITY).toInt()] = event
    }

    fun exportVerifiedEvents(): List<BlackboxEvent> {
        val current = writeSeq.get()
        val result = mutableListOf<BlackboxEvent>()
        val start = (current - CAPACITY + 1).coerceAtLeast(1L)
        for (s in start..current) {
            val ev = ring[(s % CAPACITY).toInt()] ?: continue
            val expectedCrc = CRC32().apply {
                update("${ev.sequence}|${ev.elapsedRealtimeMs}|${ev.transportEpoch}|${ev.stage.name}|${ev.code}|${ev.detail}".toByteArray(Charsets.UTF_8))
            }.value
            if (expectedCrc == ev.crc32) {
                result.add(ev)
            }
        }
        return result
    }
}

// ============================================================================
// 3. TOKEN VAULT MANAGER (CIFRADO HARDWARE AES-256-GCM + PURGA EN CALIENTE)
// ============================================================================
class SecureTokenEnvelope(
    var msaAccessToken: CharArray,
    var msaRefreshToken: CharArray,
    val msaExpiresAtElapsedMs: Long,
    var lptTransferToken: CharArray,
    var xblUserToken: CharArray,
    var userHashUhs: CharArray,
    var xstsWebToken: CharArray,
    var xstsGssvToken: CharArray,
    val xstsExpiresAtElapsedMs: Long,
    var gsStreamToken: CharArray,
    val gsExpiresAtElapsedMs: Long,
    val discoveredBaseRegionUri: String,
    val market: String
) {
    /**
     * Sobrescribe físicamente los búferes en RAM con ceros
     * para mitigar volcados de memoria (memory dumps) o inspección en frío.
     */
    fun wipe() {
        msaAccessToken.fill('\u0000')
        msaRefreshToken.fill('\u0000')
        lptTransferToken.fill('\u0000')
        xblUserToken.fill('\u0000')
        userHashUhs.fill('\u0000')
        xstsWebToken.fill('\u0000')
        xstsGssvToken.fill('\u0000')
        gsStreamToken.fill('\u0000')
    }
}

data class StoredTokenEnvelope(
    val msaAccessToken: String,
    val msaRefreshToken: String,
    val msaExpiresAtElapsedMs: Long,
    val lptTransferToken: String,
    val xblUserToken: String,
    val userHashUhs: String,
    val xstsWebToken: String,
    val xstsGssvToken: String,
    val xstsExpiresAtElapsedMs: Long,
    val gsStreamToken: String,
    val gsExpiresAtElapsedMs: Long,
    val discoveredBaseRegionUri: String,
    val market: String
)

class HardwareTokenVault(context: Context) {
    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val AES_KEY_ALIAS = "GodModeMasterTokenVaultKey"
        private const val PREFS_NAME = "godmode_encrypted_token_vault"
        private const val KEY_PAYLOAD = "vault_cipher_payload"
        private const val KEY_IV = "vault_cipher_iv"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    @Volatile private var memoryCache: StoredTokenEnvelope? = null
    @Volatile private var secureEnvelopeCache: SecureTokenEnvelope? = null

    init {
        ensureHardwareAesKey()
    }

    private fun ensureHardwareAesKey() {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (!ks.containsAlias(AES_KEY_ALIAS)) {
            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val spec = KeyGenParameterSpec.Builder(
                AES_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGen.init(spec)
            keyGen.generateKey()
        }
    }

    private fun getSecretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return (ks.getEntry(AES_KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    @Synchronized
    fun persistEnvelope(envelope: StoredTokenEnvelope) {
        memoryCache = envelope
        secureEnvelopeCache?.wipe()
        secureEnvelopeCache = SecureTokenEnvelope(
            msaAccessToken = envelope.msaAccessToken.toCharArray(),
            msaRefreshToken = envelope.msaRefreshToken.toCharArray(),
            msaExpiresAtElapsedMs = envelope.msaExpiresAtElapsedMs,
            lptTransferToken = envelope.lptTransferToken.toCharArray(),
            xblUserToken = envelope.xblUserToken.toCharArray(),
            userHashUhs = envelope.userHashUhs.toCharArray(),
            xstsWebToken = envelope.xstsWebToken.toCharArray(),
            xstsGssvToken = envelope.xstsGssvToken.toCharArray(),
            xstsExpiresAtElapsedMs = envelope.xstsExpiresAtElapsedMs,
            gsStreamToken = envelope.gsStreamToken.toCharArray(),
            gsExpiresAtElapsedMs = envelope.gsExpiresAtElapsedMs,
            discoveredBaseRegionUri = envelope.discoveredBaseRegionUri,
            market = envelope.market
        )

        val json = JSONObject().apply {
            put("msaAccess", envelope.msaAccessToken)
            put("msaRefresh", envelope.msaRefreshToken)
            put("msaExp", envelope.msaExpiresAtElapsedMs)
            put("lpt", envelope.lptTransferToken)
            put("xblUser", envelope.xblUserToken)
            put("uhs", envelope.userHashUhs)
            put("xstsWeb", envelope.xstsWebToken)
            put("xstsGssv", envelope.xstsGssvToken)
            put("xstsExp", envelope.xstsExpiresAtElapsedMs)
            put("gsToken", envelope.gsStreamToken)
            put("gsExp", envelope.gsExpiresAtElapsedMs)
            put("regionUri", envelope.discoveredBaseRegionUri)
            put("market", envelope.market)
        }.toString()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val cipherBytes = cipher.doFinal(json.toByteArray(Charsets.UTF_8))

        prefs.edit()
            .putString(KEY_PAYLOAD, Base64.encodeToString(cipherBytes, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    @Synchronized
    fun purgeMemoryCache() {
        secureEnvelopeCache?.wipe()
        secureEnvelopeCache = null
        memoryCache = null
    }

    @Synchronized
    fun loadEnvelope(): StoredTokenEnvelope? {
        memoryCache?.let { return it }
        val payloadB64 = prefs.getString(KEY_PAYLOAD, null) ?: return null
        val ivB64 = prefs.getString(KEY_IV, null) ?: return null

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP))
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
            val plainBytes = cipher.doFinal(Base64.decode(payloadB64, Base64.NO_WRAP))
            val obj = JSONObject(String(plainBytes, Charsets.UTF_8))
            StoredTokenEnvelope(
                msaAccessToken = obj.getString("msaAccess"),
                msaRefreshToken = obj.getString("msaRefresh"),
                msaExpiresAtElapsedMs = obj.getLong("msaExp"),
                lptTransferToken = obj.optString("lpt", ""),
                xblUserToken = obj.getString("xblUser"),
                userHashUhs = obj.getString("uhs"),
                xstsWebToken = obj.getString("xstsWeb"),
                xstsGssvToken = obj.getString("xstsGssv"),
                xstsExpiresAtElapsedMs = obj.getLong("xstsExp"),
                gsStreamToken = obj.getString("gsToken"),
                gsExpiresAtElapsedMs = obj.getLong("gsExp"),
                discoveredBaseRegionUri = obj.getString("regionUri"),
                market = obj.optString("market", "CL")
            ).also { memoryCache = it }
        } catch (e: Exception) {
            null
        }
    }
}

// ============================================================================
// 4. DESCUBRIMIENTO DE ENDPOINTS, CAPACIDADES Y SCORE REGIONAL (SONDA STUN RFC 5389)
// ============================================================================
data class SessionRegionDescriptor(
    val regionName: String,
    val baseUri: String,
    val networkTestEndpoint: String,
    val isDefault: Boolean,
    val calculatedScore: Int = 100
)

interface NetworkRoutingPolicyPlugin {
    fun decorateHeaders(targetUrl: String, baseHeaders: MutableMap<String, String>)
}

class DefaultCleanNetworkPolicy : NetworkRoutingPolicyPlugin {
    override fun decorateHeaders(targetUrl: String, baseHeaders: MutableMap<String, String>) {
        // En modo Carrier-Grade se utiliza el estándar limpio de red sin manipulación
    }
}

class CapabilityNegotiationEngine {
    fun buildClientTelemetryHeader(width: Int, height: Int, fps: Int): String {
        return JSONObject().apply {
            put("appInfo", JSONObject().apply {
                put("env", JSONObject().apply {
                    put("clientAppId", "com.xcloud.godmode")
                    put("clientAppType", "native")
                    put("clientAppVersion", "4.2.0")
                })
            })
            put("dev", JSONObject().apply {
                put("hw", JSONObject().apply {
                    put("make", Build.MANUFACTURER)
                    put("model", Build.MODEL)
                    put("sdkType", "native")
                })
                put("os", JSONObject().apply {
                    put("name", "android")
                    put("ver", Build.VERSION.RELEASE)
                })
                put("displayInfo", JSONObject().apply {
                    put("dimensions", JSONObject().apply {
                        put("widthInPixels", width)
                        put("heightInPixels", height)
                    })
                    put("pixelDensity", JSONObject().apply {
                        put("dpiX", 420)
                        put("dpiY", 420)
                    })
                    put("refreshRate", fps)
                    put("supportedCodecs", JSONArray().put("H264_SVC").put("HEVC"))
                })
            })
        }.toString()
    }
}

class RegionScoringEngine(private val blackbox: SessionBlackboxRecorder) {
    private val historicalStability = ConcurrentHashMap<String, Int>()

    suspend fun selectOptimalRegion(
        regions: List<SessionRegionDescriptor>,
        epoch: Long
    ): SessionRegionDescriptor = withContext(Dispatchers.IO) {
        if (regions.isEmpty()) throw IllegalStateException("Lista de regiones vacía en descubrimiento")

        val scored = regions.map { region ->
            val host = region.networkTestEndpoint.ifBlank {
                runCatching { URL(region.baseUri).host }.getOrDefault("")
            }
            val (rttMs, jitterMs, lossRatio) = probeStunUdpLatency(host)
            val stabilityBonus = historicalStability.getOrDefault(region.regionName, 95)

            // Cálculo ponderado: RTT (45%), Jitter (25%), Loss (20%), Estabilidad Histórica (10%)
            val rttScore = (100 - (rttMs / 2)).coerceIn(0, 100)
            val jitterScore = (100 - (jitterMs * 3)).coerceIn(0, 100)
            val lossScore = (100 - (lossRatio * 500).toInt()).coerceIn(0, 100)

            val composite = ((rttScore * 0.45) + (jitterScore * 0.25) +
                    (lossScore * 0.20) + (stabilityBonus * 0.10)).toInt().coerceIn(1, 100)

            region.copy(calculatedScore = if (region.isDefault) (composite + 5).coerceAtMost(100) else composite)
        }.sortedByDescending { it.calculatedScore }

        val winner = scored.first()
        blackbox.record(epoch, ControlPlaneStage.REGION_SCORING, winner.calculatedScore, "Selected=${winner.regionName} (${winner.baseUri})")
        winner
    }

    /**
     * Sonda STUN RFC 5389 directa en UDP (puerto 3478) para medir RTT, jitter y pérdida real en capa de transporte.
     */
    private fun probeStunUdpLatency(hostname: String, port: Int = 3478): Triple<Int, Int, Float> {
        if (hostname.isBlank()) return Triple(75, 8, 0.0f)
        var socket: DatagramSocket? = null
        return try {
            val address = InetAddress.getByName(hostname)
            socket = DatagramSocket().apply { soTimeout = 1200 }

            val stunRequest = ByteBuffer.allocate(20).apply {
                putShort(0x0001.toShort()) // Binding Request
                putShort(0x0000.toShort()) // Message Length: 0
                putInt(0x2112A442)         // Magic Cookie
                putLong(0x123456789ABCDEF0L) // Transaction ID (12 bytes)
                putInt(0x0FEDCBA9)
            }.array()

            val rttSamples = mutableListOf<Int>()
            var lostPackets = 0

            for (i in 0 until 3) {
                val t0 = safeElapsedRealtime()
                val packet = DatagramPacket(stunRequest, stunRequest.size, address, port)
                socket.send(packet)

                val recvBuffer = ByteArray(128)
                val recvPacket = DatagramPacket(recvBuffer, recvBuffer.size)
                try {
                    socket.receive(recvPacket)
                    val sampleRtt = (safeElapsedRealtime() - t0).toInt()
                    rttSamples.add(sampleRtt)
                } catch (_: Exception) {
                    lostPackets++
                }
            }

            if (rttSamples.isEmpty()) {
                Triple(250, 40, 1.0f)
            } else {
                val avgRtt = rttSamples.average().toInt()
                val jitter = if (rttSamples.size > 1) {
                    kotlin.math.abs(rttSamples.first() - rttSamples.last())
                } else 0
                val lossRatio = lostPackets / 3.0f
                Triple(avgRtt, jitter, lossRatio)
            }
        } catch (_: Exception) {
            Triple(250, 40, 1.0f)
        } finally {
            socket?.close()
        }
    }

    fun recordRegionOutcome(regionName: String, success: Boolean) {
        val current = historicalStability.getOrDefault(regionName, 95)
        val updated = if (success) (current + 2).coerceAtMost(100) else (current - 15).coerceAtLeast(10)
        historicalStability[regionName] = updated
    }
}

// ============================================================================
// 5. FIRMA CRIPTOGRÁFICA EC P-256 PROOF-OF-POSSESSION
// ============================================================================
class JoseCriptoProofOfPossession {
    private val keyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
    }.generateKeyPair()

    fun getPublicJwkJson(): JSONObject {
        val pub = keyPair.public as ECPublicKey
        val xBytes = pub.w.affineX.toByteArray().takeLast(32).toByteArray()
        val yBytes = pub.w.affineY.toByteArray().takeLast(32).toByteArray()
        return JSONObject().apply {
            put("kty", "EC")
            put("alg", "ES256")
            put("crv", "P-256")
            put("x", Base64.encodeToString(xBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            put("y", Base64.encodeToString(yBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        }
    }

    fun computeSignatureHeader(urlPath: String, authHeader: String, body: String): String {
        val windowsEpochTicks = ((System.currentTimeMillis() / 1000L) + 11644473600L) * 10000000L
        val buffer = ByteBuffer.allocate(4 + 1 + 8 + 1 + 4 + 1 + urlPath.length + 1 + authHeader.length + 1 + body.length + 1)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(1)
        buffer.put(0)
        buffer.putLong(windowsEpochTicks)
        buffer.put(0)
        buffer.put("POST".toByteArray(Charsets.UTF_8))
        buffer.put(0)
        buffer.put(urlPath.toByteArray(Charsets.UTF_8))
        buffer.put(0)
        buffer.put(authHeader.toByteArray(Charsets.UTF_8))
        buffer.put(0)
        buffer.put(body.toByteArray(Charsets.UTF_8))
        buffer.put(0)

        val sig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(buffer.array())
        }.sign()

        val out = ByteBuffer.allocate(12 + sig.size).order(ByteOrder.BIG_ENDIAN)
        out.putInt(1)
        out.putLong(windowsEpochTicks)
        out.put(sig)
        return Base64.encodeToString(out.array(), Base64.NO_WRAP)
    }
}

// ============================================================================
// 6. AUTHENTICATION AUTHORITY (AUTORIDAD ÚNICA DE LOGIN Y REFRESH PREDICTIVO)
// ============================================================================
class AuthenticationAuthority(
    private val vault: HardwareTokenVault,
    private val regionScorer: RegionScoringEngine,
    private val blackbox: SessionBlackboxRecorder,
    private val routingPolicy: NetworkRoutingPolicyPlugin = DefaultCleanNetworkPolicy()
) {
    companion object {
        private const val DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode"
        private const val OAUTH_TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
        private const val LIVE_TOKEN_URL = "https://login.live.com/oauth20_token.srf"
        private const val XBL_USER_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate"
        private const val XSTS_AUTHORIZE_URL = "https://xsts.auth.xboxlive.com/xsts/authorize"
        private const val CLIENT_ID_DEVICE = "1f907974-e22b-4810-a9de-d9647380c97e"
        private const val CLIENT_ID_XAL = "000000004c20a908"
    }

    private val authMutex = Mutex()
    private val schedulerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var refreshSchedulerJob: Job? = null

    private val _authHealth = MutableStateFlow(AuthHealthScore(100, 100, 100, true, 100))
    val authHealth: StateFlow<AuthHealthScore> = _authHealth

    data class DeviceCodePrompt(
        val deviceCode: String,
        val userCode: String,
        val verificationUriWithOtc: String,
        val expiresInSeconds: Int,
        val intervalSeconds: Int
    )

    suspend fun initiateDeviceCodeFlow(epoch: Long): DeviceCodePrompt = withContext(Dispatchers.IO) {
        val form = "client_id=$CLIENT_ID_DEVICE&scope=xboxlive.signin%20offline_access%20openid%20profile"
        val res = executeFormPost(DEVICE_CODE_URL, form)
        val userCode = res.getString("user_code")
        val verificationUri = res.optString("verification_uri", "https://www.microsoft.com/link")
        blackbox.record(epoch, ControlPlaneStage.LOGIN_ROOT, 200, "DeviceCodeIssued=$userCode")
        DeviceCodePrompt(
            deviceCode = res.getString("device_code"),
            userCode = userCode,
            verificationUriWithOtc = "$verificationUri?otc=$userCode",
            expiresInSeconds = res.optInt("expires_in", 900),
            intervalSeconds = res.optInt("interval", 5)
        )
    }

    suspend fun pollDeviceCodeUntilAuthenticated(prompt: DeviceCodePrompt, epoch: Long): StoredTokenEnvelope = withContext(Dispatchers.IO) {
        var currentIntervalMs = prompt.intervalSeconds * 1000L
        val deadlineMs = safeElapsedRealtime() + (prompt.expiresInSeconds * 1000L)

        while (safeElapsedRealtime() < deadlineMs) {
            delay(currentIntervalMs)
            val form = "grant_type=urn:ietf:params:oauth:grant-type:device_code&client_id=$CLIENT_ID_DEVICE&device_code=${prompt.deviceCode}"
            val response = executeFormPostAllowingOAuthErrors(OAUTH_TOKEN_URL, form)
            val error = response.optString("error", "")

            when (error) {
                "" -> {
                    val accessToken = response.getString("access_token")
                    val refreshToken = response.getString("refresh_token")
                    val expiresInSec = response.optInt("expires_in", 3600)
                    return@withContext completeFullAuthenticationCascade(accessToken, refreshToken, expiresInSec, epoch)
                }
                "authorization_pending" -> continue
                "slow_down" -> {
                    currentIntervalMs += 5000L
                    continue
                }
                else -> throw IllegalStateException("Fallo en autorización Device Code: $error")
            }
        }
        throw IllegalStateException("El código de dispositivo expiró antes de completarse.")
    }

    suspend fun exchangePkceAuthorizationCode(
        authorizationCode: String,
        codeVerifier: String,
        epoch: Long
    ): StoredTokenEnvelope = withContext(Dispatchers.IO) {
        val form = "client_id=$CLIENT_ID_XAL&code=$authorizationCode&code_verifier=$codeVerifier" +
                "&grant_type=authorization_code&redirect_uri=ms-xal-$CLIENT_ID_XAL://auth&scope=service::user.auth.xboxlive.com::MBI_SSL"
        val res = executeFormPost(LIVE_TOKEN_URL, form)
        val accessToken = res.getString("access_token")
        val refreshToken = res.getString("refresh_token")
        val expiresInSec = res.optInt("expires_in", 3600)
        completeFullAuthenticationCascade(accessToken, refreshToken, expiresInSec, epoch)
    }

    private suspend fun completeFullAuthenticationCascade(
        msaAccessToken: String,
        msaRefreshToken: String,
        msaExpiresInSec: Int,
        epoch: Long
    ): StoredTokenEnvelope = authMutex.withLock {
        val nowMs = safeElapsedRealtime()
        val msaExpMs = nowMs + (msaExpiresInSec * 1000L)

        val lptToken = requestTransferLptToken(msaRefreshToken)

        val rpsTicket = if (msaAccessToken.startsWith("d=") || msaAccessToken.startsWith("t=")) {
            msaAccessToken
        } else {
            "d=$msaAccessToken"
        }

        val xblPayload = JSONObject().apply {
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType", "JWT")
            put("Properties", JSONObject().apply {
                put("AuthMethod", "RPS")
                put("SiteName", "user.auth.xboxlive.com")
                put("RpsTicket", rpsTicket)
            })
        }
        val xblRes = executeJsonPost(XBL_USER_AUTH_URL, xblPayload.toString(), null)
        val xblUserToken = xblRes.getString("Token")
        val uhs = xblRes.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).getString("uhs")

        val xstsWebToken = requestXstsToken(xblUserToken, "http://xboxlive.com")
        val xstsGssvToken = requestXstsToken(xblUserToken, "http://gssv.xboxlive.com/")
        val xstsExpMs = nowMs + (4 * 3600 * 1000L)

        val (gsToken, gsDurationSec, market, regions) = discoverStreamingEnvironmentAndToken(uhs, xstsGssvToken, epoch)
        val optimalRegion = regionScorer.selectOptimalRegion(regions, epoch)

        val envelope = StoredTokenEnvelope(
            msaAccessToken = msaAccessToken,
            msaRefreshToken = msaRefreshToken,
            msaExpiresAtElapsedMs = msaExpMs,
            lptTransferToken = lptToken,
            xblUserToken = xblUserToken,
            userHashUhs = uhs,
            xstsWebToken = xstsWebToken,
            xstsGssvToken = xstsGssvToken,
            xstsExpiresAtElapsedMs = xstsExpMs,
            gsStreamToken = gsToken,
            gsExpiresAtElapsedMs = nowMs + (gsDurationSec * 1000L),
            discoveredBaseRegionUri = optimalRegion.baseUri,
            market = market
        )

        vault.persistEnvelope(envelope)
        startPredictiveTokenRefreshScheduler(epoch)
        blackbox.record(epoch, ControlPlaneStage.TOKEN_VAULT, 200, "CascadeComplete Region=${optimalRegion.regionName}")
        envelope
    }

    private fun requestXstsToken(xblUserToken: String, relyingParty: String): String {
        val payload = JSONObject().apply {
            put("RelyingParty", relyingParty)
            put("TokenType", "JWT")
            put("Properties", JSONObject().apply {
                put("SandboxId", "RETAIL")
                put("UserTokens", JSONArray().put(xblUserToken))
            })
        }
        val res = executeJsonPost(XSTS_AUTHORIZE_URL, payload.toString(), null)
        if (res.has("XErr")) {
            val xerr = res.getLong("XErr")
            val msg = when (xerr) {
                2148916233L -> "La cuenta no tiene un perfil Gamertag activo."
                2148916235L -> "El servicio no está disponible en la región de la cuenta."
                2148916238L -> "Cuenta infantil: requiere autorización de grupo familiar."
                else -> "Error de autorización XSTS (XErr=$xerr)"
            }
            throw IllegalStateException(msg)
        }
        return res.getString("Token")
    }

    private data class DiscoveredStreamEnv(
        val gsToken: String,
        val durationSec: Long,
        val market: String,
        val regions: List<SessionRegionDescriptor>
    )

    private fun discoverStreamingEnvironmentAndToken(
        uhs: String,
        xstsGssvToken: String,
        epoch: Long
    ): DiscoveredStreamEnv {
        val discoveryBootstrapHosts = listOf(
            "https://xgpuweb.gssv-play-prod.xboxlive.com/v2/login/user" to "xgpuweb",
            "https://xgpuwebf2p.gssv-play-prod.xboxlive.com/v2/login/user" to "xgpuwebf2p",
            "https://xhome.gssv-play-prod.xboxlive.com/v2/login/user" to "xhome"
        )

        for ((endpoint, offeringId) in discoveryBootstrapHosts) {
            try {
                val payload = JSONObject().apply {
                    put("token", "XBL3.0 x=$uhs;$xstsGssvToken")
                    put("offeringId", offeringId)
                }
                val res = executeJsonPost(endpoint, payload.toString(), null)
                val gsToken = res.getString("gsToken")
                val durationSec = res.optLong("durationInSeconds", 14400L)
                val market = res.optString("market", "CL")

                val regionsJson = res.getJSONObject("offeringSettings").getJSONArray("regions")
                val descriptors = mutableListOf<SessionRegionDescriptor>()
                for (i in 0 until regionsJson.length()) {
                    val r = regionsJson.getJSONObject(i)
                    descriptors.add(
                        SessionRegionDescriptor(
                            regionName = r.getString("name"),
                            baseUri = r.getString("baseUri"),
                            networkTestEndpoint = r.optString("networkTestHostname", ""),
                            isDefault = r.optBoolean("isDefault", false)
                        )
                    )
                }
                blackbox.record(epoch, ControlPlaneStage.ENDPOINT_DISCOVERY, 200, "Offering=$offeringId Regions=${descriptors.size}")
                return DiscoveredStreamEnv(gsToken, durationSec, market, descriptors)
            } catch (_: Exception) {
                continue
            }
        }
        throw IllegalStateException("Fallo al descubrir entorno de streaming en todos los puntos de entrada.")
    }

    fun startPredictiveTokenRefreshScheduler(epoch: Long) {
        refreshSchedulerJob?.cancel()
        refreshSchedulerJob = schedulerScope.launch {
            while (isActive) {
                delay(30_000L)
                val env = vault.loadEnvelope() ?: continue
                val now = safeElapsedRealtime()

                val msaRemainingMs = env.msaExpiresAtElapsedMs - now
                val gsRemainingMs = env.gsExpiresAtElapsedMs - now
                val minRemainingMs = minOf(msaRemainingMs, gsRemainingMs)

                val msaScore = ((msaRemainingMs / 600_000f) * 100).toInt().coerceIn(0, 100)
                val gsScore = ((gsRemainingMs / 600_000f) * 100).toInt().coerceIn(0, 100)
                _authHealth.value = AuthHealthScore(
                    msaScore = msaScore,
                    xstsScore = 100,
                    gssvScore = gsScore,
                    refreshTokenValid = env.msaRefreshToken.isNotBlank(),
                    overallScore = minOf(msaScore, gsScore)
                )

                if (minRemainingMs <= 600_000L) {
                    runCatching {
                        refreshAllCredentialsSilently(env, epoch)
                    }.onFailure { err ->
                        blackbox.record(epoch, ControlPlaneStage.TOKEN_REFRESH, 500, "RefreshRetry=${err.message}")
                    }
                }
            }
        }
    }

    suspend fun refreshAllCredentialsSilently(current: StoredTokenEnvelope, epoch: Long): StoredTokenEnvelope {
        val form = "grant_type=refresh_token&client_id=$CLIENT_ID_DEVICE&refresh_token=${current.msaRefreshToken}&scope=xboxlive.signin%20offline_access"
        val res = executeFormPost(OAUTH_TOKEN_URL, form)
        val newAccess = res.getString("access_token")
        val newRefresh = res.optString("refresh_token", current.msaRefreshToken)
        val expiresIn = res.optInt("expires_in", 3600)
        blackbox.record(epoch, ControlPlaneStage.TOKEN_REFRESH, 200, "SilentRefreshCompleted")
        return completeFullAuthenticationCascade(newAccess, newRefresh, expiresIn, epoch)
    }

    private fun requestTransferLptToken(refreshToken: String): String {
        return try {
            val form = "grant_type=refresh_token&client_id=$CLIENT_ID_XAL&refresh_token=$refreshToken" +
                    "&scope=service::http://Passport.NET/purpose::PURPOSE_XBOX_CLOUD_CONSOLE_TRANSFER_TOKEN"
            val res = executeFormPost(LIVE_TOKEN_URL, form)
            res.optString("access_token", "")
        } catch (_: Exception) {
            ""
        }
    }

    private fun executeFormPost(urlStr: String, formBody: String): JSONObject {
        val res = executeFormPostAllowingOAuthErrors(urlStr, formBody)
        if (res.has("error")) {
            throw IllegalStateException("OAuth Error en $urlStr: ${res.optString("error_description", res.getString("error"))}")
        }
        return res
    }

    private fun executeFormPostAllowingOAuthErrors(urlStr: String, formBody: String): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(formBody) }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun executeJsonPost(urlStr: String, jsonBody: String, bearer: String?): JSONObject {
        val headers = mutableMapOf(
            "Content-Type" to "application/json; charset=utf-8",
            "Accept" to "application/json",
            "x-xbl-contract-version" to "1"
        )
        if (!bearer.isNullOrBlank()) headers["Authorization"] = "Bearer $bearer"
        routingPolicy.decorateHeaders(urlStr, headers)

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(jsonBody) }
        val code = conn.responseCode
        if (code == 204) return JSONObject()
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}

// ============================================================================
// 7. GLOBAL SESSION AUTHORITY (FASES 5, 6, 7 Y CHECKPOINT MANAGER)
// ============================================================================
class GlobalSessionAuthority(
    private val vault: HardwareTokenVault,
    private val authAuthority: AuthenticationAuthority,
    private val capabilityEngine: CapabilityNegotiationEngine,
    private val blackbox: SessionBlackboxRecorder
) {
    private val sessionMutex = Mutex()
    private val transportEpochCounter = AtomicLong(1L)
    private val keepaliveRunning = AtomicBoolean(false)
    private var keepaliveJob: Job? = null

    @Volatile private var activeCheckpoint: SessionCheckpoint? = null

    fun currentSessionContext(): StreamSessionContext? = activeCheckpoint?.streamContext

    suspend fun negotiateAndBuildSessionContext(
        titleId: String,
        localDtlsFingerprint: String,
        width: Int = 1920,
        height: Int = 1080,
        targetFps: Int = 60,
        enableHevc: Boolean = true
    ): StreamSessionContext = sessionMutex.withLock {
        val epoch = transportEpochCounter.incrementAndGet()
        val env = vault.loadEnvelope() ?: throw IllegalStateException("TokenVault vacío: ejecute login primero.")

        val localUfrag = generateRandomIceToken(8)
        val localPwd = generateRandomIceToken(24)
        val deviceSessionId = UUID.randomUUID().toString()

        val playUrl = "${env.discoveredBaseRegionUri}/v5/sessions/cloud/play"
        val playPayload = JSONObject().apply {
            put("titleId", titleId)
            put("systemUpdateGroup", "")
            put("settings", JSONObject().apply {
                put("nanoVersion", "V3;NativeRawTransport")
                put("enableTextToSpeech", false)
                put("highContrast", 0)
                put("locale", "es-CL")
                put("useIceConnection", true)
                put("timezoneOffsetMinutes", -180)
                put("sdkType", "native")
                put("osName", "android")
            })
            put("serverId", "")
            put("fallbackRegionNames", JSONArray())
        }

        val deviceHeaders = mapOf(
            "X-MS-Device-Info" to capabilityEngine.buildClientTelemetryHeader(width, height, targetFps),
            "X-MS-Session-Id" to deviceSessionId
        )

        val playRes = executeSessionPost(playUrl, playPayload.toString(), env.gsStreamToken, deviceHeaders)
        val sessionPath = playRes.getString("sessionPath")
        val sessionId = sessionPath.substringAfterLast("/")
        blackbox.record(epoch, ControlPlaneStage.SESSION_NEGOTIATION, 200, "SessionCreated=$sessionId")

        awaitBladeProvisioned(env.discoveredBaseRegionUri, sessionPath, env.gsStreamToken, epoch)

        val sdpUrl = "${env.discoveredBaseRegionUri}/$sessionPath/sdp"
        val sdpOffer = buildNativeSdpOffer(localUfrag, localPwd, localDtlsFingerprint, enableHevc)
        val sdpPayload = JSONObject().apply {
            put("messageType", "offer")
            put("sdp", sdpOffer)
            put("configuration", JSONObject().apply {
                put("chatConfiguration", JSONObject().apply {
                    put("bytesPerSample", 2)
                    put("expectedClipDurationMs", 20)
                    put("format", JSONObject().apply { put("codec", "opus"); put("container", "raw") })
                    put("numChannels", 1)
                    put("sampleFrequencyHz", 24000)
                })
                put("chat", JSONObject().apply { put("minVersion", 1); put("maxVersion", 1) })
                put("control", JSONObject().apply { put("minVersion", 1); put("maxVersion", 3) })
                put("input", JSONObject().apply { put("minVersion", 1); put("maxVersion", 8) })
                put("message", JSONObject().apply { put("minVersion", 1); put("maxVersion", 1) })
            })
        }
        executeSessionPost(sdpUrl, sdpPayload.toString(), env.gsStreamToken)

        val sdpAnswerRaw = pollEndpointUntilPopulated(sdpUrl, env.gsStreamToken)
        val remoteSdp = extractSdpFromDoubleJson(sdpAnswerRaw)
        val parsedSdp = parseRemoteSdpParameters(remoteSdp)

        val iceUrl = "${env.discoveredBaseRegionUri}/$sessionPath/ice"
        val iceOffer = JSONObject().apply {
            put("messageType", "iceCandidate")
            put("candidate", "candidate:1 1 UDP 2122260223 0.0.0.0 9 typ host generation 0 ufrag $localUfrag network-id 1")
        }
        executeSessionPost(iceUrl, iceOffer.toString(), env.gsStreamToken)

        val iceAnswerRaw = pollEndpointUntilPopulated(iceUrl, env.gsStreamToken)
        val (remoteIp, remotePort) = extractSanitizedIceCandidate(iceAnswerRaw)
        blackbox.record(epoch, ControlPlaneStage.ICE_SDP_EXCHANGE, 200, "Endpoint=$remoteIp:$remotePort")

        startAutonomousKeepalive(env.discoveredBaseRegionUri, sessionPath, env.gsStreamToken, epoch)

        val streamContext = StreamSessionContext(
            sessionId = sessionId,
            transportEpoch = epoch,
            ephemeralStreamTicket = UUID.nameUUIDFromBytes("$sessionId:$epoch".toByteArray()).toString(),
            ice = IceTransportParameters(
                remoteIp = remoteIp,
                remotePort = remotePort,
                localUfrag = localUfrag,
                localPwd = localPwd,
                remoteUfrag = parsedSdp.ufrag,
                remotePwd = parsedSdp.pwd,
                remoteDtlsFingerprint = parsedSdp.fingerprint
            ),
            profile = NegotiatedVideoProfile(
                width = width,
                height = height,
                targetFps = targetFps,
                isHevcEnabled = enableHevc,
                videoPayloadType = parsedSdp.videoPt,
                audioPayloadType = parsedSdp.audioPt,
                videoSsrc = parsedSdp.videoSsrc,
                audioSsrc = parsedSdp.audioSsrc
            ),
            transport = TransportConfiguration(transportEpoch = epoch)
        )

        activeCheckpoint = SessionCheckpoint(
            sessionId = sessionId,
            sessionPath = sessionPath,
            regionBaseUri = env.discoveredBaseRegionUri,
            titleId = titleId,
            transportEpoch = epoch,
            createdRealtimeMs = safeElapsedRealtime(),
            streamContext = streamContext
        )

        streamContext
    }

    suspend fun resumeFromCheckpoint(): StreamSessionContext = sessionMutex.withLock {
        val checkpoint = activeCheckpoint ?: throw IllegalStateException("No existe SessionCheckpoint activo")
        val newEpoch = transportEpochCounter.incrementAndGet()
        val updatedContext = checkpoint.streamContext.copy(
            transportEpoch = newEpoch,
            transport = checkpoint.streamContext.transport.copy(transportEpoch = newEpoch)
        )
        activeCheckpoint = checkpoint.copy(transportEpoch = newEpoch, streamContext = updatedContext)
        blackbox.record(newEpoch, ControlPlaneStage.CHECKPOINT_RECOVERY, 200, "ResumedSession=${checkpoint.sessionId}")
        updatedContext
    }

    private fun extractSdpFromDoubleJson(root: JSONObject): String {
        if (!root.has("exchangeResponse")) return root.optString("sdp", "")
        val exchange = root.get("exchangeResponse")
        return when (exchange) {
            is JSONObject -> exchange.getString("sdp")
            is String -> JSONObject(exchange).getString("sdp")
            else -> throw IllegalStateException("Formato exchangeResponse SDP no reconocido")
        }
    }

    private fun extractSanitizedIceCandidate(root: JSONObject): Pair<String, Int> {
        val rawCandidates = if (root.has("candidates")) root.get("candidates") else root.optString("exchangeResponse", "[]")
        val candArray = when (rawCandidates) {
            is JSONArray -> rawCandidates
            is String -> {
                val parsed = if (rawCandidates.trim().startsWith("{")) {
                    JSONObject(rawCandidates).optJSONArray("candidates") ?: JSONArray()
                } else {
                    JSONArray(rawCandidates)
                }
                parsed
            }
            else -> JSONArray()
        }

        var ipv6Fallback: Pair<String, Int>? = null

        for (i in 0 until candArray.length()) {
            val item = candArray.getJSONObject(i)
            val rawLine = item.optString("candidate", "").trim().removePrefix("a=")
            if (rawLine.isBlank() || rawLine.contains("end-of-candidates", ignoreCase = true)) {
                continue
            }
            val tokens = rawLine.split(Regex("\\s+"))
            if (tokens.size >= 6 && tokens[2].equals("UDP", ignoreCase = true)) {
                val ip = tokens[4]
                val port = tokens[5].toIntOrNull() ?: 0
                if (port <= 0 || ip == "0.0.0.0") continue
                if (!ip.contains(":")) {
                    return Pair(ip, port)
                } else if (ipv6Fallback == null) {
                    ipv6Fallback = Pair(ip, port)
                }
            }
        }
        return ipv6Fallback ?: throw IllegalStateException("Ningún candidato ICE UDP válido encontrado")
    }

    private data class ParsedRemoteSdp(
        val ufrag: String,
        val pwd: String,
        val fingerprint: String,
        val videoSsrc: Long,
        val audioSsrc: Long,
        val videoPt: Int,
        val audioPt: Int
    )

    private fun parseRemoteSdpParameters(sdp: String): ParsedRemoteSdp {
        var ufrag = ""
        var pwd = ""
        var fp = ""
        var vSsrc = 1L
        var aSsrc = 2L
        var vPt = 96
        var aPt = 111
        var mediaSection = ""

        sdp.lines().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("m=video") -> {
                    mediaSection = "video"
                    val parts = trimmed.split(" ")
                    if (parts.size >= 4) vPt = parts[3].toIntOrNull() ?: 96
                }
                trimmed.startsWith("m=audio") -> {
                    mediaSection = "audio"
                    val parts = trimmed.split(" ")
                    if (parts.size >= 4) aPt = parts[3].toIntOrNull() ?: 111
                }
                trimmed.startsWith("a=ice-ufrag:") -> ufrag = trimmed.substringAfter("a=ice-ufrag:")
                trimmed.startsWith("a=ice-pwd:") -> pwd = trimmed.substringAfter("a=ice-pwd:")
                trimmed.startsWith("a=fingerprint:") -> fp = trimmed.substringAfter(" ")
                trimmed.startsWith("a=ssrc:") -> {
                    val ssrc = trimmed.substringAfter("a=ssrc:").substringBefore(" ").toLongOrNull() ?: 0L
                    if (mediaSection == "video" && vSsrc == 1L) vSsrc = ssrc
                    if (mediaSection == "audio" && aSsrc == 2L) aSsrc = ssrc
                }
            }
        }
        return ParsedRemoteSdp(ufrag, pwd, fp, vSsrc, aSsrc, vPt, aPt)
    }

    private fun buildNativeSdpOffer(ufrag: String, pwd: String, fingerprint: String, enableHevc: Boolean): String {
        return buildString {
            append("v=0\r\n")
            append("o=- 4611731400430051336 2 IN IP4 127.0.0.1\r\n")
            append("s=-\r\n")
            append("t=0 0\r\n")
            append("a=group:BUNDLE 0 1 2\r\n")
            append("a=msid-semantic: WMS\r\n")
            append("m=video 9 UDP/TLS/RTP/SAVPF 96 98")
            if (enableHevc) append(" 102")
            append("\r\n")
            append("c=IN IP4 0.0.0.0\r\n")
            append("a=ice-ufrag:$ufrag\r\n")
            append("a=ice-pwd:$pwd\r\n")
            append("a=fingerprint:sha-256 $fingerprint\r\n")
            append("a=setup:actpass\r\n")
            append("a=mid:0\r\n")
            append("a=recvonly\r\n")
            append("a=rtcp-mux\r\n")
            append("a=rtcp-rsize\r\n")
            append("a=rtpmap:96 H264/90000\r\n")
            append("a=fmtp:96 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=4d002a\r\n")
            append("a=rtpmap:98 H264/90000\r\n")
            append("a=fmtp:98 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f\r\n")
            if (enableHevc) {
                append("a=rtpmap:102 H265/90000\r\n")
            }
            append("m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n")
            append("c=IN IP4 0.0.0.0\r\n")
            append("a=ice-ufrag:$ufrag\r\n")
            append("a=ice-pwd:$pwd\r\n")
            append("a=fingerprint:sha-256 $fingerprint\r\n")
            append("a=setup:actpass\r\n")
            append("a=mid:1\r\n")
            append("a=sendrecv\r\n")
            append("a=rtcp-mux\r\n")
            append("a=rtpmap:111 opus/48000/2\r\n")
            append("a=fmtp:111 minptime=10;useinbandfec=1\r\n")
            append("m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n")
            append("c=IN IP4 0.0.0.0\r\n")
            append("a=ice-ufrag:$ufrag\r\n")
            append("a=ice-pwd:$pwd\r\n")
            append("a=fingerprint:sha-256 $fingerprint\r\n")
            append("a=setup:actpass\r\n")
            append("a=mid:2\r\n")
            append("a=sctp-port:5000\r\n")
        }
    }

    private suspend fun awaitBladeProvisioned(baseUri: String, sessionPath: String, gsToken: String, epoch: Long) {
        val stateUrl = "$baseUri/$sessionPath/state"
        repeat(60) {
            val res = executeSessionGet(stateUrl, gsToken)
            when (val st = res.optString("state", "")) {
                "Provisioned", "ReadyToConnect" -> return
                "Failed", "Canceled" -> throw IllegalStateException("Blade falló aprovisionamiento: $st")
                else -> delay(1000L)
            }
        }
        throw IllegalStateException("Timeout esperando aprovisionamiento del servidor de juego")
    }

    private suspend fun pollEndpointUntilPopulated(url: String, gsToken: String): JSONObject {
        repeat(40) {
            val res = executeSessionGet(url, gsToken)
            if (res.length() > 0 && !res.has("status")) return res
            delay(250L)
        }
        throw IllegalStateException("Timeout esperando respuesta SDP/ICE en $url")
    }

    private fun startAutonomousKeepalive(baseUri: String, sessionPath: String, gsToken: String, epoch: Long) {
        keepaliveRunning.set(true)
        keepaliveJob?.cancel()
        keepaliveJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val url = "$baseUri/$sessionPath/keepalive"
            while (keepaliveRunning.get() && isActive) {
                delay(25_000L)
                runCatching {
                    executeSessionPost(url, "{}", gsToken)
                    blackbox.record(epoch, ControlPlaneStage.KEEPALIVE_HEARTBEAT, 204, "KeepaliveAck")
                }
            }
        }
    }

    private fun generateRandomIceToken(len: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val sr = SecureRandom()
        return (1..len).map { alphabet[sr.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun executeSessionPost(
        urlStr: String,
        body: String,
        gsToken: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $gsToken")
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
        if (conn.responseCode == 204) return JSONObject()
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun executeSessionGet(urlStr: String, gsToken: String): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $gsToken")
        }
        if (conn.responseCode == 204) return JSONObject()
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}
