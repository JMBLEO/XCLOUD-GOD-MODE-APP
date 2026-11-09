package com.xcloud.godmode.engine

import com.xcloud.godmode.models.OfferedVideoFormat

enum class UpscaleMode {
    NATIVE,
    SPATIAL_EDGE_RECONSTRUCTION,
    SPATIAL_CONTRAST_SHARPENING,
    TEMPORAL_ML_FULL,
    TEMPORAL_ML_LITE
}

sealed interface UpscaleFrameInputs {
    val colorHandle: Long
    val presentationTimestampNs: Long

    data class Spatial(
        override val colorHandle: Long,
        override val presentationTimestampNs: Long
    ) : UpscaleFrameInputs {
        init {
            require(colorHandle != 0L) {
                "El handle de color no puede ser nulo."
            }
            require(presentationTimestampNs >= 0L) {
                "El timestamp de presentación no puede ser negativo."
            }
        }
    }

    data class Temporal(
        override val colorHandle: Long,
        override val presentationTimestampNs: Long,
        val depthHandle: Long,
        val motionVectorHandle: Long,
        val jitterX: Float,
        val jitterY: Float,
        val reactiveMaskHandle: Long? = null,
        val compositionMaskHandle: Long? = null
    ) : UpscaleFrameInputs {
        init {
            require(colorHandle != 0L) {
                "El handle de color no puede ser nulo."
            }
            require(depthHandle != 0L) {
                "El handle de profundidad no puede ser nulo."
            }
            require(motionVectorHandle != 0L) {
                "El handle de vectores de movimiento no puede ser nulo."
            }
            require(jitterX.isFinite() && jitterY.isFinite()) {
                "El jitter debe contener valores finitos."
            }
            require(presentationTimestampNs >= 0L) {
                "El timestamp de presentación no puede ser negativo."
            }
            require(reactiveMaskHandle == null || reactiveMaskHandle != 0L) {
                "La máscara reactiva no puede utilizar el handle cero."
            }
            require(
                compositionMaskHandle == null ||
                    compositionMaskHandle != 0L
            ) {
                "La máscara de composición no puede utilizar el handle cero."
            }
        }
    }
}

sealed interface UpscaleAvailability {
    data object Available : UpscaleAvailability

    data class Unavailable(
        val reason: String
    ) : UpscaleAvailability
}

sealed interface UpscaleConfigureResult {
    data object Configured : UpscaleConfigureResult

    data class Rejected(
        val reason: String
    ) : UpscaleConfigureResult
}

sealed interface UpscaleProcessResult {
    data class Processed(
        val presentationTimestampNs: Long
    ) : UpscaleProcessResult

    data class Failed(
        val reason: String
    ) : UpscaleProcessResult
}

interface UpscaleAdapterAvailability {
    fun isImplemented(mode: UpscaleMode): Boolean
}

class ProductionUpscaleAdapterAvailability :
    UpscaleAdapterAvailability {
    override fun isImplemented(
        mode: UpscaleMode
    ): Boolean {
        return mode == UpscaleMode.NATIVE
    }
}

interface TemporalUpscaleAdapterAvailability {
    fun isAvailable(mode: UpscaleMode): Boolean
}

class ProductionTemporalUpscaleAdapterAvailability :
    TemporalUpscaleAdapterAvailability {
    override fun isAvailable(mode: UpscaleMode): Boolean = false
}

interface UpscalePipeline {
    fun availability(
        mode: UpscaleMode,
        format: OfferedVideoFormat,
        inputs: UpscaleFrameInputs
    ): UpscaleAvailability

    fun configure(
        mode: UpscaleMode,
        inputWidth: Int,
        inputHeight: Int,
        outputWidth: Int,
        outputHeight: Int
    ): UpscaleConfigureResult

    fun process(
        inputs: UpscaleFrameInputs
    ): UpscaleProcessResult

    fun release()
}

class UpscaleCompatibilityGate(
    private val adapterAvailability: UpscaleAdapterAvailability,
    private val temporalAdapterAvailability: TemporalUpscaleAdapterAvailability
) {
    fun validate(
        mode: UpscaleMode,
        inputs: UpscaleFrameInputs
    ): UpscaleAvailability {
        if (inputs.colorHandle == 0L) {
            return UpscaleAvailability.Unavailable(
                reason = "COLOR_INPUT_UNAVAILABLE: " +
                    "No existe una entrada de color válida."
            )
        }

        return when (mode) {
            UpscaleMode.NATIVE -> {
                UpscaleAvailability.Available
            }
            UpscaleMode.SPATIAL_EDGE_RECONSTRUCTION,
            UpscaleMode.SPATIAL_CONTRAST_SHARPENING -> {
                if (!adapterAvailability.isImplemented(mode)) {
                    UpscaleAvailability.Unavailable(
                        reason = "SPATIAL_UPSCALE_ADAPTER_UNAVAILABLE: " +
                            "El escalador espacial seleccionado " +
                            "todavía no está implementado."
                    )
                } else {
                    UpscaleAvailability.Available
                }
            }
            UpscaleMode.TEMPORAL_ML_FULL,
            UpscaleMode.TEMPORAL_ML_LITE -> {
                if (inputs !is UpscaleFrameInputs.Temporal) {
                    return UpscaleAvailability.Unavailable(
                        reason = "TEMPORAL_UPSCALE_INPUTS_UNAVAILABLE: " +
                            "El flujo no entrega profundidad, vectores " +
                            "de movimiento y jitter verificables."
                    )
                }

                if (!temporalAdapterAvailability.isAvailable(mode)) {
                    return UpscaleAvailability.Unavailable(
                        reason = "TEMPORAL_UPSCALE_ADAPTER_UNAVAILABLE: " +
                            "No existe un adaptador temporal compatible."
                    )
                }

                UpscaleAvailability.Available
            }
        }
    }
}
