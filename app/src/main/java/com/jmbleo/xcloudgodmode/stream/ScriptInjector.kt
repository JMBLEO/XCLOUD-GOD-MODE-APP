package com.jmbleo.xcloudgodmode.stream

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Gestor avanzado de inyección de scripts JavaScript para entornos de streaming.
 * Aísla y ejecuta el código en el momento óptimo (milisegundo 0 con document-start o post-carga),
 * garantizando que el userscript (God Mode / FSR / CAS / WebRTC hook) funcione como un entorno
 * independiente y seguro sin interferir en la interfaz nativa.
 */
open class ScriptInjector(
    private val context: Context,
    private val scriptAssetName: String = "xcloud-god-mode-ai.user.js"
) {

    companion object {
        private const val TAG = "ScriptInjector"
        private val ALLOWED_ORIGINS = setOf(
            "https://*.xbox.com",
            "https://xbox.com",
            "https://*.microsoft.com"
        )

        /**
         * CSS Ingestion Payload Quirúrgico: Oculta exclusivamente la barra superior de Xbox,
         * perfiles y menús de navegación, preservando intactos los contenedores del reproductor
         * de vídeo y el canvas de WebRTC para evitar pantallas negras.
         */
        const val CSS_HIDE_PAYLOAD = """
            (function() {
                try {
                    const host = location.hostname.toLowerCase();
                    const path = location.pathname.toLowerCase();

                    const authenticationPage =
                        host === 'login.live.com' ||
                        host === 'login.microsoftonline.com' ||
                        host.includes('login.') ||
                        path.includes('/auth/msa');

                    if (authenticationPage) {
                        document.getElementById('godmode-ui-injector')?.remove();
                        document.getElementById('xcloud-godmode-surgical-style')?.remove();
                        document.documentElement.classList.remove('godmode-stream-active');
                        return;
                    }

                    if (document.getElementById('xcloud-godmode-surgical-style')) return;
                    var style = document.createElement('style');
                    style.id = 'xcloud-godmode-surgical-style';
                    style.type = 'text/css';
                    style.innerHTML = `
                        /* Ocultar exclusivamente la barra de navegación superior de Xbox Cloud Gaming */
                        header, nav, [data-tid="navigation-header"], [class*="HeaderBar"], [class*="NavigationContainer"] {
                            display: none !important;
                            visibility: hidden !important;
                            opacity: 0 !important;
                            height: 0 !important;
                            pointer-events: none !important;
                        }

                        /* Asegurar que el contenedor del stream ocupe la pantalla completa de forma limpia */
                        body, html {
                            margin: 0 !important;
                            padding: 0 !important;
                            background-color: #000000 !important;
                            width: 100% !important;
                            height: 100% !important;
                            overflow: hidden !important;
                        }

                        /* Garantizar visibilidad absoluta del canvas y vídeo de WebRTC */
                        video, canvas, div[class*="StreamContainer"], div[class*="VideoContainer"] {
                            display: block !important;
                            visibility: visible !important;
                            opacity: 1 !important;
                            width: 100% !important;
                            height: 100% !important;
                        }
                    `;
                    document.head.appendChild(style);
                    console.log('[GodMode] Inyección quirúrgica completada sin bloquear WebRTC.');
                } catch(e) {
                    console.error('[GodMode] Error aplicando CSS Hiding Quirúrgico: ', e);
                }
            })();
        """

        /**
         * Inyecta la regla CSS que neutraliza la interfaz web de Xbox.
         */
        @JvmStatic
        fun injectCssHidingPayload(webView: WebView) {
            try {
                webView.evaluateJavascript(CSS_HIDE_PAYLOAD, null)
            } catch (e: Exception) {
                Log.e(TAG, "Error inyectando CSS Hiding Payload", e)
            }
        }

        /**
         * Inyección aislada de scripts JavaScript en WebView encapsulados en una IIFE autoejecutable,
         * aplicando simultáneamente la supresión de la UI web matriz de Xbox.
         */
        @JvmStatic
        fun injectGodModeScript(webView: WebView, scriptContent: String) {
            injectCssHidingPayload(webView)
            if (scriptContent.isNotBlank()) {
                val sanitizedScript = scriptContent.replace("\n", " ")
                val injectionPayload = "javascript:(function() { " +
                        "try { " +
                        "console.log('[GodMode] Injecting core scripts...');" +
                        "$sanitizedScript" +
                        "} catch(e) { " +
                        "console.error('[GodMode] Injection failed: ', e); " +
                        "} " +
                        "})();"
                webView.evaluateJavascript(injectionPayload, null)
            }
        }
    }

    private var cachedScriptContent: String = ""
    var isDocumentStartSupported: Boolean = false
        private set

    init {
        loadScriptFromAssets()
    }

    /**
     * Carga el script desde assets en memoria.
     */
    private fun loadScriptFromAssets() {
        try {
            cachedScriptContent = context.assets.open(scriptAssetName)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            Log.d(TAG, "Script '$scriptAssetName' cargado exitosamente (${cachedScriptContent.length} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Error al leer script '$scriptAssetName' desde assets", e)
            cachedScriptContent = ""
        }
    }

    /**
     * Configura la inyección temprana en document-start si el motor WebView del dispositivo lo soporta.
     * Esta es la técnica más avanzada: inyecta el script antes de que se ejecute cualquier JS de Xbox.com.
     */
    fun setupDocumentStartInjection(webView: WebView): Boolean {
        if (cachedScriptContent.isBlank()) {
            loadScriptFromAssets()
        }

        if (cachedScriptContent.isNotBlank() && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                // Envuelve el script en una IIFE aislada para evitar contaminación global indebida
                val isolatedScript = wrapInIsolatedSandbox(cachedScriptContent)
                WebViewCompat.addDocumentStartJavaScript(webView, isolatedScript, ALLOWED_ORIGINS)
                isDocumentStartSupported = true
                Log.i(TAG, "Inyección temprana document-start configurada para $ALLOWED_ORIGINS")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Fallo al registrar document-start script, usando fallback manual", e)
                isDocumentStartSupported = false
            }
        } else {
            isDocumentStartSupported = false
        }
        return false
    }

    /**
     * Inyección manual de rescate/fallback al iniciar o completar la carga de una página.
     */
    fun injectFallbackScript(webView: WebView) {
        injectCssHidingPayload(webView)
        if (cachedScriptContent.isBlank()) {
            loadScriptFromAssets()
        }
        if (cachedScriptContent.isNotBlank()) {
            try {
                val isolatedScript = wrapInIsolatedSandbox(cachedScriptContent)
                webView.evaluateJavascript(isolatedScript) { result ->
                    Log.d(TAG, "Script fallback inyectado: $result")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error inyectando fallback script", e)
            }
        }
    }

    /**
     * Inyección explícita tras la finalización de la carga del documento (onPageFinished).
     */
    fun injectOnPageFinished(webView: WebView) {
        injectCssHidingPayload(webView)
        injectFallbackScript(webView)
    }

    /**
     * Permite inyectar scripts JavaScript adicionales desde assets manteniendo
     * el aislamiento estricto respecto al hilo y la interfaz nativa.
     */
    fun injectCustomScript(webView: WebView, assetName: String) {
        try {
            val customCode = context.assets.open(assetName).bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (customCode.isNotBlank()) {
                val isolated = wrapInIsolatedSandbox(customCode)
                webView.evaluateJavascript(isolated) { res ->
                    Log.d(TAG, "Custom script '$assetName' inyectado: $res")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error inyectando custom script '$assetName'", e)
        }
    }

    /**
     * Aísla el userscript en un entorno independiente con clausura estricta,
     * previniendo que excepciones del stream o de la página colapsen el hilo nativo.
     */
    fun wrapInIsolatedSandbox(source: String): String {
        return """
            (function() {
                'use strict';
                try {
                    $CSS_HIDE_PAYLOAD
                    console.log('[ScriptInjector] Inicializando God Mode Native Engine...');
                    $source
                } catch (e) {
                    console.error('[ScriptInjector] Excepción capturada en Sandbox:', e);
                }
            })();
        """.trimIndent()
    }

    fun getScriptCode(): String = cachedScriptContent
}
