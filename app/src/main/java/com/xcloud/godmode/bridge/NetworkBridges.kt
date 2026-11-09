package com.xcloud.godmode.bridge

import com.xcloud.godmode.streaming.SessionHttpClient
import com.xcloud.godmode.streaming.SessionHttpResult
import com.xcloud.godmode.streaming.NanoBinaryTransportNegotiator
import com.xcloud.godmode.streaming.NanoNativeEngineRouter
import com.xcloud.godmode.engine.LaunchAttemptId
import com.xcloud.godmode.engine.StartupGate
import com.xcloud.godmode.engine.StreamStartupStateMachine
import com.xcloud.godmode.engine.EngineDetachReason
import com.xcloud.godmode.engine.EngineAttachment
import com.xcloud.godmode.engine.EngineId
import com.xcloud.godmode.engine.FirstFrameResult
import com.xcloud.godmode.engine.awaitValidSurface
import com.xcloud.godmode.streaming.NativeStreamBridge
import com.xcloud.godmode.streaming.NativeStreamPipelineManager
import com.xcloud.godmode.streaming.decoder.HardwareDecoderDescriptor
import com.xcloud.godmode.streaming.transport.NanoBinaryStreamDescriptor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import android.view.Surface

/**
 * Implementation of SessionHttpClient using OkHttp.
 */
class OkHttpSessionClient(private val okHttpClient: OkHttpClient) : SessionHttpClient {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun postJson(
        url: String,
        bearerToken: CharArray,
        body: String,
        headers: Map<String, String>
    ): SessionHttpResult = withContext(Dispatchers.IO) {
        val reqBuilder = Request.Builder()
            .url(url)
            .post(body.toRequestBody(jsonMediaType))
            .addHeader("Authorization", "Bearer ${String(bearerToken)}")

        headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }

        okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            val correlationId = response.header("X-MS-Correlation-Id") 
                ?: response.header("ms-cv")
                ?: response.header("X-MS-Session-Id")

            SessionHttpResult(
                statusCode = response.code,
                body = responseBody,
                correlationId = correlationId
            )
        }
    }

    override suspend fun getJson(
        url: String,
        bearerToken: CharArray,
        headers: Map<String, String>
    ): SessionHttpResult = withContext(Dispatchers.IO) {
        val reqBuilder = Request.Builder()
            .url(url)
            .get()
            .addHeader("Authorization", "Bearer ${String(bearerToken)}")

        headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }

        okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            val correlationId = response.header("X-MS-Correlation-Id") 
                ?: response.header("ms-cv")

            SessionHttpResult(
                statusCode = response.code,
                body = responseBody,
                correlationId = correlationId
            )
        }
    }
}

/**
 * Implementation of NanoBinaryTransportNegotiator using direct GSSV binary signaling.
 * Strictly Zero-WebRTC: No SDP, No ICE candidates exchange, No DTLS.
 */
