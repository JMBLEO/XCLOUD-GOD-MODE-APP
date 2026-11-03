package com.xcloud.godmode.constants

/**
 * NetworkBypassCatalog: Centralized verified global locations and X-Forwarded-For IP bypass catalog.
 */
data class ServerRegion(
    val id: String,
    val name: String,
    val flag: String,
    val country: String
)

object NetworkBypassCatalog {
    val CLOUD_SERVERS = listOf(
        ServerRegion("Automático", "Automático (Selección Óptima de Azure)", "🌐", "Global"),
        ServerRegion("AustraliaEast", "Australia East (New South Wales)", "🇦🇺", "Australia"),
        ServerRegion("AustraliaSouthEast", "Australia South East (Victoria)", "🇦🇺", "Australia"),
        ServerRegion("BrazilSouth", "Brazil South (São Paulo State)", "🇧🇷", "Brasil"),
        ServerRegion("CanadaCentral", "Canada Central (Toronto)", "🇨🇦", "Canadá"),
        ServerRegion("CanadaEast", "Canada East (Quebec City)", "🇨🇦", "Canadá"),
        ServerRegion("ChileCentral", "Chile Central (Santiago - Quilicura)", "🇨🇱", "Chile"),
        ServerRegion("Colombia", "Colombia (Bogotá Edge)", "🇨🇴", "Colombia"),
        ServerRegion("FranceCentral", "France Central (Paris)", "🇫🇷", "Francia"),
        ServerRegion("FranceSouth", "France South (Marseille)", "🇫🇷", "Francia"),
        ServerRegion("GermanyNorth", "Germany North (Bremen)", "🇩🇪", "Alemania"),
        ServerRegion("GermanyWestCentral", "Germany West Central (Frankfurt)", "🇩🇪", "Alemania"),
        ServerRegion("CentralIndia", "Central India (Pune)", "🇮🇳", "India"),
        ServerRegion("SouthIndia", "South India (Chennai)", "🇮🇳", "India"),
        ServerRegion("JapanEast", "Japan East (Tokyo / Saitama)", "🇯🇵", "Japón"),
        ServerRegion("JapanWest", "Japan West (Osaka)", "🇯🇵", "Japón"),
        ServerRegion("KoreaCentral", "Korea Central (Seoul)", "🇰🇷", "Corea del Sur"),
        ServerRegion("KoreaSouth", "Korea South (Busan)", "🇰🇷", "Corea del Sur"),
        ServerRegion("MexicoCentral", "Mexico Central (Querétaro State)", "🇲🇽", "México"),
        ServerRegion("NorwayEast", "Norway East (Oslo)", "🇳🇴", "Noruega"),
        ServerRegion("PolandCentral", "Poland Central (Warsaw)", "🇵🇱", "Polonia"),
        ServerRegion("SouthAfricaNorth", "South Africa North (Johannesburg)", "🇿🇦", "Sudáfrica"),
        ServerRegion("SwedenCentral", "Sweden Central (Gävle)", "🇸🇪", "Suecia"),
        ServerRegion("SwitzerlandNorth", "Switzerland North (Zurich)", "🇨🇭", "Suiza"),
        ServerRegion("UKSouth", "UK South (London)", "🇬🇧", "Reino Unido"),
        ServerRegion("UKWest", "UK West (Cardiff)", "🇬🇧", "Reino Unido"),
        ServerRegion("EastUS", "East US (Virginia)", "🇺🇸", "Estados Unidos"),
        ServerRegion("EastUS2", "East US 2 (Virginia)", "🇺🇸", "Estados Unidos"),
        ServerRegion("WestUS", "West US (California)", "🇺🇸", "Estados Unidos"),
        ServerRegion("WestUS2", "West US 2 (Washington)", "🇺🇸", "Estados Unidos"),
        ServerRegion("WestUS3", "West US 3 (Arizona)", "🇺🇸", "Estados Unidos"),
        ServerRegion("NorthCentralUS", "North Central US (Illinois)", "🇺🇸", "Estados Unidos"),
        ServerRegion("SouthCentralUS", "South Central US (Texas)", "🇺🇸", "Estados Unidos"),
        ServerRegion("WestCentralUS", "West Central US (Wyoming)", "🇺🇸", "Estados Unidos")
    )

    val BYPASS_IP_CATALOG: Map<String, String> get() = com.xcloud.godmode.network.AzureBypassConfig.AS_MAP

    val STORE_LOCALES = mapOf(
        "Argentina" to "es-AR",
        "Australia" to "en-AU",
        "Brasil" to "pt-BR",
        "Chile" to "es-CL",
        "Colombia" to "es-CO",
        "Estados Unidos" to "en-US",
        "España" to "es-ES",
        "Francia" to "fr-FR",
        "Alemania" to "de-DE",
        "Japón" to "ja-JP",
        "México" to "es-MX",
        "Reino Unido" to "en-GB"
    )

    val SERVER_TO_COUNTRY = mapOf(
        "AustraliaEast" to "Australia",
        "AustraliaSouthEast" to "Australia",
        "BrazilSouth" to "Brasil",
        "CanadaCentral" to "Canadá",
        "CanadaEast" to "Canadá",
        "ChileCentral" to "Chile",
        "Colombia" to "Colombia",
        "FranceCentral" to "Francia",
        "FranceSouth" to "Francia",
        "GermanyNorth" to "Alemania",
        "GermanyWestCentral" to "Alemania",
        "CentralIndia" to "India",
        "SouthIndia" to "India",
        "JapanEast" to "Japón",
        "JapanWest" to "Japón",
        "KoreaCentral" to "Corea del Sur",
        "KoreaSouth" to "Corea del Sur",
        "MexicoCentral" to "México",
        "NorwayEast" to "Noruega",
        "PolandCentral" to "Polonia",
        "SouthAfricaNorth" to "Sudáfrica",
        "SwedenCentral" to "Suecia",
        "SwitzerlandNorth" to "Suiza",
        "UKSouth" to "Reino Unido",
        "UKWest" to "Reino Unido",
        "EastUS" to "Estados Unidos",
        "EastUS2" to "Estados Unidos",
        "WestUS" to "Estados Unidos",
        "WestUS2" to "Estados Unidos",
        "WestUS3" to "Estados Unidos",
        "NorthCentralUS" to "Estados Unidos",
        "SouthCentralUS" to "Estados Unidos",
        "WestCentralUS" to "Estados Unidos"
    )
}
