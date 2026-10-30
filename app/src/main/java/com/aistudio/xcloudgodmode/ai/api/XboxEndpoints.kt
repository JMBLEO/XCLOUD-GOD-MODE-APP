package com.aistudio.xcloudgodmode.ai.api

/**
 * XboxEndpoints: Centralized catalog of the 14 core Xbox & Microsoft domains
 * for cloud streaming, rich media extraction, presence, social, and messaging.
 */
object XboxEndpoints {
    // 14 Core Domains
    const val EMERALD_CATALOG = "https://emerald.xboxservices.com"
    const val CONTENT_ACCESS = "https://contentaccess.exp.xboxservices.com"
    const val USER_PRESENCE = "https://userpresence.xboxlive.com"
    const val PLAY_ASSETS = "https://assets.play.xbox.com"
    const val NOTIFICATION_INBOX = "https://notificationinbox.xboxlive.com"
    const val XBL_MESSAGING = "https://xblmessaging.xboxlive.com"
    const val RTA_WEBSOCKET = "wss://rta.xboxlive.com/connect"
    const val GSSV_CHILE_CENTRAL = "https://clc.core.gssv-play-prod.xboxlive.com"
    const val PEOPLE_HUB = "https://peoplehub.xboxlive.com"
    const val XBL_CHAT = "https://chat.xboxlive.com"
    const val XBL_ACCOUNTS = "https://accounts.xboxlive.com"
    const val UHF_MICROSOFT = "https://uhf.microsoft.com"
    const val STORE_IMAGES_PRIMARY = "https://store-images.s-microsoft.com"
    const val STORE_IMAGES_SECONDARY = "https://store-images.microsoft.com"

    val MEDIA_AND_API_DOMAINS = mapOf(
        "EMERALD_CATALOG" to EMERALD_CATALOG,
        "CONTENT_ACCESS" to CONTENT_ACCESS,
        "USER_PRESENCE" to USER_PRESENCE,
        "PLAY_ASSETS" to PLAY_ASSETS,
        "NOTIFICATION_INBOX" to NOTIFICATION_INBOX,
        "XBL_MESSAGING" to XBL_MESSAGING,
        "RTA_WEBSOCKET" to RTA_WEBSOCKET,
        "GSSV_CHILE_CENTRAL" to GSSV_CHILE_CENTRAL,
        "PEOPLE_HUB" to PEOPLE_HUB,
        "XBL_CHAT" to XBL_CHAT,
        "XBL_ACCOUNTS" to XBL_ACCOUNTS,
        "UHF_MICROSOFT" to UHF_MICROSOFT,
        "STORE_IMAGES_PRIMARY" to STORE_IMAGES_PRIMARY,
        "STORE_IMAGES_SECONDARY" to STORE_IMAGES_SECONDARY
    )

    /**
     * Normalizes image URLs from Microsoft Store, Game Pass or third parties.
     * Fixes protocol-relative URLs (//...), truncations like "tore-images.microsoft.com" -> "store-images.s-microsoft.com",
     * and injects dynamic scaling parameters when appropriate.
     */
    /**
     * Normalizes image URLs from Microsoft Store, Game Pass or third parties.
     * Fixes protocol-relative URLs (//...), prefixes, and typos without breaking Akamai/CDN caches.
     */
    fun normalizeImageUrl(
        rawUrl: String?,
        width: Int = 600,
        height: Int = 900,
        quality: Int = 90
    ): String {
        if (rawUrl.isNullOrBlank()) return ""
        var url = rawUrl.trim()

        if (url.startsWith("//")) {
            url = "https:$url"
        } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }

        // Fix typo "tore-images.microsoft.com"
        if (url.contains("tore-images.microsoft.com", ignoreCase = true)) {
            url = url.replace(Regex("^https?://tore-images\\.microsoft\\.com", RegexOption.IGNORE_CASE), STORE_IMAGES_SECONDARY)
        }

        // Add sizing parameters only for Microsoft Store images (safe check)
        if (url.contains("store-images.microsoft.com") || url.contains("store-images.s-microsoft.com")) {
            if (!url.contains("?")) {
                url = "$url?w=$width&h=$height&format=jpg"
            } else if (!url.contains("w=") && !url.contains("h=")) {
                url = "$url&w=$width&h=$height&format=jpg"
            }
        }

        return url
    }
}