class NanoGssvTransportNegotiator(
    private val httpClient: SessionHttpClient
) : NanoBinaryTransportNegotiator {
    private val TAG = "NanoTransport"

    override suspend fun establishDirectNanoTunnel(
        attemptId: LaunchAttemptId,
        baseRegionUri: String,
        sessionPath: String,
        gsToken: CharArray,
        negotiatedDecoder: HardwareDecoderDescriptor,
        startupGates: StreamStartupStateMachine
    ): Pair<String, Int> = withContext(Dispatchers.IO) {
        val baseUrl = baseRegionUri.trimEnd('/')
        
        // 1. Polling for Readiness (/state)
        var isReady = false
        var retryCount = 0
        while (!isReady && retryCount < 60) {
            val stateRes = httpClient.getJson(
                url = "$baseUrl/$sessionPath/state",
                bearerToken = gsToken,
                headers = emptyMap()
            )
            
            if (stateRes.statusCode != 200) {
                delay(1000)
                retryCount++
                continue
            }
            
            val json = JSONObject(stateRes.body)
            val state = json.optString("state")
            when (state) {
                "Provisioned", "Provisioning" -> {
                    if (state == "Provisioned") {
                        isReady = true
                    } else {
                        delay(1000)
                        retryCount++
                    }
                }
                "ReadyToConnect" -> {
                    httpClient.postJson(
                        url = "$baseUrl/$sessionPath/connect",
                        bearerToken = gsToken,
                        body = "{}",
                        headers = emptyMap()
                    )
                    delay(1000)
                    retryCount++
                }
                "Failed", "Cancelled" -> throw IllegalStateException("Session aborted by Xbox: $state")
                else -> {
                    delay(1000)
                    retryCount++
                }
            }
        }
        
        if (!isReady) throw IllegalStateException("Session provisioning timed out")
        startupGates.complete(attemptId, StartupGate.SESSION_PROVISIONED)

        // 2. Fetch Binary Configuration (/configuration)
        val configRes = httpClient.getJson(
            url = "$baseUrl/$sessionPath/configuration",
            bearerToken = gsToken,
            headers = emptyMap()
        )
        
        if (configRes.statusCode != 200) throw IllegalStateException("Failed to fetch NANO configuration: ${configRes.statusCode}")
        
        val configJson = JSONObject(configRes.body)
        val serverDetails = configJson.optJSONObject("serverDetails") 
            ?: throw IllegalStateException("Missing serverDetails in NANO config")
        
        val remoteIp = serverDetails.optString("ip").ifBlank { 
            serverDetails.optString("serverIp") 
        }
        val remotePort = serverDetails.optInt("port", serverDetails.optInt("serverPort", 9002))
        
        if (remoteIp.isNullOrBlank()) throw IllegalStateException("Invalid remote IP in NANO config")

        // 3. Extract SRTP keys
        val srtpObj = serverDetails.optJSONObject("srtp")
        val srtpKey = srtpObj?.optString("key")
        val srtpSalt = srtpObj?.optString("salt")
        if (!srtpKey.isNullOrBlank() && !srtpSalt.isNullOrBlank()) {
            NativeStreamBridge.injectDirectSrtpKeys(srtpKey, srtpSalt)
        }

        Pair(remoteIp, remotePort)
    }
}

/**
 * Implementation of NanoNativeEngineRouter to dispatch the stream to the native engines.
 */
class NanoHardwareEngineRouter(
    private val streamManager: NativeStreamPipelineManager
) : NanoNativeEngineRouter {
    
    override suspend fun attachNanoStreamToHardwareEngine(
        attemptId: LaunchAttemptId,
        engineId: EngineId,
        descriptor: NanoBinaryStreamDescriptor,
        surface: Surface
    ): EngineAttachment {
        
        val attachment = object : EngineAttachment {
            override val engineId: EngineId = engineId
            override val attemptId: LaunchAttemptId = attemptId

            override suspend fun awaitFirstFramePresented(timeoutMs: Long): FirstFrameResult {
                return streamManager.firstFramePresented
                    .filterNotNull()
                    .first()
            }

            override fun stopSurfaceAccessImmediately() {
                NativeStreamBridge.detachSurface()
            }

            override suspend fun detach(reason: EngineDetachReason) {
                withContext(NonCancellable) {
                    streamManager.stopStream()
                }
            }
        }

        NativeStreamBridge.startNativeReactor(
            remoteIp = descriptor.remoteHostIp,
            remotePort = descriptor.remoteUdpPort,
            remoteUfrag = "", 
            remotePwd = "",
            localUfrag = "",
            enableIPv6 = true
        )
        
        NativeStreamBridge.attachSurface(
            surface = surface,
            profile = descriptor.negotiatedDecoder.generation.ordinal,
            width = descriptor.renderInputWidth,
            height = descriptor.renderInputHeight
        )
        
        streamManager.startSessionServices()

        return attachment
    }
}
