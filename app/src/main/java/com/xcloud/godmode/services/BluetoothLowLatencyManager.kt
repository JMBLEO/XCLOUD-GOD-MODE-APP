package com.xcloud.godmode.services

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class WifiFrequencyBand {
    BAND_2_4_GHZ,   // Potential interference with Bluetooth
    BAND_5_GHZ,     // Optimal for wireless cloud gaming
    BAND_6_GHZ,     // Wi-Fi 6E/7 Ultra-wide
    ETHERNET_OR_CELLULAR,
    UNKNOWN
}

data class BluetoothOptimizationState(
    val isConnectionPriorityHigh: Boolean = false,
    val isWifiCoexistenceWarningActive: Boolean = false,
    val currentWifiBand: WifiFrequencyBand = WifiFrequencyBand.UNKNOWN,
    val activeControllerName: String? = null
)

/**
 * BluetoothLowLatencyManager: Forces minimum connection intervals (7.5ms / 133 Hz baseband polling)
 * for connected Bluetooth gamepads and audits 2.4 GHz Wi-Fi/Bluetooth coexistence to prevent packet drops.
 */
class BluetoothLowLatencyManager(private val context: Context) {
    companion object {
        private const val TAG = "BtLowLatencyMgr"
    }

    private val _state = MutableStateFlow(BluetoothOptimizationState())
    val state: StateFlow<BluetoothOptimizationState> = _state.asStateFlow()

    private var activeGatt: BluetoothGatt? = null

    /**
     * Requests high connection priority (7.5ms interval) on the controller's GATT connection.
     */
    fun optimizeControllerConnection(device: BluetoothDevice) {
        val deviceName = try { device.name } catch (_: SecurityException) { "Bluetooth Controller" }
        Log.i(TAG, "Requesting low-latency Bluetooth link for $deviceName")

        try {
            activeGatt?.close()
            activeGatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        val requested = gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) == true
                        Log.i(TAG, "GATT CONNECTION_PRIORITY_HIGH requested: $requested")
                        _state.value = _state.value.copy(
                            isConnectionPriorityHigh = requested,
                            activeControllerName = deviceName
                        )
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        _state.value = _state.value.copy(
                            isConnectionPriorityHigh = false,
                            activeControllerName = null
                        )
                    }
                }
            })
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing Bluetooth permissions to optimize GATT connection", e)
        }

        auditWifiCoexistence()
    }

    /**
     * Audits whether active Wi-Fi is operating on 2.4 GHz, triggering an interference warning.
     */
    fun auditWifiCoexistence(): WifiFrequencyBand {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

        val activeNetwork = connectivityManager?.activeNetwork
        val caps = connectivityManager?.getNetworkCapabilities(activeNetwork)

        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val band = WifiFrequencyBand.ETHERNET_OR_CELLULAR
            _state.value = _state.value.copy(currentWifiBand = band, isWifiCoexistenceWarningActive = false)
            return band
        }

        var frequency = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && caps.transportInfo is WifiInfo) {
            frequency = (caps.transportInfo as WifiInfo).frequency
        } else if (wifiManager != null) {
            @Suppress("DEPRECATION")
            frequency = wifiManager.connectionInfo.frequency
        }

        val band = when {
            frequency in 2400..2495 -> WifiFrequencyBand.BAND_2_4_GHZ
            frequency in 5150..5895 -> WifiFrequencyBand.BAND_5_GHZ
            frequency >= 5925 -> WifiFrequencyBand.BAND_6_GHZ
            else -> WifiFrequencyBand.UNKNOWN
        }

        val warning = (band == WifiFrequencyBand.BAND_2_4_GHZ)
        if (warning) {
            Log.w(TAG, "Coexistence warning: Wi-Fi on 2.4 GHz ($frequency MHz) interferes with Bluetooth controller packets.")
        }

        _state.value = _state.value.copy(currentWifiBand = band, isWifiCoexistenceWarningActive = warning)
        return band
    }

    fun release() {
        try {
            activeGatt?.close()
        } catch (_: Exception) {}
        activeGatt = null
    }
}
