package com.xcloud.godmode.bridge

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * XCloudInputPacketBuilder: Serializes gamepad events into binary packets for the 'input' DataChannel.
 */
object XCloudInputPacketBuilder {

    /**
     * Binary packaging of button events and joysticks for the 'input' DataChannel.
     */
    fun buildGamepadStartPacket(
        buttonsMask: Int,
        leftThumbX: Short,
        leftThumbY: Short,
        rightThumbX: Short,
        rightThumbY: Short,
        leftTrigger: Short,
        rightTrigger: Short
    ): ByteArray {
        val buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x01.toByte())
        buf.put(0.toByte())
        buf.putShort(buttonsMask.toShort())
        buf.putShort(leftThumbX)
        buf.putShort(leftThumbY)
        buf.putShort(rightThumbX)
        buf.putShort(rightThumbY)
        
        buf.put((leftTrigger / 4).toInt().toByte())
        buf.put((rightTrigger / 4).toInt().toByte())
        
        buf.put(0.toByte())
        buf.put(0.toByte())
        return buf.array()
    }
}
