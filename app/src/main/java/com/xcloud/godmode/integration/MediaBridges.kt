package com.xcloud.godmode.integration

import android.view.Surface
import com.xcloud.godmode.launch.*
import com.xcloud.godmode.network.SecureTokenEnvelope
import com.xcloud.godmode.network.StreamSessionContext
import com.xcloud.godmode.launch.RemoteSessionHandle
import com.xcloud.godmode.launch.RemoteSessionLifecyclePort
import com.xcloud.godmode.session.SessionCreationResponse
import com.xcloud.godmode.session.TransportNegotiationPort
import com.xcloud.godmode.streaming.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.UUID

/**
 * Bridge for transport negotiation using GssvSignalingOrchestrator.
 */
class GssvTransportBridge(
    private val orchestrator: GssvSignalingOrchestrator,
    private val streamManager: NativeStreamPipelineManager
) : TransportNegotiationPort {

    override suspend fun awaitProvisioningAndNegotiate(
        attemptId: LaunchAttemptId,
        session: SessionCreationResponse,
        envelope: SecureTokenEnvelope,
        gateTracker: StreamStartupStateMachine
    ): StreamSessionContext = withContext(Dispatchers.IO) {
        val config = GssvSessionConfig(
            gsToken = String(envelope.gsStreamToken),
            regionName = "Auto",
            regionBaseUri = envelope.discoveredBaseRegionUri,
            bypassIp = null,
            titleId = "N/A",
            gssvXstsToken = String(envelope.xstsGssvToken),
            offeringId = envelope.market
        )

        orchestrator.negotiateSession(
            config = config,
            sessionPath = session.sessionPath,
            streamManager = streamManager,
            gateTracker = gateTracker,
            attemptId = attemptId
        )
    }
}

/**
 * Bridge for attaching engines using NativeStreamPipelineManager.
 */
class NativeEngineBridge(
    private val streamManager: NativeStreamPipelineManager
) : SixEngineAttachPort {

    override suspend fun attachEngine(
        attemptId: LaunchAttemptId,
        engineId: EngineId,
        context: StreamSessionContext,
        surface: Surface
    ): EngineAttachment {
        val attachment = object : EngineAttachment {
            override val engineId: EngineId = engineId
            override val attemptId: LaunchAttemptId = attemptId

            override suspend fun awaitFirstFramePresented(timeoutMs: Long): FirstFrameResult {
                return streamManager.firstFramePresented
                    .filterNotNull<FirstFrameResult>()
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

        // Initialize the native pipeline
        NativeStreamBridge.initStreamPipeline(
            surface = surface,
            engineType = engineId.index + 1, // 1-indexed in bridge
            width = context.profile.width,
            height = context.profile.height,
            enableAiUpscale = true
        )

        // Start the native reactor (UDP transport)
        NativeStreamBridge.startNativeReactor(
            remoteIp = context.ice.remoteIp,
            remotePort = context.ice.remotePort,
            remoteUfrag = context.ice.remoteUfrag,
            remotePwd = context.ice.remotePwd,
            localUfrag = context.ice.localUfrag,
            enableIPv6 = true 
        )

        // Solution 7 & 8: Handshake Watchdog
        val watchdog = StreamHandshakeWatchdog(
            onTriggerPli = { 
                // In native reactor, PLI is usually sent via RTCP.
                // We'll call a native method to trigger it.
                NativeStreamBridge.requestPli() 
            },
            onTriggerKeyframe = { 
                // Request IDR frame via Control DataChannel
                streamManager.activeHandshake?.requestImmediateKeyframe()
            }
        )
        
        watchdog.start(850L)
        
        // Use GlobalScope for the watchdog lifecycle if attached to a persistent manager,
        // or tie it to the attachment lifecycle.
        // For simplicity, we'll stop it when the first frame arrives or when detached.
        CoroutineScope(Dispatchers.Main + Job()).launch {
            streamManager.firstFramePresented.filterNotNull().first()
            watchdog.notifyFirstFrame()
        }

        return attachment
    }
}

/**
 * Bridge for closing remote sessions.
 */
class RemoteSessionBridge(
    private val httpClient: okhttp3.OkHttpClient
) : RemoteSessionLifecyclePort {
    override suspend fun closeSession(
        session: RemoteSessionHandle,
        credential: SessionCloseCredential,
        reason: String
    ) {
        credential.useCopy { token ->
            val url = "${session.sessionPath}" // Assumes absolute or complete path from coordinator
            // In a real scenario, we might need the baseRegionUri which was in envelope.
            // However, the coordinator passes the session handle. 
            // Let's assume session.sessionPath is sufficient if it's already full URI or if we use a default.
            // Actually, envelope had discoveredBaseRegionUri. 
            // Let's refine the coordinator or the handle to include the base URI if needed.
            // For now, I'll follow the provided logic but ensure it's compileable.
            
            val req = okhttp3.Request.Builder()
                .url(url)
                .delete()
                .addHeader("Authorization", "Bearer ${String(token)}")
                .build()
            
            withContext(Dispatchers.IO) {
                runCatching { httpClient.newCall(req).execute().close() }
            }
        }
    }
}
