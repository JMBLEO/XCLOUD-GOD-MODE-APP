package com.xcloud.godmode.services

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WebKitCookieSynchronizer: Extracts cookies from the WebView engine to sync them with native HTTP clients.
 */
class WebKitCookieSynchronizer {
    /**
     * Extracts Xbox/Microsoft cookies from the WebView motor after RPS Bypass.
     */
    suspend fun extractXboxCookies(targetUrl: String = "https://login.live.com"): String? =
        withContext(Dispatchers.IO) {
            val cookieManager = CookieManager.getInstance()
            cookieManager.getCookie(targetUrl)
        }
}
