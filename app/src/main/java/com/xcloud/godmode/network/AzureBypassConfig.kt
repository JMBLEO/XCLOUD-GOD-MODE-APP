package com.xcloud.godmode.network

import com.xcloud.godmode.models.SettingsModel

/**
 * AzureBypassConfig: Central native network engine configuration for Azure Data Centers,
 * Regional ISPs, and X-Forwarded-For Bypass endpoints.
 *
 * Implements low-latency routing profiles and zero-trust IP spoofing configuration
 * organized by region, datacenter, and ISP infrastructure.
 */
enum class BypassCategory(val label: String, val icon: String) {
    ALL("Todas", "🌐"),
    AZURE_DATACENTER("Azure Data Centers", "☁️"),
    REGIONAL_ISP("ISPs Regionales (Fibra)", "⚡"),
    CLOUD_EDGE("Cloud Edges (GCP/AWS/CF)", "🚀"),
    CUSTOM("IP Personalizada", "⚙️")
}

data class AzureBypassEntry(
    val id: String,
    val displayName: String,
    val ip: String,
    val regionId: String,
    val country: String,
    val category: BypassCategory,
    val provider: String,
    val estimatedRttMs: Int = 0
)

object AzureBypassConfig {

    val ENTRIES: List<AzureBypassEntry> = listOf(
        // Desactivado
        AzureBypassEntry(
            id = "disabled",
            displayName = "Desactivado",
            ip = "",
            regionId = "Global",
            country = "Global",
            category = BypassCategory.ALL,
            provider = "None"
        ),

        // --- LATINOAMÉRICA (CHILE, BRASIL, ARGENTINA, COLOMBIA, MÉXICO) ---
        // Chile
        AzureBypassEntry(
            id = "cl_azure_chile_central",
            displayName = "🇨🇱 [MS Azure] Chile Central (Santiago - Quilicura)",
            ip = "20.143.92.1",
            regionId = "ChileCentral",
            country = "Chile",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 12
        ),
        AzureBypassEntry(
            id = "cl_azure_edge_pasarela",
            displayName = "🇨🇱 [MS Azure] Chile Edge (Santiago Pasarela)",
            ip = "151.206.123.10",
            regionId = "ChileCentral",
            country = "Chile",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 15
        ),
        AzureBypassEntry(
            id = "cl_isp_entel",
            displayName = "🇨🇱 [ISP] Entel Chile (Fibra Nacional)",
            ip = "200.83.128.1",
            regionId = "ChileCentral",
            country = "Chile",
            category = BypassCategory.REGIONAL_ISP,
            provider = "Entel Chile",
            estimatedRttMs = 14
        ),
        AzureBypassEntry(
            id = "cl_isp_vtr_claro",
            displayName = "🇨🇱 [ISP] VTR / Claro Chile (Enlace Metropolitano)",
            ip = "190.160.0.1",
            regionId = "ChileCentral",
            country = "Chile",
            category = BypassCategory.REGIONAL_ISP,
            provider = "VTR / Claro",
            estimatedRttMs = 16
        ),
        AzureBypassEntry(
            id = "cl_isp_gtd",
            displayName = "🇨🇱 [ISP] GTD Manquehue (Baja Latencia Stgo)",
            ip = "200.72.0.1",
            regionId = "ChileCentral",
            country = "Chile",
            category = BypassCategory.REGIONAL_ISP,
            provider = "GTD Manquehue",
            estimatedRttMs = 13
        ),
        AzureBypassEntry(
            id = "cl_isp_movistar",
            displayName = "🇨🇱 [ISP] Telefónica / Movistar Chile",
            ip = "200.75.0.1",
            regionId = "ChileCentral",
            country = "Chile",
            category = BypassCategory.REGIONAL_ISP,
            provider = "Movistar Chile",
            estimatedRttMs = 15
        ),
        AzureBypassEntry(
            id = "cl_isp_wom",
            displayName = "🇨🇱 [ISP] Wom Chile (Enlace de Datos)",
            ip = "181.42.0.1",
            regionId = "ChileCentral",
            country = "Chile",
            category = BypassCategory.REGIONAL_ISP,
            provider = "Wom Chile",
            estimatedRttMs = 18
        ),
        AzureBypassEntry(
            id = "cl_gcp_santiago",
            displayName = "🇨🇱 [GCP] Google Cloud Chile (Santiago Node)",
            ip = "34.176.0.1",
            regionId = "ChileCentral",
            country = "Chile",
            category = BypassCategory.CLOUD_EDGE,
            provider = "Google Cloud",
            estimatedRttMs = 15
        ),
        AzureBypassEntry(
            id = "cl_aws_santiago",
            displayName = "🇨🇱 [AWS] Amazon Web Services Chile (Santiago Edge)",
            ip = "15.228.0.1",
            regionId = "ChileCentral",
            country = "Chile",
            category = BypassCategory.CLOUD_EDGE,
            provider = "Amazon Web Services",
            estimatedRttMs = 16
        ),
        AzureBypassEntry(
            id = "cl_cf_santiago",
            displayName = "🇨🇱 [Cloudflare] Chile Edge Node (Santiago)",
            ip = "104.18.0.1",
            regionId = "ChileCentral",
            country = "Chile",
            category = BypassCategory.CLOUD_EDGE,
            provider = "Cloudflare",
            estimatedRttMs = 14
        ),

        // Brasil
        AzureBypassEntry(
            id = "br_azure_brazil_south",
            displayName = "🇧🇷 [MS Azure] Brazil South (São Paulo State)",
            ip = "169.150.198.66",
            regionId = "BrazilSouth",
            country = "Brasil",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 28
        ),
        AzureBypassEntry(
            id = "br_gcp_saopaulo",
            displayName = "🇧🇷 [GCP] Google Cloud Brasil (São Paulo)",
            ip = "34.151.0.1",
            regionId = "BrazilSouth",
            country = "Brasil",
            category = BypassCategory.CLOUD_EDGE,
            provider = "Google Cloud",
            estimatedRttMs = 30
        ),
        AzureBypassEntry(
            id = "br_aws_saopaulo",
            displayName = "🇧🇷 [AWS] Amazon Web Services Brasil (São Paulo)",
            ip = "18.228.0.1",
            regionId = "BrazilSouth",
            country = "Brasil",
            category = BypassCategory.CLOUD_EDGE,
            provider = "Amazon Web Services",
            estimatedRttMs = 31
        ),
        AzureBypassEntry(
            id = "br_isp_claro",
            displayName = "🇧🇷 [ISP] Claro BR (São Paulo Edge)",
            ip = "177.54.148.163",
            regionId = "BrazilSouth",
            country = "Brasil",
            category = BypassCategory.REGIONAL_ISP,
            provider = "Claro Brasil",
            estimatedRttMs = 29
        ),
        AzureBypassEntry(
            id = "br_isp_vivo",
            displayName = "🇧🇷 [ISP] Vivo BR (São Paulo Backbone)",
            ip = "177.10.128.1",
            regionId = "BrazilSouth",
            country = "Brasil",
            category = BypassCategory.REGIONAL_ISP,
            provider = "Vivo Brasil",
            estimatedRttMs = 30
        ),
        AzureBypassEntry(
            id = "br_direct_2",
            displayName = "🇧🇷 Brasil 2 (177.54.148.163)",
            ip = "177.54.148.163",
            regionId = "BrazilSouth",
            country = "Brasil",
            category = BypassCategory.REGIONAL_ISP,
            provider = "Claro Brasil"
        ),
        AzureBypassEntry(
            id = "br_direct_3",
            displayName = "🇧🇷 Brasil 3 (177.10.128.1)",
            ip = "177.10.128.1",
            regionId = "BrazilSouth",
            country = "Brasil",
            category = BypassCategory.REGIONAL_ISP,
            provider = "Vivo Brasil"
        ),

        // Argentina
        AzureBypassEntry(
            id = "ar_azure_mendoza",
            displayName = "🇦🇷 [MS Azure] Argentina - Mendoza / Cuyo (Enlace Transandino)",
            ip = "190.221.180.1",
            regionId = "ChileCentral",
            country = "Argentina",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure Transandino",
            estimatedRttMs = 24
        ),
        AzureBypassEntry(
            id = "ar_gcp_baires",
            displayName = "🇦🇷 [GCP] Google Cloud Argentina (Buenos Aires Node)",
            ip = "34.175.0.1",
            regionId = "ChileCentral",
            country = "Argentina",
            category = BypassCategory.CLOUD_EDGE,
            provider = "Google Cloud",
            estimatedRttMs = 28
        ),
        AzureBypassEntry(
            id = "ar_isp_movistar",
            displayName = "🇦🇷 [ISP] Movistar Argentina (Baires)",
            ip = "181.114.212.34",
            regionId = "ChileCentral",
            country = "Argentina",
            category = BypassCategory.REGIONAL_ISP,
            provider = "Movistar Argentina",
            estimatedRttMs = 26
        ),
        AzureBypassEntry(
            id = "ar_isp_claro",
            displayName = "🇦🇷 [ISP] Claro Argentina (Nodos de Enlace)",
            ip = "190.111.246.128",
            regionId = "ChileCentral",
            country = "Argentina",
            category = BypassCategory.REGIONAL_ISP,
            provider = "Claro Argentina",
            estimatedRttMs = 27
        ),
        AzureBypassEntry(
            id = "ar_isp_telecentro",
            displayName = "🇦🇷 [ISP] Telecentro (Cuyo / Baires)",
            ip = "190.221.32.1",
            regionId = "ChileCentral",
            country = "Argentina",
            category = BypassCategory.REGIONAL_ISP,
            provider = "Telecentro",
            estimatedRttMs = 29
        ),
        AzureBypassEntry(
            id = "ar_isp_arsat",
            displayName = "🇦🇷 [ISP] Arsat (Red Troncal Nacional)",
            ip = "200.51.240.1",
            regionId = "ChileCentral",
            country = "Argentina",
            category = BypassCategory.REGIONAL_ISP,
            provider = "Arsat",
            estimatedRttMs = 32
        ),
        AzureBypassEntry(
            id = "ar_direct_1",
            displayName = "🇦🇷 Argentina 1 (186.124.22.114)",
            ip = "186.124.22.114",
            regionId = "ChileCentral",
            country = "Argentina",
            category = BypassCategory.REGIONAL_ISP,
            provider = "Telecom Argentina"
        ),
        AzureBypassEntry(
            id = "ar_direct_2",
            displayName = "🇦🇷 Argentina 2 (181.114.212.34)",
            ip = "181.114.212.34",
            regionId = "ChileCentral",
            country = "Argentina",
            category = BypassCategory.REGIONAL_ISP,
            provider = "Movistar Argentina"
        ),
        AzureBypassEntry(
            id = "ar_direct_3",
            displayName = "🇦🇷 Argentina 3 (190.111.246.128)",
            ip = "190.111.246.128",
            regionId = "ChileCentral",
            country = "Argentina",
            category = BypassCategory.REGIONAL_ISP,
            provider = "Claro Argentina"
        ),

        // Colombia
        AzureBypassEntry(
            id = "co_azure_bogota",
            displayName = "🇨🇴 [MS Azure] Colombia (Bogotá Edge)",
            ip = "186.80.12.1",
            regionId = "Colombia",
            country = "Colombia",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 35
        ),

        // México
        AzureBypassEntry(
            id = "mx_azure_queretaro",
            displayName = "🇲🇽 [MS Azure] Mexico Central (Querétaro State)",
            ip = "20.195.68.1",
            regionId = "MexicoCentral",
            country = "México",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 32
        ),
        AzureBypassEntry(
            id = "mx_isp_telmex",
            displayName = "🇲🇽 [ISP] Telmex / Infinitum (Fibra México)",
            ip = "187.190.0.1",
            regionId = "MexicoCentral",
            country = "México",
            category = BypassCategory.REGIONAL_ISP,
            provider = "Telmex Infinitum",
            estimatedRttMs = 30
        ),
        AzureBypassEntry(
            id = "mx_isp_totalplay",
            displayName = "🇲🇽 [ISP] Totalplay (Baja Latencia CDMX)",
            ip = "189.216.0.1",
            regionId = "MexicoCentral",
            country = "México",
            category = BypassCategory.REGIONAL_ISP,
            provider = "Totalplay",
            estimatedRttMs = 28
        ),

        // --- NORTEAMÉRICA (ESTADOS UNIDOS & CANADÁ) ---
        // Estados Unidos
        AzureBypassEntry(
            id = "us_azure_central_us",
            displayName = "🇺🇸 [MS Azure] Central US (Iowa)",
            ip = "52.173.18.128",
            regionId = "CentralUS",
            country = "Estados Unidos",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 42
        ),
        AzureBypassEntry(
            id = "us_azure_east_us",
            displayName = "🇺🇸 [MS Azure] East US (Virginia)",
            ip = "20.42.73.128",
            regionId = "EastUS",
            country = "Estados Unidos",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 45
        ),
        AzureBypassEntry(
            id = "us_azure_east_us_2",
            displayName = "🇺🇸 [MS Azure] East US 2 (Virginia)",
            ip = "20.39.81.128",
            regionId = "EastUS2",
            country = "Estados Unidos",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 44
        ),
        AzureBypassEntry(
            id = "us_azure_east_us_3",
            displayName = "🇺🇸 [MS Azure] East US 3 (Georgia)",
            ip = "20.50.81.128",
            regionId = "EastUS2",
            country = "Estados Unidos",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 46
        ),
        AzureBypassEntry(
            id = "us_azure_north_central_us",
            displayName = "🇺🇸 [MS Azure] North Central US (Illinois)",
            ip = "20.49.106.128",
            regionId = "NorthCentralUS",
            country = "Estados Unidos",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 43
        ),
        AzureBypassEntry(
            id = "us_azure_south_central_us",
            displayName = "🇺🇸 [MS Azure] South Central US (Texas)",
            ip = "20.45.18.128",
            regionId = "SouthCentralUS",
            country = "Estados Unidos",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 38
        ),
        AzureBypassEntry(
            id = "us_azure_west_central_us",
            displayName = "🇺🇸 [MS Azure] West Central US (Wyoming)",
            ip = "52.247.213.128",
            regionId = "WestCentralUS",
            country = "Estados Unidos",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 50
        ),
        AzureBypassEntry(
            id = "us_azure_west_us",
            displayName = "🇺🇸 [MS Azure] West US (California)",
            ip = "13.89.204.1",
            regionId = "WestUS",
            country = "Estados Unidos",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 48
        ),
        AzureBypassEntry(
            id = "us_azure_west_us_2",
            displayName = "🇺🇸 [MS Azure] West US 2 (Washington)",
            ip = "20.46.81.128",
            regionId = "WestUS2",
            country = "Estados Unidos",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 52
        ),
        AzureBypassEntry(
            id = "us_azure_west_us_3",
            displayName = "🇺🇸 [MS Azure] West US 3 (Arizona)",
            ip = "20.150.81.128",
            regionId = "WestUS3",
            country = "Estados Unidos",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 42
        ),
        AzureBypassEntry(
            id = "us_azure_global_dns",
            displayName = "🇺🇸 [MS Azure] EE.UU. (4.2.2.2 - Global DNS/Opt)",
            ip = "4.2.2.2",
            regionId = "EastUS",
            country = "Estados Unidos",
            category = BypassCategory.CLOUD_EDGE,
            provider = "Level3 / Microsoft",
            estimatedRttMs = 35
        ),
        AzureBypassEntry(
            id = "us_aws_east_virginia",
            displayName = "🇺🇸 [AWS] EE.UU. East (Virginia)",
            ip = "3.80.0.1",
            regionId = "EastUS",
            country = "Estados Unidos",
            category = BypassCategory.CLOUD_EDGE,
            provider = "Amazon Web Services",
            estimatedRttMs = 45
        ),
        AzureBypassEntry(
            id = "us_direct_2",
            displayName = "🇺🇸 EE.UU. 2 (143.244.47.65)",
            ip = "143.244.47.65",
            regionId = "EastUS",
            country = "Estados Unidos",
            category = BypassCategory.REGIONAL_ISP,
            provider = "DigitalOcean US"
        ),
        AzureBypassEntry(
            id = "us_direct_3",
            displayName = "🇺🇸 EE.UU. 3 (185.122.204.197)",
            ip = "185.122.204.197",
            regionId = "WestUS",
            country = "Estados Unidos",
            category = BypassCategory.REGIONAL_ISP,
            provider = "G-Core Labs US"
        ),

        // Canadá
        AzureBypassEntry(
            id = "ca_azure_canada_central",
            displayName = "🇨🇦 [MS Azure] Canada Central (Toronto)",
            ip = "20.48.224.128",
            regionId = "CanadaCentral",
            country = "Canadá",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 48
        ),
        AzureBypassEntry(
            id = "ca_azure_canada_east",
            displayName = "🇨🇦 [MS Azure] Canada East (Quebec City)",
            ip = "52.229.123.128",
            regionId = "CanadaEast",
            country = "Canadá",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 50
        ),

        // --- EUROPA ---
        AzureBypassEntry(
            id = "fr_azure_france_central",
            displayName = "🇫🇷 [MS Azure] France Central (Paris)",
            ip = "20.190.155.128",
            regionId = "FranceCentral",
            country = "Francia",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 20
        ),
        AzureBypassEntry(
            id = "fr_azure_france_south",
            displayName = "🇫🇷 [MS Azure] France South (Marseille)",
            ip = "40.66.57.128",
            regionId = "FranceSouth",
            country = "Francia",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 24
        ),
        AzureBypassEntry(
            id = "de_azure_germany_north",
            displayName = "🇩🇪 [MS Azure] Germany North (Bremen)",
            ip = "51.116.146.128",
            regionId = "GermanyNorth",
            country = "Alemania",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 22
        ),
        AzureBypassEntry(
            id = "de_azure_germany_west_central",
            displayName = "🇩🇪 [MS Azure] Germany West Central (Frankfurt)",
            ip = "51.116.57.128",
            regionId = "GermanyWestCentral",
            country = "Alemania",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 18
        ),
        AzureBypassEntry(
            id = "nl_azure_west_europe",
            displayName = "🇳🇱 [MS Azure] West Europe (North Holland)",
            ip = "20.31.25.128",
            regionId = "WestEurope",
            country = "Países Bajos",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 19
        ),
        AzureBypassEntry(
            id = "pt_azure_europa_portugal",
            displayName = "🇪🇺 [MS Azure] Europa / Portugal (Lisboa Edge)",
            ip = "194.25.0.68",
            regionId = "WestEurope",
            country = "Portugal",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 25
        ),
        AzureBypassEntry(
            id = "no_azure_norway_east",
            displayName = "🇳🇴 [MS Azure] Norway East (Oslo)",
            ip = "51.120.219.128",
            regionId = "NorwayEast",
            country = "Noruega",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 28
        ),
        AzureBypassEntry(
            id = "no_azure_norway_west",
            displayName = "🇳🇴 [MS Azure] Norway West (Stavanger)",
            ip = "51.120.106.128",
            regionId = "NorwayWest",
            country = "Noruega",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 30
        ),
        AzureBypassEntry(
            id = "pl_azure_poland_central",
            displayName = "🇵🇱 [MS Azure] Poland Central (Warsaw)",
            ip = "45.134.212.66",
            regionId = "PolandCentral",
            country = "Polonia",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 25
        ),
        AzureBypassEntry(
            id = "se_azure_sweden_central",
            displayName = "🇸🇪 [MS Azure] Sweden Central (Gävle)",
            ip = "51.12.213.128",
            regionId = "SwedenCentral",
            country = "Suecia",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 26
        ),
        AzureBypassEntry(
            id = "ch_azure_switzerland_north",
            displayName = "🇨🇭 [MS Azure] Switzerland North (Zurich)",
            ip = "51.107.57.128",
            regionId = "SwitzerlandNorth",
            country = "Suiza",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 21
        ),
        AzureBypassEntry(
            id = "ch_azure_switzerland_west",
            displayName = "🇨🇭 [MS Azure] Switzerland West (Geneva)",
            ip = "51.107.169.128",
            regionId = "SwitzerlandWest",
            country = "Suiza",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 23
        ),
        AzureBypassEntry(
            id = "uk_azure_uk_south",
            displayName = "🇬🇧 [MS Azure] UK South (London)",
            ip = "20.33.26.128",
            regionId = "UKSouth",
            country = "Reino Unido",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 17
        ),
        AzureBypassEntry(
            id = "uk_azure_uk_west",
            displayName = "🇬🇧 [MS Azure] UK West (Cardiff)",
            ip = "51.140.231.128",
            regionId = "UKWest",
            country = "Reino Unido",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 21
        ),
        AzureBypassEntry(
            id = "uk_direct_1",
            displayName = "🇬🇧 Reino Unido (88.215.47.1)",
            ip = "88.215.47.1",
            regionId = "UKSouth",
            country = "Reino Unido",
            category = BypassCategory.REGIONAL_ISP,
            provider = "BT Openreach UK"
        ),

        // --- ASIA PACÍFICO & OCEANÍA ---
        // Australia
        AzureBypassEntry(
            id = "au_azure_australia_east",
            displayName = "🇦🇺 [MS Azure] Australia East (New South Wales)",
            ip = "20.53.111.128",
            regionId = "AustraliaEast",
            country = "Australia",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 22
        ),
        AzureBypassEntry(
            id = "au_azure_australia_south_east",
            displayName = "🇦🇺 [MS Azure] Australia South East (Victoria)",
            ip = "20.193.18.128",
            regionId = "AustraliaSouthEast",
            country = "Australia",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 25
        ),

        // India
        AzureBypassEntry(
            id = "in_azure_central_india",
            displayName = "🇮🇳 [MS Azure] Central India (Pune)",
            ip = "20.192.115.128",
            regionId = "CentralIndia",
            country = "India",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 38
        ),
        AzureBypassEntry(
            id = "in_azure_south_india",
            displayName = "🇮🇳 [MS Azure] South India (Chennai)",
            ip = "20.192.203.128",
            regionId = "SouthIndia",
            country = "India",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 40
        ),

        // Japón
        AzureBypassEntry(
            id = "jp_azure_japan_east",
            displayName = "🇯🇵 [MS Azure] Japan East (Tokyo, Saitama)",
            ip = "210.131.113.123",
            regionId = "JapanEast",
            country = "Japón",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 16
        ),
        AzureBypassEntry(
            id = "jp_azure_japan_west",
            displayName = "🇯🇵 [MS Azure] Japan West (Osaka)",
            ip = "20.43.149.128",
            regionId = "JapanWest",
            country = "Japón",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 19
        ),
        AzureBypassEntry(
            id = "jp_direct_2",
            displayName = "🇯🇵 Japón 2 (138.199.21.239)",
            ip = "138.199.21.239",
            regionId = "JapanEast",
            country = "Japón",
            category = BypassCategory.REGIONAL_ISP,
            provider = "NTT Communications"
        ),

        // Corea del Sur
        AzureBypassEntry(
            id = "kr_azure_korea_central",
            displayName = "🇰🇷 [MS Azure] Korea Central (Seoul)",
            ip = "168.126.63.1",
            regionId = "KoreaCentral",
            country = "Corea del Sur",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "KT Corporation / Azure",
            estimatedRttMs = 12
        ),
        AzureBypassEntry(
            id = "kr_azure_korea_south",
            displayName = "🇰🇷 [MS Azure] Korea South (Busan)",
            ip = "20.194.240.128",
            regionId = "KoreaSouth",
            country = "Corea del Sur",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 15
        ),
        AzureBypassEntry(
            id = "kr_direct_2",
            displayName = "🇰🇷 Corea 2 (121.125.60.151)",
            ip = "121.125.60.151",
            regionId = "KoreaCentral",
            country = "Corea del Sur",
            category = BypassCategory.REGIONAL_ISP,
            provider = "SK Telecom"
        ),
        AzureBypassEntry(
            id = "kr_direct_3",
            displayName = "🇰🇷 Corea 3 (211.234.110.151)",
            ip = "211.234.110.151",
            regionId = "KoreaCentral",
            country = "Corea del Sur",
            category = BypassCategory.REGIONAL_ISP,
            provider = "LG Uplus"
        ),

        // Oriente Medio & África
        AzureBypassEntry(
            id = "ae_azure_uae_central",
            displayName = "🇦🇪 [MS Azure] UAE Central (Abu Dhabi)",
            ip = "20.195.111.128",
            regionId = "UAECentral",
            country = "Emiratos Árabes",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 35
        ),
        AzureBypassEntry(
            id = "ae_azure_uae_north",
            displayName = "🇦🇪 [MS Azure] UAE North (Dubai)",
            ip = "20.195.148.128",
            regionId = "UAENorth",
            country = "Emiratos Árabes",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 34
        ),
        AzureBypassEntry(
            id = "za_azure_south_africa_north",
            displayName = "🇿🇦 [MS Azure] South Africa North (Johannesburg)",
            ip = "102.133.155.128",
            regionId = "SouthAfricaNorth",
            country = "Sudáfrica",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 45
        ),
        AzureBypassEntry(
            id = "za_azure_south_africa_west",
            displayName = "🇿🇦 [MS Azure] South Africa West (Cape Town)",
            ip = "102.133.242.128",
            regionId = "SouthAfricaWest",
            country = "Sudáfrica",
            category = BypassCategory.AZURE_DATACENTER,
            provider = "Microsoft Azure",
            estimatedRttMs = 48
        ),

        // Custom IP
        AzureBypassEntry(
            id = "custom_ip",
            displayName = "⚙️ IP Personalizada",
            ip = "custom",
            regionId = "Custom",
            country = "Custom",
            category = BypassCategory.CUSTOM,
            provider = "Usuario"
        )
    )

