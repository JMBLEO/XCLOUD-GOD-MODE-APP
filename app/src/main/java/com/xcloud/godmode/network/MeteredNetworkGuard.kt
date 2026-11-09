package com.xcloud.godmode.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log

/**
 * Phase 63: Metered Network Guard (Cellular & Hotspot Data Saver).
 * Detects metered network links via ConnectivityManager and caps video bitrate
 * (typically 7-10 Mbps) to prevent massive cellular data overconsumption.
 */
class MeteredNetworkGuard(private val context: Context) {

    companion object {
        private const val TAG = "MeteredNetworkGuard"
        const val DEFAULT_METERED_BITRATE_CAP_BPS = 8_000_000 // 8 Mbps
        const val UNMETERED_DEFAULT_BITRATE_BPS = 20_000_000  // 20 Mbps
    }

    data class NetworkAudit(
        val isMetered: Boolean,
        val isCellular: Boolean,
        val isWifi: Boolean,
        val recommendedBitrateCapBps: Int,
        val warningMessage: String?
    )

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /**
     * Inspects active network capabilities.
     */
    fun auditCurrentNetwork(): NetworkAudit {
        val cm = connectivityManager ?: return NetworkAudit(
            isMetered = false,
            isCellular = false,
            isWifi = false,
            recommendedBitrateCapBps = UNMETERED_DEFAULT_BITRATE_BPS,
            warningMessage = null
        )

        val isMetered = cm.isActiveNetworkMetered
        var isCellular = false
        var isWifi = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(activeNetwork)
            if (caps != null) {
                isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
        }

        val recommendedCap = if (isMetered || isCellular) {
            DEFAULT_METERED_BITRATE_CAP_BPS
        } else {
            UNMETERED_DEFAULT_BITRATE_BPS
        }

        val warning = if (isMetered) {
            "Red medida detectada (Datos móviles o hotspot). Bitrate restringido a 8 Mbps para conservar datos."
        } else null

        Log.d(TAG, "Network Audit: metered=$isMetered, cellular=$isCellular, cap=${recommendedCap / 1_000_000}Mbps")

        return NetworkAudit(
            isMetered = isMetered,
            isCellular = isCellular,
            isWifi = isWifi,
            recommendedBitrateCapBps = recommendedCap,
            warningMessage = warning
        )
    }

    /**
     * Computes the adjusted bitrate respecting user metered preference.
     */
    fun computeEffectiveBitrate(requestedBitrateBps: Int, forceDataSaver: Boolean): Int {
        val audit = auditCurrentNetwork()
        if (forceDataSaver || audit.isMetered) {
            return requestedBitrateBps.coerceAtMost(audit.recommendedBitrateCapBps)
        }
        return requestedBitrateBps
    }
}
