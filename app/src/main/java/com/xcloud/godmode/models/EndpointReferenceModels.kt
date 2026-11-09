package com.xcloud.godmode.models

enum class InternetProtocolVersion(val displayName: String) {
    IPV4("IPv4"),
    IPV6("IPv6")
}

enum class EndpointTrust(val displayName: String) {
    USER_DEFINED("Definido por el usuario"),
    DYNAMICALLY_VALIDATED("Validado dinámicamente"),
    UNVERIFIED_REFERENCE("Referencia no verificada")
}

enum class EndpointSelectionState {
    SELECTABLE,
    DIAGNOSTIC_ONLY,
    BLOCKED
}

data class NetworkAddressInfo(
    val address: String,
    val protocolVersion: InternetProtocolVersion
)

data class BypassEndpointReference(
    val id: String,
    val displayName: String,
    val countryCode: String?,
    val regionId: String?,
    val providerLabel: String?,
    val address: NetworkAddressInfo?,
    val trust: EndpointTrust,
    val selectionState: EndpointSelectionState,
    val sourceNote: String
) {
    val selectable: Boolean
        get() = selectionState == EndpointSelectionState.SELECTABLE
}
