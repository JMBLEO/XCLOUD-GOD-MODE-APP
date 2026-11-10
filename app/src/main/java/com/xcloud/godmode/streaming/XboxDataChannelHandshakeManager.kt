package com.xcloud.godmode.streaming

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object XboxDataChannelHandshakeManager {

    /**
     * SUBPROCESO 8: Tramas iniciales obligatorias al abrirse el canal "message" (messageV1).
     */
    fun buildMessageChannelHandshakePayloads(): List<String> {
        val systemUiPayload = JSONObject().apply {
            put("type", "Handshake")
            put("version", "messageV1")
            put("id", UUID.randomUUID().toString())
            put("cv", "")
        }.toString()

        val uiConfigTransaction = JSONObject().apply {
            put("type", "Message")
            put("id", UUID.randomUUID().toString())
            put("target", "/streaming/systemUi/configuration")
            put("content", JSONObject().apply {
                put("version", JSONArray().put(0).put(2).put(0))
                // 10=ShowApplication, 19=ShowMessageDialog, 27=ShowPurchase, 31=ShowVirtualKeyboard, 32=ShowAccountPicker
                put("systemUis", JSONArray(listOf(10, 19, 31, 27, 32, -41, -40, -44)))
            }.toString())
            put("cv", "")
        }.toString()

        return listOf(systemUiPayload, uiConfigTransaction)
    }

    /**
     * SUBPROCESO 9: Tramas iniciales obligatorias al abrirse el canal "control" (controlV1).
     * Conecta el mando virtual/físico en la ranura 0 y fuerza el envío inmediato de un I-Frame (IDR).
     */
    fun buildControlChannelInitPayloads(): List<String> {
        val authMsg = JSONObject().apply {
            put("message", "authorizationRequest")
            put("accessKey", UUID.randomUUID().toString().uppercase())
        }.toString()

        val gamepadConnectedMsg = JSONObject().apply {
            put("message", "gamepadChanged")
            put("gamepadIndex", 0)
            put("wasAdded", true)
        }.toString()

        val requestKeyframeMsg = JSONObject().apply {
            put("message", "videoKeyframeRequested")
            put("ifrRequested", true)
        }.toString()

        return listOf(authMsg, gamepadConnectedMsg, requestKeyframeMsg)
    }

    /**
     * SUBPROCESO 10: Paquete binario ClientMetadata (Tipo 0x0001) en respuesta al ServerMetadata (0x0008)
     * en el canal "input". Sin estos 5 bytes iniciales, Xbox ignora todas las tramas del mando.
     */
    fun buildInputClientMetadataPacket(maxTouchPoints: Byte = 10): ByteArray {
        return ByteBuffer.allocate(5).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putShort(0x0001) // MessageType: ClientMetadataReport
            put(maxTouchPoints)
            putShort(0x0000) // Padding / Reserved
        }.array()
    }
}
