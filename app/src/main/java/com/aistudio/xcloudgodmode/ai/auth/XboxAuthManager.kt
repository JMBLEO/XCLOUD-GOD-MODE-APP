package com.aistudio.xcloudgodmode.ai.auth

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

object XboxAuthManager {
    private const val XBOX_START_URL =
        "https://www.xbox.com/play"

    private val sessionOrigins = listOf(
        "https://www.xbox.com",
        "https://xbox.com",
        "https://login.live.com",
        "https://login.microsoftonline.com"
    )

    fun configureCookies(webView: WebView) {
        val manager = CookieManager.getInstance()

        manager.setAcceptCookie(true)
        manager.setAcceptThirdPartyCookies(
            webView,
            true
        )
    }

    fun configureCookieStorage() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
    }

    /**
     * Indica que hay cookies candidatas.
     *
     * No garantiza que Xbox siga aceptando
     * la sesión en el servidor.
     */
    fun hasCandidateSession(): Boolean {
        val manager = CookieManager.getInstance()

        return sessionOrigins.any { origin ->
            !manager
                .getCookie(origin)
                .isNullOrBlank()
        }
    }

    fun hasActiveSession(context: Context? = null): Boolean {
        return hasCandidateSession()
    }

    fun saveSession(context: Context? = null): Boolean {
        CookieManager
            .getInstance()
            .flush()
        return hasCandidateSession()
    }

    fun startUrl(): String =
        XBOX_START_URL

    suspend fun clearSession(
        context: Context
    ): Boolean = withContext(
        Dispatchers.Main
    ) {
        val manager = CookieManager.getInstance()

        val removed =
            suspendCancellableCoroutine { continuation ->
                manager.removeAllCookies { success ->
                    if (continuation.isActive) {
                        continuation.resume(success)
                    }
                }
            }

        manager.flush()

        WebStorage
            .getInstance()
            .deleteAllData()

        context.cacheDir
            .resolve("xbox_artworks")
            .deleteRecursively()

        removed
    }

    fun clearSession(
        context: Context,
        clearWebStorage: Boolean = false,
        onComplete: (Boolean) -> Unit = {}
    ) {
        val appContext = context.applicationContext
        val cookieManager = CookieManager.getInstance()

        Handler(Looper.getMainLooper()).post {
            cookieManager.removeAllCookies { removed ->
                cookieManager.flush()

                if (clearWebStorage) {
                    WebStorage
                        .getInstance()
                        .deleteAllData()
                }

                appContext
                    .getSharedPreferences(
                        "xcloud_auth_state",
                        Context.MODE_PRIVATE
                    )
                    .edit()
                    .clear()
                    .apply()

                onComplete(removed)
            }
        }
    }

    fun isAuthenticationUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return false
        return XboxAuthRoutes.isMicrosoftLogin(uri) || (uri.path?.contains("/auth/msa") == true)
    }

    fun isSuccessfulXboxNavigation(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return false
        return XboxAuthRoutes.isXboxPlay(uri)
    }
}
