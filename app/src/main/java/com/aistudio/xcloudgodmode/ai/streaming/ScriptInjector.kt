package com.aistudio.xcloudgodmode.ai.streaming

import android.webkit.WebView

class ScriptInjector(private val context: android.content.Context) {

    /**
     * Configura la inyección temprana en document-start para asegurar el control de UI
     * sin alterar los contenedores críticos de WebRTC de la SPA de Xbox.
     */
    fun setupDocumentStartInjection(webView: WebView) {
        // En Android, la inyección real se ejecuta al terminar la carga o mediante evaluateJavascript controlado
    }

    fun injectGodModeScript(webView: WebView, customScript: String = "") {
        val safeScript = customScript.replace("\n", " ")

        // Payload CSS quirúrgico: Oculta exclusivamente la barra superior de Xbox,
        // perfiles y menús sociales, sin afectar los contenedores del reproductor de vídeo.
        val surgicalCssPayload = """
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
                } catch(_) {}

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

                try {
                    $safeScript
                    console.log('[GodMode] Inyección quirúrgica completada sin bloquear WebRTC.');
                } catch(err) {
                    console.error('[GodMode] Error ejecutando script personalizado: ', err);
                }
            })();
        """.trimIndent()

        val fullInjection = "javascript:(function() { try { $surgicalCssPayload } catch(e) { console.error(e); } })();"
        webView.evaluateJavascript(fullInjection, null)
    }

    companion object {
        fun injectGodModeScript(webView: WebView, scriptContent: String = "") {
            val injector = ScriptInjector(webView.context)
            injector.injectGodModeScript(webView, scriptContent)
        }
    }
}
