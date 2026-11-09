package com.xcloud.godmode.models

enum class StreamQualityTier(val displayName: String) {
    UNAVAILABLE("No disponible"),
    LIMITED("Limitado"),
    STANDARD("Estándar (720p)"),
    HIGH("Alta (1080p 60fps)"),
    ULTRA("Ultra (4K HDR)")
}

enum class MetricEvidence(val displayName: String) {
    MEASURED_AGAINST_SELECTED_SERVER("Medida contra el servidor seleccionado"),
    DECLARED_BY_ACTIVE_NETWORK("Capacidad declarada del enlace"),
    DERIVED_ESTIMATE("Estimación derivada"),
    UNAVAILABLE("Sin evidencia / No medida")
}

data class StreamConnectionMetrics(
    val latencyMs: Double?,
    val latencyEvidence: MetricEvidence,

    val downstreamMbps: Double?,
    val upstreamMbps: Double?,
    val bandwidthEvidence: MetricEvidence,

    val estimatedQuality: StreamQualityTier,
    val qualityEvidence: MetricEvidence,

    val selectedServerReachable: Boolean,
    val measuredAtEpochMs: Long?,
    val measurementNote: String
)

enum class BypassMode(val displayName: String) {
    DISABLED("Desactivado"),
    VALIDATED_REFERENCE("Referencia validada"),
    CUSTOM_ADDRESS("IP personalizada")
}

data class BypassConfiguration(
    val mode: BypassMode,
    val enabled: Boolean,
    val referenceId: String?,
    val displayName: String,
    val countryCode: String?,
    val address: NetworkAddressInfo?,
    val trust: EndpointTrust
)

data class StreamRootConfiguration(
    val bypass: BypassConfiguration,
    val server: StreamServerConfiguration
)

data class StreamConfigurationState(
    val applied: StreamRootConfiguration,
    val pending: StreamRootConfiguration?,
    val restartRequired: Boolean
)

enum class RegionSelectionMode(val displayName: String) {
    AUTOMATIC_VALIDATED("Automático validado"),
    SOUTH_AMERICA_PREFERRED("Sudamérica adaptativa"),
    MANUAL_PREFERENCE("Preferencia manual")
}

data class RegionPreference(
    val mode: RegionSelectionMode,
    val preferredRegionIds: List<String>
)

enum class ServerSelectionMode(val displayName: String) {
    AUTOMATIC_VALIDATED("Selección automática validada"),
    REGIONAL_PREFERENCE("Preferencia regional"),
    CUSTOM_HOST("Host personalizado"),
    DIAGNOSTIC_REFERENCE("Referencia de diagnóstico")
}

enum class AppliedServerStatus(val displayName: String) {
    AUTOMATIC_PENDING_DISCOVERY("Pendiente de descubrimiento"),
    RESOLVING("Resolviendo..."),
    RESOLVED("Resuelto"),
    REACHABLE("Alcanzable"),
    UNREACHABLE("Inalcanzable"),
    INVALID("Inválido")
}

data class StreamServerConfiguration(
    val selectionMode: ServerSelectionMode,
    val serverId: String,
    val regionId: String?,
    val displayName: String,
    val countryCode: String?,
    val hostName: String?,
    val port: Int?,
    val resolvedAddress: NetworkAddressInfo?,
    val status: AppliedServerStatus,
    val trust: EndpointTrust
)

enum class CodecPreference(val displayName: String, val description: String) {
    AUTO_MAXIMUM_VERIFIED(
        "Detección automática verificada",
        "Selecciona el mejor formato completo acreditado por la oferta remota y el dispositivo (AV1 -> HEVC 10-bit -> HEVC 8-bit)."
    ),
    AV1_HARDWARE(
        "AV1 por hardware",
        "Acepta únicamente ofertas AV1 completas con decodificador por hardware."
    ),
    HEVC_MAIN10_HARDWARE(
        "HEVC Main10 por hardware",
        "Acepta únicamente ofertas HEVC de 10 bits por hardware (HDR/WCG)."
    ),
    HEVC_MAIN_HARDWARE(
        "HEVC Main por hardware",
        "Acepta únicamente ofertas HEVC estándar de 8 bits por hardware."
    ),
    VVC_EXPERIMENTAL(
        "VVC / H.266 experimental",
        "Formato experimental reservado para pruebas de laboratorio con validador activo."
    )
}

enum class StoreLocalePreference(val displayName: String, val localeCode: String) {
    SYSTEM_DEFAULT("Predeterminado del sistema", "default"),
    SPANISH_LATAM("Español (Latinoamérica)", "es-MX"),
    SPANISH_SPAIN("Español (España)", "es-ES"),
    ENGLISH_US("English (United States)", "en-US"),
    PORTUGUESE_BRAZIL("Português (Brasil)", "pt-BR")
}

data class RootApplicationConfiguration(
    val regionPreference: RegionPreference,
    val server: StreamServerConfiguration,
    val bypass: BypassConfiguration,
    val storeLocale: StoreLocalePreference,
    val codecPreference: CodecPreference
)

enum class PendingConfigurationField {
    REGION,
    SERVER,
    BYPASS,
    STORE_LOCALE,
    CODEC
}

data class RootConfigurationState(
    val applied: RootApplicationConfiguration,
    val pending: RootApplicationConfiguration?,
    val pendingFields: Set<PendingConfigurationField>,
    val restartRequired: Boolean
)

data class VerifiedStreamQuality(
    val width: Int,
    val height: Int,
    val frameRate: Double,
    val mimeType: String,
    val bitDepth: Int,
    val decoderName: String,
    val verified: Boolean
)

sealed interface ApplicationRestartAction {
    data object Requested : ApplicationRestartAction
    data object Deferred : ApplicationRestartAction
}

fun countryFlagEmoji(countryCode: String?): String {
    if (countryCode.isNullOrBlank() || countryCode.length != 2) return "🌐"
    val firstChar = Character.codePointAt(countryCode.uppercase(), 0) - 0x41 + 0x1F1E6
    val secondChar = Character.codePointAt(countryCode.uppercase(), 1) - 0x41 + 0x1F1E6
    return String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
}
