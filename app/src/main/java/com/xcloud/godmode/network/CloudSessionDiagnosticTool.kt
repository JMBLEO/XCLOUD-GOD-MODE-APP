package com.xcloud.godmode.network

import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Diagnostic record capturing the complete HTTP response from the cloud gaming backend
 * when an entitlement or session rejection occurs (e.g. HTTP 400 NoEntitlement).
 */
data class NoEntitlementDiagnosticEntry(
    val timestampElapsedMs: Long,
    val timestampWallMs: Long,
    val httpStatusCode: Int,
    val requestedTitleId: String,
    val sentTitleId: String,
    val offeringId: String,
    val authStatusCode: String,
    val errorCode: String?,
    val serverMessage: String?,
    val correlationId: String?,
    val requestUrl: String,
    val region: String,
    val rawResponseBody: String,
    val formattedDiagnosticLog: String
)

/**
 * CloudSessionDiagnosticTool: Diagnostic tool in the authentication and session flow that
 * captures and logs the complete HTTP response from the cloud gaming backend when a 400 NoEntitlement
 * error occurs, specifically extracting titleId, offeringId, and AuthStatus to identify why
 * the session request is being rejected.
 */
object CloudSessionDiagnosticTool {

    private const val TAG = "CloudSessionDiag"
    private val history = ArrayDeque<NoEntitlementDiagnosticEntry>(32)

    /**
     * Recursively traverses a JSONObject to extract values for candidate keys.
     */
    fun findStringRecursively(json: JSONObject?, vararg candidateKeys: String): String? {
        if (json == null) return null
        for (key in candidateKeys) {
            val direct = json.optString(key)
            if (direct.isNotBlank() && direct != "null") return direct
        }

        val iterator = json.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            when (val value = json.opt(key)) {
                is JSONObject -> {
                    findStringRecursively(value, *candidateKeys)?.let { return it }
                }
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        val item = value.optJSONObject(i) ?: continue
                        findStringRecursively(item, *candidateKeys)?.let { return it }
                    }
                }
            }
        }
        return null
    }

    /**
     * Extracts titleId, offeringId, and AuthStatus from the complete HTTP response
     * when a session creation request fails (e.g. HTTP 400 NoEntitlement).
     */
    @Synchronized
    fun captureAndLogSessionFailure(
        httpStatusCode: Int,
        requestUrl: String,
        requestedTitleId: String,
        sentTitleId: String,
        offeringId: String,
        region: String,
        rawResponseBody: String,
        correlationId: String? = null
    ): NoEntitlementDiagnosticEntry {
        val json = runCatching { JSONObject(rawResponseBody) }.getOrNull()

        // Extract AuthStatus specifically (e.g. "NoEntitlement", "UserNotEntitled", etc.)
        val authStatus = findStringRecursively(json, "authStatusCode", "AuthStatus", "authStatus")
            ?: if (rawResponseBody.contains("NoEntitlement", ignoreCase = true)) "NoEntitlement" else "Unknown"

        val errorCode = findStringRecursively(json, "code", "errorCode", "error") ?: "HTTP_$httpStatusCode"
        val serverMessage = findStringRecursively(json, "message", "description", "error_description")
            ?: rawResponseBody.take(250)

        val resolvedCorrId = correlationId
            ?: findStringRecursively(json, "correlationId", "CorrelationId", "x-ms-correlation-id")
            ?: "N/A"

        val isNameSentAsTitleId = sentTitleId.equals(requestedTitleId, ignoreCase = true) &&
                !sentTitleId.all { it.isDigit() } && sentTitleId.length > 12

        val logMessage = buildString {
            appendLine("╔══════════════════════════════════════════════════════════════════════╗")
            appendLine("║  🚨 [DIAGNÓSTICO XCLOUD CONTROL PLANE] HTTP 400 NoEntitlement / Reject")
            appendLine("╠══════════════════════════════════════════════════════════════════════╣")
            appendLine("║ • Title ID Solicitado: $requestedTitleId")
            appendLine("║ • Title ID Enviado al Backend: $sentTitleId")
            appendLine("║ • Offering ID (Pool de Licencias): $offeringId")
            appendLine("║ • AuthStatus Extraído: $authStatus")
            appendLine("║ • Código de Error: $errorCode")
            appendLine("║ • Mensaje del Servidor: $serverMessage")
            appendLine("║ • URL Destino: $requestUrl")
            appendLine("║ • Región Cloud: $region")
            appendLine("║ • Correlation ID: $resolvedCorrId")
            appendLine("║──────────────────────────────────────────────────────────────────────║")
            appendLine("║ • DIAGNÓSTICO DE CAUSA RAÍZ:")
            if (isNameSentAsTitleId) {
                appendLine("║   [!] Se envió un nombre/slug ('$sentTitleId') en lugar del TitleID.")
                appendLine("║       El backend xCloud requiere el TitleID numérico (ej. 1716328221)")
                appendLine("║       o el ProductID oficial de 12 caracteres del catálogo xCloud.")
            } else if (authStatus.equals("NoEntitlement", ignoreCase = true)) {
                appendLine("║   [!] Rechazo por falta de licencia para el título en pool '$offeringId'.")
                appendLine("║       Posibles causas:")
                appendLine("║       1) La cuenta no tiene suscripción activa a Xbox Game Pass Ultimate.")
                appendLine("║       2) El título no forma parte del catálogo activo de Xbox Cloud Gaming.")
                appendLine("║       3) Si es título BYOG ('Juega tus propios juegos'), la cuenta debe")
                appendLine("║          poseer la compra digital verificada en este gamertag.")
            } else {
                appendLine("║   [!] El servidor rechazó la solicitud con estado $authStatus.")
            }
            appendLine("║──────────────────────────────────────────────────────────────────────║")
            appendLine("║ • RESPUESTA HTTP COMPLETA DEL BACKEND:")
            appendLine("║ $rawResponseBody")
            appendLine("╚══════════════════════════════════════════════════════════════════════╝")
        }

        val entry = NoEntitlementDiagnosticEntry(
            timestampElapsedMs = SystemClock.elapsedRealtime(),
            timestampWallMs = System.currentTimeMillis(),
            httpStatusCode = httpStatusCode,
            requestedTitleId = requestedTitleId,
            sentTitleId = sentTitleId,
            offeringId = offeringId,
            authStatusCode = authStatus,
            errorCode = errorCode,
            serverMessage = serverMessage,
            correlationId = resolvedCorrId,
            requestUrl = requestUrl,
            region = region,
            rawResponseBody = rawResponseBody,
            formattedDiagnosticLog = logMessage
        )

        if (history.size >= 32) history.removeFirst()
        history.addLast(entry)

        Log.e(TAG, logMessage)
        return entry
    }

    @Synchronized
    fun getLatestReport(): NoEntitlementDiagnosticEntry? = history.lastOrNull()

    @Synchronized
    fun getHistory(): List<NoEntitlementDiagnosticEntry> = history.toList()

    @Synchronized
    fun clearHistory() {
        history.clear()
    }
}
