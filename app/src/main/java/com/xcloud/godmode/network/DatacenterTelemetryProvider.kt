package com.xcloud.godmode.network

import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Health & Latency tier for cloud gaming datacenter.
 */
enum class DatacenterHealthTier {
    OPTIMAL,     // < 50 ms
    ACCEPTABLE,  // 50 - 100 ms
    HIGH_LATENCY,// > 100 ms
    UNREACHABLE
}

data class DatacenterInfo(
    val id: String,
    val name: String,
    val flagEmoji: String,
    val regionCode: String,
    val endpointHost: String,
    val rttMs: Long? = null,
    val healthTier: DatacenterHealthTier = DatacenterHealthTier.UNREACHABLE,
    val isEstimatedQueueCrowded: Boolean = false,
    val lastProbedEpochMs: Long = 0L
)

/**
 * 19 Official Azure Cloud Gaming Datacenter regions with live RTT probing.
 */
object DatacenterRegistry {
    val ALL_REGIONS = listOf(
        DatacenterInfo("chilecentral", "Chile Central", "🇨🇱", "CL", "chilecentral.cloudgaming.xbox.com"),
        DatacenterInfo("brazilsouth", "Brazil South", "🇧🇷", "BR", "brazilsouth.cloudgaming.xbox.com"),
        DatacenterInfo("eastus", "US East", "🇺🇸", "US-E", "eastus.cloudgaming.xbox.com"),
        DatacenterInfo("eastus2", "US East 2", "🇺🇸", "US-E2", "eastus2.cloudgaming.xbox.com"),
        DatacenterInfo("westus", "US West", "🇺🇸", "US-W", "westus.cloudgaming.xbox.com"),
        DatacenterInfo("westus2", "US West 2", "🇺🇸", "US-W2", "westus2.cloudgaming.xbox.com"),
        DatacenterInfo("centralus", "US Central", "🇺🇸", "US-C", "centralus.cloudgaming.xbox.com"),
        DatacenterInfo("southcentralus", "US South Central", "🇺🇸", "US-SC", "southcentralus.cloudgaming.xbox.com"),
        DatacenterInfo("uksouth", "UK South", "🇬🇧", "UK-S", "uksouth.cloudgaming.xbox.com"),
        DatacenterInfo("westeurope", "Europe West", "🇳🇱", "EU-W", "westeurope.cloudgaming.xbox.com"),
        DatacenterInfo("northeurope", "Europe North", "🇮🇪", "EU-N", "northeurope.cloudgaming.xbox.com"),
        DatacenterInfo("germanywestcentral", "Germany West", "🇩🇪", "DE", "germanywestcentral.cloudgaming.xbox.com"),
        DatacenterInfo("francecentral", "France Central", "🇫🇷", "FR", "francecentral.cloudgaming.xbox.com"),
        DatacenterInfo("swedencentral", "Sweden Central", "🇸🇪", "SE", "swedencentral.cloudgaming.xbox.com"),
        DatacenterInfo("japaneast", "Japan East", "🇯🇵", "JP-E", "japaneast.cloudgaming.xbox.com"),
        DatacenterInfo("koreacentral", "Korea Central", "🇰🇷", "KR", "koreacentral.cloudgaming.xbox.com"),
        DatacenterInfo("australiaeast", "Australia East", "🇦🇺", "AU-E", "australiaeast.cloudgaming.xbox.com"),
        DatacenterInfo("australiasoutheast", "Australia Southeast", "🇦🇺", "AU-SE", "australiasoutheast.cloudgaming.xbox.com"),
        DatacenterInfo("southeastasia", "Southeast Asia", "🇸🇬", "SG", "southeastasia.cloudgaming.xbox.com")
    )
}

/**
 * DatacenterTelemetryProvider: Performs low-overhead RTT latency probes (HEAD / TCP handshakes)
 * across Azure datacenters to drive optimal route selection.
 */
class DatacenterTelemetryProvider(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "DatacenterTelemetry"
    }

    private val _datacenters = MutableStateFlow(DatacenterRegistry.ALL_REGIONS)
    val datacenters: StateFlow<List<DatacenterInfo>> = _datacenters.asStateFlow()

    private val _selectedDatacenterId = MutableStateFlow("chilecentral")
    val selectedDatacenterId: StateFlow<String> = _selectedDatacenterId.asStateFlow()

    fun selectDatacenter(id: String) {
        _selectedDatacenterId.value = id
    }

    suspend fun probeAll(scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        val currentList = _datacenters.value
        val updated = currentList.map { dc ->
            scope.async(Dispatchers.IO) {
                probeSingle(dc)
            }
        }.awaitAll()

        _datacenters.value = updated.sortedWith(
            compareBy<DatacenterInfo> { it.rttMs ?: Long.MAX_VALUE }
        )
    }

    private fun probeSingle(dc: DatacenterInfo): DatacenterInfo {
        val startNs = SystemClock.elapsedRealtimeNanos()
        var rttMs: Long? = null

        // Attempt TCP connect to 443
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(dc.endpointHost, 443), 2500)
                val durationNs = SystemClock.elapsedRealtimeNanos() - startNs
                rttMs = durationNs / 1_000_000L
            }
        } catch (_: IOException) {
            // Fallback HTTP HEAD
            try {
                val req = Request.Builder()
                    .url("https://${dc.endpointHost}")
                    .head()
                    .build()
                val response = httpClient.newCall(req).execute()
                response.close()
                val durationNs = SystemClock.elapsedRealtimeNanos() - startNs
                rttMs = durationNs / 1_000_000L
            } catch (_: Exception) {
                rttMs = null
            }
        }

        val tier = when {
            rttMs == null -> DatacenterHealthTier.UNREACHABLE
            rttMs < 50L -> DatacenterHealthTier.OPTIMAL
            rttMs <= 100L -> DatacenterHealthTier.ACCEPTABLE
            else -> DatacenterHealthTier.HIGH_LATENCY
        }

        return dc.copy(
            rttMs = rttMs,
            healthTier = tier,
            lastProbedEpochMs = System.currentTimeMillis()
        )
    }
}
