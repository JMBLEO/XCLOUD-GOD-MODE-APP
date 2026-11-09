package com.xcloud.godmode.engine

@JvmInline
value class LaunchAttemptId(val value: Long)

typealias EngineId = com.xcloud.godmode.models.EngineId

enum class EngineDetachReason {
    NORMAL_DISCONNECT,
    THERMAL_THROTTLING,
    SURFACE_DESTROYED,
    STREAM_ERROR,
    FATAL_CRASH,
    USER_EXIT,
    CANCELLED,
    SESSION_FAILED,
    REPLACED_BY_NEW_ATTEMPT
}

data class FrameSize(val width: Int, val height: Int)

data class NativeFrame(
    val timestampNs: Long,
    val frameIndex: Long,
    val size: FrameSize,
    val hardwareBufferHandle: Long? = null
)

data class FirstFrameResult(
    val presented: Boolean,
    val frameId: Long,
    val presentationTimestampNs: Long,
    val failureReason: String? = null
)

interface DirectSurfaceRendererContract {
    fun attachSurface(surface: Any)
    fun detachSurface(reason: EngineDetachReason)
    fun renderFrame(frame: NativeFrame): Boolean
}

interface SpatialUpscalePipeline {
    fun configureUpscaler(inputSize: FrameSize, outputSize: FrameSize): Boolean
    fun processFrame(source: NativeFrame): NativeFrame
}

interface EngineAttachment {
    val engineId: EngineId
    val attemptId: LaunchAttemptId
    val renderer: DirectSurfaceRendererContract? get() = null
    val upscalePipeline: SpatialUpscalePipeline? get() = null

    suspend fun awaitFirstFramePresented(timeoutMs: Long): FirstFrameResult
    fun stopSurfaceAccessImmediately()
    suspend fun detach(reason: EngineDetachReason)
}

data class DirectEngineAttachment(
    override val engineId: EngineId,
    override val attemptId: LaunchAttemptId = LaunchAttemptId(0L),
    override val renderer: DirectSurfaceRendererContract? = null,
    override val upscalePipeline: SpatialUpscalePipeline? = null
) : EngineAttachment {
    override suspend fun awaitFirstFramePresented(timeoutMs: Long): FirstFrameResult {
        return FirstFrameResult(
            presented = false,
            frameId = -1L,
            presentationTimestampNs = 0L,
            failureReason = "Motor no inicializado en producción"
        )
    }

    override fun stopSurfaceAccessImmediately() {
        renderer?.detachSurface(EngineDetachReason.SURFACE_DESTROYED)
    }

    override suspend fun detach(reason: EngineDetachReason) {
        renderer?.detachSurface(reason)
    }
}