    /**
     * Map representation for seamless interoperability with legacy and map-based readers.
     */
    val AS_MAP: Map<String, String> by lazy {
        ENTRIES.associate { it.displayName to it.ip }
    }

    /**
     * Filters entries by text search query and optional category.
     */
    fun filterEntries(query: String, category: BypassCategory? = null): List<AzureBypassEntry> {
        val trimmed = query.trim()
        return ENTRIES.filter { entry ->
            val matchesCategory = category == null || category == BypassCategory.ALL || entry.category == category
            val matchesQuery = trimmed.isEmpty() ||
                    entry.displayName.contains(trimmed, ignoreCase = true) ||
                    entry.ip.contains(trimmed, ignoreCase = true) ||
                    entry.country.contains(trimmed, ignoreCase = true) ||
                    entry.provider.contains(trimmed, ignoreCase = true)
            matchesCategory && matchesQuery
        }
    }

    /**
     * Resolves recommended bypass IPs for a given Azure server location (e.g. "ChileCentral", "BrazilSouth").
     */
    fun getRecommendedEntriesForServer(serverId: String): List<AzureBypassEntry> {
        if (serverId.equals("Automático", ignoreCase = true) || serverId.isBlank()) {
            return ENTRIES
        }
        val directMatches = ENTRIES.filter { it.regionId.equals(serverId, ignoreCase = true) }
        val otherEntries = ENTRIES.filter { !it.regionId.equals(serverId, ignoreCase = true) && it.id != "disabled" && it.id != "custom_ip" }
        return directMatches + otherEntries
    }

    /**
     * Returns the human-readable display label for a specific IP string.
     */
    fun getLabelForIp(ip: String): String {
        if (ip.isBlank() || ip.equals("Desactivado", ignoreCase = true)) return "Desactivado"
        if (ip == "custom") return "IP Personalizada"
        return ENTRIES.firstOrNull { it.ip == ip }?.displayName ?: ip
    }

    /**
     * Resolves the actual IP string to send in HTTP X-Forwarded-For headers or WebRTC routing.
     * Returns null or empty string if bypass is disabled.
     */
    fun resolveEffectiveBypassIp(settings: SettingsModel): String? {
        if (!settings.enableNetworkBypass) return null
        val rawIp = settings.bypassIp.trim()
        if (rawIp.isEmpty() || rawIp.equals("Desactivado", ignoreCase = true)) return null
        if (rawIp == "custom") {
            val custom = settings.customIp.trim()
            return custom.ifEmpty { null }
        }
        return rawIp
    }
}
