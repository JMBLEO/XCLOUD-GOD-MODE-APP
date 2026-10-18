package com.aistudio.xcloudgodmode.ai.auth

import android.net.Uri

internal object XboxAuthRoutes {
    private val trustedHosts = setOf(
        "xbox.com",
        "www.xbox.com",
        "login.live.com",
        "login.microsoftonline.com",
        "account.live.com",
        "account.xbox.com",
        "sisu.xboxlive.com",
        "user.auth.xboxlive.com",
        "xsts.auth.xboxlive.com",
        "account.microsoft.com",
        "login.microsoft.com"
    )

    fun isTrusted(uri: Uri): Boolean {
        val host = uri.host
            ?.lowercase()
            ?: return false

        return uri.scheme == "https" &&
            (
                host in trustedHosts ||
                host.endsWith(".xbox.com") ||
                host.endsWith(".live.com") ||
                host.endsWith(".microsoft.com") ||
                host.endsWith(".microsoftonline.com") ||
                host.endsWith(".xboxlive.com") ||
                host.endsWith(".msauth.net") ||
                host.endsWith(".msftauth.net")
            )
    }

    fun isXboxOAuthCallback(uri: Uri): Boolean {
        if (!isXboxHost(uri)) {
            return false
        }

        val path = normalisedPath(uri)

        return path.endsWith("/auth/msa") &&
            uri.getQueryParameter("action")
                .equals(
                    "loggedIn",
                    ignoreCase = true
                )
    }

    fun isXboxLoginRequest(uri: Uri): Boolean {
        if (!isXboxHost(uri)) {
            return false
        }

        val path = normalisedPath(uri)

        return path.endsWith("/auth/msa") &&
            uri.getQueryParameter("action")
                .equals(
                    "logIn",
                    ignoreCase = true
                )
    }

    fun isXboxPlay(uri: Uri): Boolean {
        if (!isXboxHost(uri)) {
            return false
        }

        val path = normalisedPath(uri)

        return path == "/play" ||
            path.endsWith("/play") ||
            path.startsWith("/play/")
    }

    fun isMicrosoftLogin(uri: Uri): Boolean {
        val host = uri.host
            ?.lowercase()
            ?: return false

        return uri.scheme == "https" &&
            (
                host == "login.live.com" ||
                host == "login.microsoftonline.com" ||
                host.endsWith(".login.microsoftonline.com") ||
                host.endsWith(".live.com") ||
                host.endsWith(".microsoft.com") ||
                host.endsWith(".xboxlive.com")
            )
    }

    private fun isXboxHost(uri: Uri): Boolean {
        val host = uri.host
            ?.lowercase()
            ?: return false

        return uri.scheme == "https" &&
            (
                host == "xbox.com" ||
                host == "www.xbox.com" ||
                host.endsWith(".xbox.com")
            )
    }

    private fun normalisedPath(uri: Uri): String {
        val path = uri.path
            ?.trimEnd('/')
            ?.lowercase()
            .orEmpty()

        return if (path.isEmpty()) "/" else path
    }
}
