package com.xcloud.godmode.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Phase 69: Vehicle Physics Telemetry & Direct Haptic Driver.
 * Parses racing telemetry UDP packets (Forza Motorsport / Horizon standard 324-byte 'sled' and 'dash' format)
 * and translates RPM, tire slip ratio, and suspension travel directly into
 * DualSense adaptive trigger resistance and Xbox impulse trigger ABS vibration.
 */
class VehiclePhysicsTelemetryReceiver {

    companion object {
        const val TELEMETRY_UDP_PORT = 5300
        const val FORZA_DATA_PACKET_SIZE = 324
    }

    data class TelemetryState(
        val isRaceOn: Boolean,
        val engineMaxRpm: Float,
        val engineIdleRpm: Float,
        val currentEngineRpm: Float,
        val speedMps: Float,
        val gear: Int,
        val tireSlipRatioFrontLeft: Float,
        val tireSlipRatioFrontRight: Float,
        val tireSlipRatioRearLeft: Float,
        val tireSlipRatioRearRight: Float,
        val accelTriggerHapticIntensity: Float, // 0.0 .. 1.0 (Traction loss / revs)
        val brakeTriggerHapticIntensity: Float  // 0.0 .. 1.0 (ABS locking)
    )

    /**
     * Parses standard 324-byte Forza telemetry UDP datagram.
     */
    fun parseForzaPacket(data: ByteArray, length: Int): TelemetryState? {
        if (length < 232) return null // Need at least basic sled/dash fields

        val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)

        val isRaceOn = buffer.getInt(0) != 0
        // Offset 8: EngineMaxRpm (float)
        val maxRpm = buffer.getFloat(8)
        // Offset 12: EngineIdleRpm (float)
        val idleRpm = buffer.getFloat(12)
        // Offset 16: CurrentEngineRpm (float)
        val currentRpm = buffer.getFloat(16)

        // Offsets 88..100: Tire Slip Ratios (FL, FR, RL, RR)
        val slipFL = if (length >= 104) kotlin.math.abs(buffer.getFloat(88)) else 0f
        val slipFR = if (length >= 104) kotlin.math.abs(buffer.getFloat(92)) else 0f
        val slipRL = if (length >= 104) kotlin.math.abs(buffer.getFloat(96)) else 0f
        val slipRR = if (length >= 104) kotlin.math.abs(buffer.getFloat(100)) else 0f

        // Offset 244: Speed m/s (in dash mode, if available)
        val speed = if (length >= 248) buffer.getFloat(244) else 0f
        val gear = if (length >= 249) buffer.get(248).toInt() else 1

        // Calculate brake lock intensity (high front slip under braking)
        val maxFrontSlip = kotlin.math.max(slipFL, slipFR)
        val brakeHaptic = when {
            maxFrontSlip > 1.0f -> 1.0f // Heavy lockup -> rapid ABS pulse
            maxFrontSlip > 0.4f -> (maxFrontSlip - 0.4f) / 0.6f
            else -> 0f
        }

        // Calculate accelerator slip intensity (rear wheel spin)
        val maxRearSlip = kotlin.math.max(slipRL, slipRR)
        val accelHaptic = when {
            maxRearSlip > 0.8f -> 1.0f
            maxRearSlip > 0.3f -> (maxRearSlip - 0.3f) / 0.5f
            else -> 0f
        }

        return TelemetryState(
            isRaceOn = isRaceOn,
            engineMaxRpm = maxRpm,
            engineIdleRpm = idleRpm,
            currentEngineRpm = currentRpm,
            speedMps = speed,
            gear = gear,
            tireSlipRatioFrontLeft = slipFL,
            tireSlipRatioFrontRight = slipFR,
            tireSlipRatioRearLeft = slipRL,
            tireSlipRatioRearRight = slipRR,
            accelTriggerHapticIntensity = accelHaptic.coerceIn(0f, 1f),
            brakeTriggerHapticIntensity = brakeHaptic.coerceIn(0f, 1f)
        )
    }
}
