package com.xcloud.godmode.network

import org.json.JSONObject

data class NetworkBufferProfile(
    val targetJitterBufferMs: Int,
    val maxJitterBufferMs: Int,
    val nackGraceWindowMs: Int,
    val rtpReorderRingSize: Int,
    val keyframeCooldownMs: Int
)

data class GeoBypassNode(
    val countryCode: String,
    val countryName: String,
    val integratedBypassIp: String,
    val market: String,
    val locale: String,
    val timezoneOffsetMinutes: Int,
    val bufferProfile: NetworkBufferProfile
)

object GeoBypassRegistry {

    private val integratedNodes = listOf(
        GeoBypassNode(
            countryCode = "AR",
            countryName = "Argentina (Prioridad Chile / Cono Sur)",
            integratedBypassIp = "181.15.144.12",
            market = "AR",
            locale = "es-AR",
            timezoneOffsetMinutes = -180,
            bufferProfile = NetworkBufferProfile(
                targetJitterBufferMs = 8,
                maxJitterBufferMs = 24,
                nackGraceWindowMs = 3,
                rtpReorderRingSize = 128,
                keyframeCooldownMs = 1000
            )
        ),
        GeoBypassNode(
            countryCode = "BR",
            countryName = "Brasil (São Paulo Direct)",
            integratedBypassIp = "200.221.2.45",
            market = "BR",
            locale = "pt-BR",
            timezoneOffsetMinutes = -180,
            bufferProfile = NetworkBufferProfile(
                targetJitterBufferMs = 10,
                maxJitterBufferMs = 30,
                nackGraceWindowMs = 4,
                rtpReorderRingSize = 256,
                keyframeCooldownMs = 1100
            )
        ),
        GeoBypassNode(
            countryCode = "MX",
            countryName = "México (Latam Norte)",
            integratedBypassIp = "187.188.12.34",
            market = "MX",
            locale = "es-MX",
            timezoneOffsetMinutes = -360,
            bufferProfile = NetworkBufferProfile(
                targetJitterBufferMs = 14,
                maxJitterBufferMs = 42,
                nackGraceWindowMs = 5,
                rtpReorderRingSize = 256,
                keyframeCooldownMs = 1200
            )
        ),
        GeoBypassNode(
            countryCode = "US",
            countryName = "Estados Unidos (East US)",
            integratedBypassIp = "4.2.2.2",
            market = "US",
            locale = "es-US",
            timezoneOffsetMinutes = -300,
            bufferProfile = NetworkBufferProfile(
                targetJitterBufferMs = 16,
                maxJitterBufferMs = 48,
                nackGraceWindowMs = 6,
                rtpReorderRingSize = 512,
                keyframeCooldownMs = 1300
            )
        )
    )

    fun resolveOptimalNode(userCountryOrSelectedCode: String = "CL", customAppIpOverride: String? = null): GeoBypassNode {
        val targetCode = when (userCountryOrSelectedCode.uppercase()) {
            "CL", "AR", "UY", "PY", "BO", "PE" -> "AR"
            "BR" -> "BR"
            "CO", "EC", "MX", "CR", "PA" -> "MX"
            else -> "AR"
        }

        val baseNode = integratedNodes.firstOrNull { it.countryCode == targetCode } ?: integratedNodes.first()

        return if (!customAppIpOverride.isNullOrBlank()) {
            baseNode.copy(integratedBypassIp = customAppIpOverride)
        } else {
            baseNode
        }
    }

    fun selectBestServerFromGssvRegions(
        gssvLoginResponseJson: String,
        measuredRttByRegion: Map<String, Int>
    ): Pair<String, String> {
        val root = JSONObject(gssvLoginResponseJson)
        val regions = root.getJSONObject("offeringSettings").getJSONArray("regions")

        var bestName = ""
        var bestBaseUri = ""
        var bestRtt = Int.MAX_VALUE

        for (i in 0 until regions.length()) {
            val reg = regions.getJSONObject(i)
            val name = reg.getString("name")
            val baseUri = reg.getString("baseUri")
            val isDefault = reg.optBoolean("isDefault", false)
            val rtt = measuredRttByRegion[name] ?: if (isDefault) 1 else 9999

            if (rtt < bestRtt) {
                bestRtt = rtt
                bestName = name
                bestBaseUri = baseUri
            }
        }
        return Pair(bestName, bestBaseUri)
    }
}
