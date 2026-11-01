package com.xcloud.godmode.streaming

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class XboxDataChannelHandshake(
    private val sendOnMessageChannel: (String) -> Unit,
    private val sendOnControlChannel: (String) -> Unit,
    private val sendClientMetadataOnInputChannel: () -> Unit
) {
    /**
     * Se debe llamar inmediatamente cuando el DataChannel 'message' (protocolo "messageV1") cambia a estado OPEN.
     * Sin este handshake, el servidor GSSV mantiene el video en negro (0 kbps).
     */
    fun onMessageChannelOpened() {
        val handshakeFrame = JSONObject().apply {
            put("type", "Handshake")
            put("version", "messageV1")
            put("id", UUID.randomUUID().toString())
            put("cv", "0")
        }
        sendOnMessageChannel(handshakeFrame.toString())
    }

    /**
     * Se debe llamar cada vez que llega un mensaje de texto por el DataChannel 'message'.
     * Responde a las transacciones del servidor y dispara el arranque del canal 'control'.
     */
    fun onMessageChannelReceived(rawText: String, targetWidth: Int = 1920, targetHeight: Int = 1080) {
        val msg = JSONObject(rawText)
        val type = msg.optString("type")

        if (type == "HandshakeAck") {
            // 1. Notificar configuración de UI del sistema y dimensiones fijadas (Resolution Pinning estilo green-nx)
            sendSystemUiAndDimensionsConfiguration(targetWidth, targetHeight)
            // 2. Despertar el flujo de video en el canal 'control'
            wakeUpVideoAndGamepadOnControlChannel()
            // 3. Enviar el reporte inicial de metadatos binarios (0x08) en el canal 'input'
            sendClientMetadataOnInputChannel()
        } else if (type == "TransactionStart") {
            // El servidor de Xbox exige un TransactionComplete con el mismo 'id' y 'cv'
            val txId = msg.optString("id")
            val cv = msg.optString("cv", "0")
            val ack = JSONObject().apply {
                put("type", "TransactionComplete")
                put("content", "{}")
                put("id", txId)
                put("cv", cv)
            }
            sendOnMessageChannel(ack.toString())
        }
    }

    private fun sendSystemUiAndDimensionsConfiguration(width: Int, height: Int) {
        val uiConfigMsg = JSONObject().apply {
            put("type", "Message")
            put("id", UUID.randomUUID().toString())
            put("target", "/streaming/systemUi/configuration")
            put("cv", "0")
            put("content", JSONObject().apply {
                put("systemUis", JSONArray(listOf(10, 19, 31, 27, 32, -41, -40, -44)))
                put("version", JSONArray(listOf(0, 2, 0)))
            }.toString())
        }
        sendOnMessageChannel(uiConfigMsg.toString())

        // Resolution Pinning (Evita que el servidor salte a 1440p sin aviso)
        val dimensionsMsg = JSONObject().apply {
            put("type", "Message")
            put("id", UUID.randomUUID().toString())
            put("target", "/streaming/characteristics/dimensionschanged")
            put("cv", "0")
            put("content", JSONObject().apply {
                put("horizontal", width)
                put("vertical", height)
                put("preferredWidth", width)
                put("preferredHeight", height)
                put("safeAreaLeft", 0)
                put("safeAreaTop", 0)
                put("safeAreaRight", width)
                put("safeAreaBottom", height)
                put("supportsCustomResolution", true)
            }.toString())
        }
        sendOnMessageChannel(dimensionsMsg.toString())
    }

    /**
     * Envía la secuencia de arranque en el DataChannel 'control' (protocolo "controlV1")
     * que fuerza al codificador H.264 del servidor de Xbox a emitir el primer IDR Keyframe + SPS/PPS.
     */
    fun wakeUpVideoAndGamepadOnControlChannel() {
        // 1. Autorización del canal de control
        val authReq = JSONObject().apply {
            put("message", "authorizationRequest")
            put("accessKey", "4BDB3609-C1F1-4195-9B37-FEFF45DA8B8E")
        }
        sendOnControlChannel(authReq.toString())

        // 2. Conectar virtualmente el mando 0
        val gamepadAdd = JSONObject().apply {
            put("message", "gamepadChanged")
            put("gamepadIndex", 0)
            put("wasAdded", true)
        }
        sendOnControlChannel(gamepadAdd.toString())

        // 3. Solicitar el primer IDR Keyframe (SPS + PPS + IDR Slice)
        requestImmediateKeyframe()
    }

    fun requestImmediateKeyframe() {
        val keyframeReq = JSONObject().apply {
            put("message", "videoKeyframeRequested")
            put("ifrRequested", true)
        }
        sendOnControlChannel(keyframeReq.toString())
    }
}
