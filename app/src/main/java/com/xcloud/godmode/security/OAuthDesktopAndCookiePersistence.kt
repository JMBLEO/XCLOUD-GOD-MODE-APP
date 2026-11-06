package com.xcloud.godmode.security

import android.webkit.CookieManager
import android.util.Log

/**
 * OAuthDesktopAndCookiePersistence: Manages the lifecycle of WebView cookies
 * for Microsoft/Xbox authentication, ensuring the session survives browser redirects.
 */
object OAuthDesktopAndCookiePersistence {
    private const val TAG = "CookiePersistence"

    fun flush() {
        try {
            CookieManager.getInstance().flush()
            Log.d(TAG, "Auth cookies flushed to disk.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to flush cookies", e)
        }
    }

    fun clear() {
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            Log.d(TAG, "Auth cookies cleared.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear cookies", e)
        }
    }
}
