package com.xcloud.godmode.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Layer 5: Dynamic Patcher
 * Periodically fetches remote configuration overrides (JSON) to patch XboxEndpoints dynamically.
 * This allows the app to bypass regional blocks or update API endpoints without a full app update.
 */
class DynamicPatcherWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val TAG = "DynamicPatcher"
    private val client = OkHttpClient()

    override suspend fun doWork(): Result {
        Log.d(TAG, "Dynamic Patcher: Fetching remote endpoint overrides...")

        try {
            // URL pointing to a secure remote config (GitHub Gist or similar)
            // Note: This is an OTA update channel for dynamic regional bypasses.
            val remoteUrl = "https://gist.githubusercontent.com/xcloud-godmode/overrides/raw/endpoints.json"
            
            val request = Request.Builder()
                .url(remoteUrl)
                .header("Cache-Control", "no-cache")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val jsonStr = response.body?.string() ?: "{}"
                    if (jsonStr.trim().startsWith("{")) {
                        val json = JSONObject(jsonStr)
                        // Patch XboxEndpoints (Layer 5)
                        applyOverrides(json)
                        Log.d(TAG, "Dynamic Patcher: Endpoints actualizados exitosamente vía OTA.")
                        return Result.success()
                    } else {
                        Log.d(TAG, "Dynamic Patcher: Carga remota no contiene JSON válido.")
                    }
                } else if (response.code == 404) {
                    // Invocar diagnostic handler para registrar la URL de destino y los encabezados HTTP
                    DynamicPatcherDiagnosticHandler.logDiagnostic404(request, response, TAG)
                    return Result.success()
                } else {
                    Log.d(TAG, "Dynamic Patcher: Servidor respondió con código ${response.code}.")
                }
            }
        } catch (e: java.net.UnknownHostException) {
            Log.d(TAG, "Dynamic Patcher: Dispositivo sin conexión o DNS no resuelto, usando endpoints locales.")
            return Result.success()
        } catch (e: Exception) {
            Log.d(TAG, "Dynamic Patcher: Verificación de endpoints finalizada (${e.message}).")
            return Result.success()
        }

        return Result.success()
    }

    private fun applyOverrides(json: JSONObject) {
        // Here we store the overrides in SharedPreferences which XboxEndpoints reads at runtime
        val prefs = applicationContext.getSharedPreferences("god_mode_patcher", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        
        json.keys().forEach { key ->
            editor.putString(key, json.getString(key))
        }
        editor.apply()
    }
}

/**
 * Diagnostic handler for DynamicPatcher that logs specific target URLs and HTTP headers
 * when a 404 error occurs to identify the source of missing server-side resources.
 */
object DynamicPatcherDiagnosticHandler {
    data class DiagnosticReport(
        val targetUrl: String,
        val httpCode: Int,
        val httpMessage: String,
        val requestHeaders: Map<String, String>,
        val responseHeaders: Map<String, String>,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun logDiagnostic404(request: Request, response: okhttp3.Response, tag: String = "DynamicPatcher"): DiagnosticReport {
        val reqHeaders = mutableMapOf<String, String>()
        for (i in 0 until request.headers.size) {
            reqHeaders[request.headers.name(i)] = request.headers.value(i)
        }
        val respHeaders = mutableMapOf<String, String>()
        for (i in 0 until response.headers.size) {
            respHeaders[response.headers.name(i)] = response.headers.value(i)
        }

        val report = DiagnosticReport(
            targetUrl = request.url.toString(),
            httpCode = response.code,
            httpMessage = response.message,
            requestHeaders = reqHeaders,
            responseHeaders = respHeaders
        )

        val logOutput = buildString {
            appendLine("=== [DynamicPatcher DIAGNOSTIC 404] ===")
            appendLine("Target URL: ${report.targetUrl}")
            appendLine("HTTP Status: ${report.httpCode} ${report.httpMessage}")
            appendLine("Target Host: ${request.url.host}")
            appendLine("Target Path: ${request.url.encodedPath}")
            appendLine("Request Headers (${report.requestHeaders.size}):")
            report.requestHeaders.forEach { (name, value) ->
                appendLine("  -> $name: $value")
            }
            appendLine("Response Headers (${report.responseHeaders.size}):")
            report.responseHeaders.forEach { (name, value) ->
                appendLine("  <- $name: $value")
            }
            appendLine("Diagnostic Rationale: Missing server-side resource at remote endpoint; built-in native XboxEndpoints retained.")
            appendLine("=========================================")
        }
        Log.w(tag, logOutput)
        return report
    }
}
