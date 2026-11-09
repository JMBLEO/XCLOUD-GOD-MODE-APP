package com.xcloud.godmode.streaming

import android.view.Surface
import com.xcloud.godmode.engine.EngineId
import com.xcloud.godmode.network.StreamSessionContext

/**
 * Interfaz unificada de los motores nativos (Soporta exclusivamente Project Nano, MediaCodec, Vulkan Video y FSR 1.0).
 * CERO WebRTC. CERO FFmpeg.
 */
interface CertifiedNativeStreamEngine {
    val engineId: EngineId

    suspend fun initializeEngine(mediaContext: StreamSessionContext, width: Int, height: Int): Boolean
    suspend fun bindSurface(surface: Surface): Boolean
    suspend fun awaitFirstFrame(timeoutMs: Long): Boolean
    fun stopSurfaceAccessDirectly()
    suspend fun teardownEngine()
}

class CertifiedEngineRouter(
    private val hardwareDecoders: Map<EngineId, CertifiedNativeStreamEngine>
) {
    suspend fun selectAndAttachEngine(
        preferredId: EngineId,
        context: StreamSessionContext,
        width: Int,
        height: Int,
        surface: Surface
    ): CertifiedNativeStreamEngine {
        val engine = hardwareDecoders[preferredId] ?: hardwareDecoders.values.first()
        
        val initOk = engine.initializeEngine(context, width, height)
        check(initOk) { "El motor ${engine.engineId.name} rechazó la inicialización por incompatibilidad de hardware" }

        val boundOk = engine.bindSurface(surface)
        check(boundOk) { "El motor ${engine.engineId.name} no pudo asociar la Surface nativa" }

        return engine
    }
}
