package com.xcloud.godmode.engine

import com.xcloud.godmode.models.EngineId
import com.xcloud.godmode.models.EngineReadiness

interface EngineAvailability {
    fun readiness(engineId: EngineId): EngineReadiness
}

class ProductionEngineAvailability : EngineAvailability {
    override fun readiness(engineId: EngineId): EngineReadiness {
        return EngineReadiness.UNAVAILABLE
    }
}

sealed interface NativeEngineGateResult {
    data class Available(
        val engineId: EngineId
    ) : NativeEngineGateResult

    data class Unavailable(
        val reason: String
    ) : NativeEngineGateResult
}

class NativeEngineGate(
    private val availability: EngineAvailability
) {
    fun resolve(
        candidates: Collection<EngineId>
    ): NativeEngineGateResult {
        val engine = candidates.firstOrNull { candidate ->
            availability.readiness(candidate) == EngineReadiness.IMPLEMENTED
        }

        return if (engine != null) {
            NativeEngineGateResult.Available(engine)
        } else {
            NativeEngineGateResult.Unavailable(
                reason = "NATIVE_ENGINE_UNAVAILABLE: " +
                    "No existe un motor nativo implementado con presentación verificable."
            )
        }
    }

    fun verifyAndAcquireEngine(engineId: EngineId, attemptId: LaunchAttemptId = LaunchAttemptId(0L)): EngineAttachment {
        val readiness = availability.readiness(engineId)
        if (readiness != EngineReadiness.IMPLEMENTED) {
            throw NativeEngineUnavailableException(
                "NATIVE_ENGINE_UNAVAILABLE: El motor $engineId no está implementado en la compilación de producción."
            )
        }
        throw NativeEngineUnavailableException(
            "NATIVE_ENGINE_UNAVAILABLE: Instancia de motor no inicializada para $engineId."
        )
    }
}

class NativeEngineUnavailableException(message: String) : IllegalStateException(message)
