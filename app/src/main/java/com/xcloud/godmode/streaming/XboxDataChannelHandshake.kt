package com.xcloud.godmode.streaming

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class XboxDataChannelHandshake(
    private val sendOnMessageChannel: (String) -> Unit,
    private val sendOnControlChannel: (String) -> Unit,
    private val sendClientMetadataOnInputChannel: () -> Unit
) {
    private val TAG = "XboxHandshake"
    private var currentAccessKey: String = "4BDB3609-C1F1-4195-9B37-FEFF45DA8B8E"
    private var isControlAuthorized = false

    fun setAccessKey(key: String) {
        if (key.isNotBlank()) {
            currentAccessKey = key
        }
    }

    /**
     * Paso 1 y 2: Cuando el canal 'control' abre, se DEBE enviar videoPreference (2560x1440 o resolución objetivo)
     * ANTES de authorizationRequest. Si se envía después o tras recibir frames, Azure bloquea la sesión a 720p/5Mbps.
     */
    fun onControlChannelOpened(
        accessKey: String = currentAccessKey,
        targetWidth: Int = 1920,
        targetHeight: Int = 1080
    ) {
        setAccessKey(accessKey)
        Log.i(TAG, "Control channel OPEN. Disparando secuencia de desbloqueo temprano (${targetWidth}x${targetHeight})...")

        // 1. videoPreference (Paso 1 OBLIGATORIO antes de authorizationRequest)
        val videoPref = JSONObject().apply {
            put("message", "videoPreference")
            put("width", targetWidth)
            put("height", targetHeight)
            put("fps", 60)
            put("videoCodec", "H264")
            put("colorSpaceFlags", 0)
        }
        sendOnControlChannel(videoPref.toString())

        // 2. authorizationRequest con accessKey dinámica
        val authReq = JSONObject().apply {
            put("message", "authorizationRequest")
            put("accessKey", currentAccessKey)
        }
        sendOnControlChannel(authReq.toString())
        isControlAuthorized = true

        // 3. Conectar virtualmente el mando 0
        val gamepadAdd = JSONObject().apply {
            put("message", "gamepadChanged")
            put("gamepadIndex", 0)
            put("wasAdded", true)
        }
        sendOnControlChannel(gamepadAdd.toString())

        // 4. Solicitar IDR Keyframe inicial
        requestImmediateKeyframe()
    }

    /**
     * Paso 3: Se debe llamar inmediatamente cuando el DataChannel 'message' cambia a OPEN.
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
     * Procesa respuestas en el canal 'message'.
     * Al recibir HandshakeAck, se despacha la configuración de dimensiones (Paso 4) y UI.
     */
    fun onMessageChannelReceived(rawText: String, targetWidth: Int = 1920, targetHeight: Int = 1080) {
        val msg = JSONObject(rawText)
        val type = msg.optString("type")

        if (type == "HandshakeAck") {
            // Paso 4: Fijar dimensiones personalizadas en 'message'
            sendSystemUiAndDimensionsConfiguration(targetWidth, targetHeight)
            // Asegurar que el canal de control también despierte si aún no se había autorizado
            if (!isControlAuthorized) {
                onControlChannelOpened(currentAccessKey, targetWidth, targetHeight)
            }
            // Enviar metadatos de input (V8 / 0x08)
            sendClientMetadataOnInputChannel()
            // Solicitar keyframe tras confirmación
            requestImmediateKeyframe()
        } else if (type == "TransactionStart") {
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

    fun sendSystemUiAndDimensionsConfiguration(width: Int, height: Int) {
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

        // Resolution Pinning con soporte de resolución personalizada (1440p / 1080p)
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

    fun wakeUpVideoAndGamepadOnControlChannel(width: Int = 1920, height: Int = 1080) {
        onControlChannelOpened(currentAccessKey, width, height)
    }

    fun requestImmediateKeyframe() {
        val keyframeReq = JSONObject().apply {
            put("message", "videoKeyframeRequested")
            put("ifrRequested", true)
        }
        sendOnControlChannel(keyframeReq.toString())
    }
}

