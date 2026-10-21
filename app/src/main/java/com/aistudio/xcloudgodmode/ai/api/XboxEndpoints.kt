package com.aistudio.xcloudgodmode.ai.api

object XboxEndpoints {
    val MEDIA_AND_API_DOMAINS = mapOf(
        "STORE_IMAGES_PRIMARY" to "https://store-images.s-microsoft.com",
        "PLAY_ASSETS" to "https://assets.play.xbox.com",
        "EMERALD_CATALOG" to "https://emerald.xboxservices.com",
        "CONTENT_ACCESS" to "https://contentaccess.exp.xboxservices.com",
        "GSSV_CHILE_CENTRAL" to "https://clc.core.gssv-play-prod.xboxlive.com",
        "PEOPLE_HUB" to "https://peoplehub.xboxlive.com",
        "USER_PRESENCE" to "https://userpresence.xboxlive.com",
        "NOTIFICATION_INBOX" to "https://notificationinbox.xboxlive.com",
        "XBL_MESSAGING" to "https://xblmessaging.xboxlive.com",
        "XBL_CHAT" to "https://chat.xboxlive.com",
        "XBL_ACCOUNTS" to "https://accounts.xboxlive.com",
        "UHF_MICROSOFT" to "https://uhf.microsoft.com"
    )

    fun normalizeImageUrl(
        rawUrl: String?,
        width: Int = 600,
        height: Int = 900
    ): String {
        if (rawUrl.isNullOrBlank()) return ""
        var url = rawUrl.trim()

        if (url.startsWith("//")) url = "https:$url"
        else if (!url.startsWith("http")) url = "https://$url"

        // Fix potential CDN domain issues
        url = url.replace(Regex("https?://(tore|store)-images\\.microsoft\\.com", RegexOption.IGNORE_CASE), "https://store-images.s-microsoft.com")

        return if (!url.contains("?")) {
            "$url?w=$width&h=$height&q=90&mode=scale&format=jpg"
        } else {
            url
        }
    }
}
