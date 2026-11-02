package com.xcloud.godmode.streaming

import android.webkit.WebView

object RegionBypassWebViewHelper {

    // IPs oficiales de bypass verificadas en XFly (constants.ts) y Better-xCloud (bypass-servers.ts)
    private const val BYPASS_IP_BRAZIL = "169.150.198.66"
    private const val BYPASS_IP_US = "143.244.47.65"

    /**
     * 1. Normaliza la URL de Xbox Cloud Gaming para evitar que mercados sin portal web local
     * (como es-CL, es-CO, es-PE, es-EC) redirijan a la pantalla "determinadas regiones".
     * Usa 'es-MX' para mantener toda la interfaz y los juegos en Español Latino, o 'pt-BR' / 'en-US'.
     */
    fun buildSupportedPlayUrl(productId: String? = null, preferredLocale: String = "es-MX"): String {
        val safeLocale = when (preferredLocale.lowercase()) {
            "es-cl", "es-pe", "es-co", "es-ec", "es-uy", "es-py", "es-bo", "es-ve" -> "es-MX"
            else -> preferredLocale.ifBlank { "es-MX" }
        }
        return if (!productId.isNullOrBlank()) {
            "https://www.xbox.com/$safeLocale/play/launch/$productId"
        } else {
            "https://www.xbox.com/$safeLocale/play"
        }
    }

    /**
     * 2. Script de inyección temprana (Inspirado en XFly fetch-hook.ts y Better-xCloud xcloud-interceptor.ts).
     * Sobrescribe window.fetch y XMLHttpRequest dentro del WebView para inyectar X-Forwarded-For
     * en cada petición a *.gssv-play-prod.xboxlive.com y forzar el servidor de menor latencia.
     */
    fun buildEarlyFetchBypassScript(
        spoofedIp: String = BYPASS_IP_BRAZIL,
        injectedGsToken: String? = null
    ): String = """
        (function() {
            if (window.__GODMODE_FETCH_HOOKED__) return;
            window.__GODMODE_FETCH_HOOKED__ = true;

            const SPOOF_IP = '$spoofedIp';
            const INJECTED_GS_TOKEN = ${if (injectedGsToken != null) "'$injectedGsToken'" else "null"};
            const origFetch = window.fetch;

            window.fetch = async function(input, init) {
                let url = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                let options = Object.assign({}, init || {});
                let headers = new Headers(options.headers || (typeof input !== 'string' && input ? input.headers : undefined) || {});

                // Interceptar llamadas a los servidores GSSV de Xbox Cloud Gaming (Ref: XFly / Better-xCloud)
                if (/gssv-play-prod|xboxlive\.com/i.test(url)) {
                    if (SPOOF_IP && (/\/v2\/login\/user/i.test(url) || /xgpuweb/i.test(url))) {
                        headers.set('X-Forwarded-For', SPOOF_IP);
                    }
                    if (INJECTED_GS_TOKEN && !headers.has('Authorization') && /\/v2\/titles|\/v5\/sessions/i.test(url)) {
                        headers.set('Authorization', 'Bearer ' + INJECTED_GS_TOKEN);
                    }
                    options.headers = headers;
                }

                const response = await origFetch.call(this, input, options);

                // Si /v2/login/user devuelve las regiones, asegurar que allowRegionSelection sea true
                if (/\/v2\/login\/user/i.test(url) && response.ok) {
                    try {
                        const clone = response.clone();
                        const data = await clone.json();
                        if (data && data.offeringSettings && Array.isArray(data.offeringSettings.regions)) {
                            data.offeringSettings.allowRegionSelection = true;
                            return new Response(JSON.stringify(data), {
                                status: response.status,
                                statusText: response.statusText,
                                headers: response.headers
                            });
                        }
                    } catch (e) {}
                }
                return response;
            };

            // Redirigir automáticamente si xbox.com intenta mostrar la página de región no soportada
            if (/\/es-CL\/play|\/es-CO\/play|\/es-PE\/play/i.test(location.pathname)) {
                location.replace(location.href.replace(/\/es-(CL|CO|PE)\/play/i, '/es-MX/play'));
            }
        })();
    """.trimIndent()

    fun injectBypassScript(webView: WebView, spoofedIp: String = BYPASS_IP_BRAZIL, gsToken: String? = null) {
        val script = buildEarlyFetchBypassScript(spoofedIp, gsToken)
        webView.evaluateJavascript(script, null)
    }
}
