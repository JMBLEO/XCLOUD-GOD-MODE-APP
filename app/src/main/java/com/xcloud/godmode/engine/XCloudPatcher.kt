package com.xcloud.godmode.engine

import android.util.Log

/**
 * XCloudPatcher: Real-time source code mutator for Xbox Cloud Gaming JS bundles.
 * Uses regex to rewrite minified code to unlock hidden menus and hide UI elements.
 */
object XCloudPatcher {

    private const val TAG = "XCloudPatcher"

    fun patch(url: String, source: String): String {
        var patched = source

        // 1. Unblock Resolution & Bitrate (Unlock 1080p HQ and beyond)
        patched = patched.replace(
            Regex("""this\.maxVideoBitrate=(\d+)"""), 
            "this.maxVideoBitrate=25000000" // 25Mbps God Mode Ultra
        ).replace(
            "maxResolution:720", "maxResolution:1080"
        ).replace(
            "maxVideoBitrate:w+,enable1080p:w+", "maxVideoBitrate:25000,enable1080p:true"
        )

        // 2. Unlock Developer/Debug Menus & Internal Flags
        patched = patched.replace("isDebug:!1", "isDebug:!0")
            .replace("isInternal:!1", "isInternal:!0")
            .replace("enableDebugMenu:!1", "enableDebugMenu:!0")
            .replace("showDebugOverlay:!1", "showDebugOverlay:!0")
            .replace("isDebugMenuEnabled:!1", "isDebugMenuEnabled:!0")
            .replace("enableDeveloperMenu:!1", "enableDeveloperMenu:!0")

        // 3. Network & Connection Spoofing (Bypass 4G/5G restrictions)
        patched = patched.replace(
            Regex("""navigator\.connection\.effectiveType"""),
            "\"4g\"" 
        )

        // 4. Bypass "Touch Controls Only" restrictions
        patched = patched.replace("isTouchOnly:!0", "isTouchOnly:!1")

        // 5. Force "Anaconda" (Series X) hardware profile
        patched = patched.replace(
            Regex("""deviceProfile:"(.*?)\""""),
            "deviceProfile:\"Anaconda\""
        )

        // 6. Community Patch: Enable "Stream Settings" & Advanced Features
        patched = patched.replace("showStreamSettings:!1", "showStreamSettings:!0")
        patched = patched.replace("enableStreamSettings:!1", "enableStreamSettings:!0")
        
        // 7. AST-Style Mutation: Feature Flag Injection
        val featureFlagRegex = Regex("""(\w+)\.featureFlags=\{(.*?)\}""")
        patched = patched.replace(featureFlagRegex) { match ->
            val objName = match.groupValues[1]
            val flags = match.groupValues[2]
            // AV1, 120FPS, Multi-controller, Clarity Boost, etc.
            "$objName.featureFlags={$flags,enableAV1:!0,enable120FPS:!0,localCoOp:!0,highResStream:!0,clarityBoost:!0,lowLatencyMode:!0}"
        }

        if (source.length != patched.length) {
            Log.d(TAG, "Successfully applied God Mode patches to: $url")
        }

        return patched
    }
}
