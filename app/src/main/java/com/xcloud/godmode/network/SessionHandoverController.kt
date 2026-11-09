package com.xcloud.godmode.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TransportType {
    WIFI,
    CELLULAR_5G_LTE,
    ETHERNET,
    OTHER,
    NONE
}

data class SessionHandoverEvent(
    val previousTransport: TransportType,
    val currentTransport: TransportType,
    val isSeamlessHandover: Boolean,
    val timestampEpochMs: Long
)

/**
 * SessionHandoverController: Monitoreo continuo de red para realizar transiciones transparentes
 * (Wi-Fi <-> 5G) re-anclando el socket UDP sin destruir la Surface de renderizado ni MediaCodec.
 */
class SessionHandoverController(
    private val context: Context,
    private val onReAnchorRequired: (previous: TransportType, current: TransportType) -> Unit
) {
    companion object {
        private const val TAG = "SessionHandover"
    }

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var currentTransport: TransportType = TransportType.NONE
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val _lastHandoverEvent = MutableStateFlow<SessionHandoverEvent?>(null)
    val lastHandoverEvent: StateFlow<SessionHandoverEvent?> = _lastHandoverEvent.asStateFlow()

    fun startMonitoring() {
        if (connectivityManager == null) return
        stopMonitoring()

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val newTransport = resolveTransport(capabilities)
                if (currentTransport != TransportType.NONE && currentTransport != newTransport) {
                    val oldTransport = currentTransport
                    currentTransport = newTransport
                    Log.i(TAG, "Network transport shifted from $oldTransport to $newTransport. Triggering hot session re-anchor.")

                    val event = SessionHandoverEvent(
                        previousTransport = oldTransport,
                        currentTransport = newTransport,
                        isSeamlessHandover = true,
                        timestampEpochMs = System.currentTimeMillis()
                    )
                    _lastHandoverEvent.value = event
                    onReAnchorRequired(oldTransport, newTransport)
                } else {
                    currentTransport = newTransport
                }
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Active network lost during streaming session. Holding decoder surface.")
                val old = currentTransport
                currentTransport = TransportType.NONE
                _lastHandoverEvent.value = SessionHandoverEvent(
                    previousTransport = old,
                    currentTransport = TransportType.NONE,
                    isSeamlessHandover = false,
                    timestampEpochMs = System.currentTimeMillis()
                )
            }
        }

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network handover callback", e)
        }
    }

    fun stopMonitoring() {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        networkCallback = null
    }

    private fun resolveTransport(caps: NetworkCapabilities): TransportType {
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TransportType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> TransportType.CELLULAR_5G_LTE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> TransportType.ETHERNET
            else -> TransportType.OTHER
        }
    }
}
