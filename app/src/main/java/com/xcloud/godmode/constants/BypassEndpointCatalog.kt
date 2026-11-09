package com.xcloud.godmode.constants

import com.xcloud.godmode.models.BypassEndpointReference
import com.xcloud.godmode.models.EndpointSelectionState
import com.xcloud.godmode.models.EndpointTrust
import com.xcloud.godmode.models.InternetProtocolVersion
import com.xcloud.godmode.models.NetworkAddressInfo

object BypassEndpointCatalog {

    val disabled = BypassEndpointReference(
        id = "bypass-disabled",
        displayName = "Desactivado",
        countryCode = null,
        regionId = null,
        providerLabel = null,
        address = null,
        trust = EndpointTrust.DYNAMICALLY_VALIDATED,
        selectionState = EndpointSelectionState.SELECTABLE,
        sourceNote = "Configuración predeterminada sin bypass."
    )

    val customAddress = BypassEndpointReference(
        id = "custom-address",
        displayName = "IP personalizada",
        countryCode = null,
        regionId = null,
        providerLabel = null,
        address = null,
        trust = EndpointTrust.USER_DEFINED,
        selectionState = EndpointSelectionState.SELECTABLE,
        sourceNote = "Dirección introducida y confirmada por el usuario."
    )

    val diagnosticReferences: List<BypassEndpointReference> = listOf(
        ipv4(
            id = "reference-au-east",
            displayName = "Australia East, New South Wales",
            countryCode = "AU",
            regionId = "australiaeast",
            providerLabel = "Referencia de nube",
            address = "20.53.111.128"
        ),
        ipv4(
            id = "reference-au-southeast",
            displayName = "Australia South East, Victoria",
            countryCode = "AU",
            regionId = "australiasoutheast",
            providerLabel = "Referencia de nube",
            address = "20.193.18.128"
        ),
        ipv4(
            id = "reference-br-south",
            displayName = "Brazil South, São Paulo",
            countryCode = "BR",
            regionId = "brazilsouth",
            providerLabel = "Referencia de nube",
            address = "169.150.198.66"
        ),
        ipv4(
            id = "reference-ca-central",
            displayName = "Canada Central, Toronto",
            countryCode = "CA",
            regionId = "canadacentral",
            providerLabel = "Referencia de nube",
            address = "20.48.224.128"
        ),
        ipv4(
            id = "reference-ca-east",
            displayName = "Canada East, Quebec",
            countryCode = "CA",
            regionId = "canadaeast",
            providerLabel = "Referencia de nube",
            address = "52.229.123.128"
        ),
        ipv4(
            id = "reference-cl-central",
            displayName = "Chile Central, Santiago",
            countryCode = "CL",
            regionId = "chilecentral",
            providerLabel = "Referencia regional",
            address = "20.143.92.1"
        ),
        ipv4(
            id = "reference-fr-central",
            displayName = "France Central, París",
            countryCode = "FR",
            regionId = "francecentral",
            providerLabel = "Referencia de nube",
            address = "20.190.155.128"
        ),
        ipv4(
            id = "reference-fr-south",
            displayName = "France South, Marsella",
            countryCode = "FR",
            regionId = "francesouth",
            providerLabel = "Referencia de nube",
            address = "40.66.57.128"
        ),
        ipv4(
            id = "reference-de-north",
            displayName = "Germany North",
            countryCode = "DE",
            regionId = "germanynorth",
            providerLabel = "Referencia de nube",
            address = "51.116.146.128"
        ),
        ipv4(
            id = "reference-de-west-central",
            displayName = "Germany West Central, Frankfurt",
            countryCode = "DE",
            regionId = "germanywestcentral",
            providerLabel = "Referencia de nube",
            address = "51.116.57.128"
        ),
        ipv4(
            id = "reference-in-central",
            displayName = "Central India, Pune",
            countryCode = "IN",
            regionId = "centralindia",
            providerLabel = "Referencia de nube",
            address = "20.192.115.128"
        ),
        ipv4(
            id = "reference-in-south",
            displayName = "South India, Chennai",
            countryCode = "IN",
            regionId = "southindia",
            providerLabel = "Referencia de nube",
            address = "20.192.203.128"
        ),
        ipv4(
            id = "reference-jp-east",
            displayName = "Japan East, Tokio",
            countryCode = "JP",
            regionId = "japaneast",
            providerLabel = "Referencia de nube",
            address = "210.131.113.123"
        ),
        ipv4(
            id = "reference-jp-west",
            displayName = "Japan West, Osaka",
            countryCode = "JP",
            regionId = "japanwest",
            providerLabel = "Referencia de nube",
            address = "20.43.149.128"
        ),
        ipv4(
            id = "reference-kr-central",
            displayName = "Korea Central, Seúl",
            countryCode = "KR",
            regionId = "koreacentral",
            providerLabel = "Referencia de nube",
            address = "168.126.63.1"
        ),
        ipv4(
            id = "reference-kr-south",
            displayName = "Korea South, Busan",
            countryCode = "KR",
            regionId = "koreasouth",
            providerLabel = "Referencia de nube",
            address = "20.194.240.128"
        ),
        ipv4(
            id = "reference-mx-central",
            displayName = "Mexico Central, Querétaro",
            countryCode = "MX",
            regionId = "mexicocentral",
            providerLabel = "Referencia regional",
            address = "20.195.68.1"
        ),
        ipv4(
            id = "reference-west-europe",
            displayName = "West Europe",
            countryCode = "NL",
            regionId = "westeurope",
            providerLabel = "Referencia de nube",
            address = "20.31.25.128"
        ),
        ipv4(
            id = "reference-no-east",
            displayName = "Norway East, Oslo",
            countryCode = "NO",
            regionId = "norwayeast",
            providerLabel = "Referencia de nube",
            address = "51.120.219.128"
        ),
        ipv4(
            id = "reference-se-central",
            displayName = "Sweden Central",
            countryCode = "SE",
            regionId = "swedencentral",
            providerLabel = "Referencia de nube",
            address = "51.12.213.128"
        ),
        ipv4(
            id = "reference-ch-north",
            displayName = "Switzerland North, Zúrich",
            countryCode = "CH",
            regionId = "switzerlandnorth",
            providerLabel = "Referencia de nube",
            address = "51.107.57.128"
        ),
        ipv4(
            id = "reference-uae-north",
            displayName = "UAE North, Dubái",
            countryCode = "AE",
            regionId = "uaenorth",
            providerLabel = "Referencia de nube",
            address = "20.195.148.128"
        ),
        ipv4(
            id = "reference-uk-south",
            displayName = "UK South, Londres",
            countryCode = "GB",
            regionId = "uksouth",
            providerLabel = "Referencia de nube",
            address = "20.33.26.128"
        ),
        ipv4(
            id = "reference-us-central",
            displayName = "Central US, Iowa",
            countryCode = "US",
            regionId = "centralus",
            providerLabel = "Referencia de nube",
            address = "52.173.18.128"
        ),
        ipv4(
            id = "reference-us-east",
            displayName = "East US, Virginia",
            countryCode = "US",
            regionId = "eastus",
            providerLabel = "Referencia de nube",
            address = "20.42.73.128"
        ),
        ipv4(
            id = "reference-us-east-2",
            displayName = "East US 2, Virginia",
            countryCode = "US",
            regionId = "eastus2",
            providerLabel = "Referencia de nube",
            address = "20.39.81.128"
        ),
        ipv4(
            id = "reference-us-north-central",
            displayName = "North Central US, Illinois",
            countryCode = "US",
            regionId = "northcentralus",
            providerLabel = "Referencia de nube",
            address = "20.49.106.128"
        ),
        ipv4(
            id = "reference-us-south-central",
            displayName = "South Central US, Texas",
            countryCode = "US",
            regionId = "southcentralus",
            providerLabel = "Referencia de nube",
            address = "20.45.18.128"
        ),
        ipv4(
            id = "reference-us-west",
            displayName = "West US, California",
            countryCode = "US",
            regionId = "westus",
            providerLabel = "Referencia de nube",
            address = "13.89.204.1"
        ),
        ipv4(
            id = "reference-us-west-2",
            displayName = "West US 2, Washington",
            countryCode = "US",
            regionId = "westus2",
            providerLabel = "Referencia de nube",
            address = "20.46.81.128"
        ),
        ipv4(
            id = "reference-us-west-3",
            displayName = "West US 3, Arizona",
            countryCode = "US",
            regionId = "westus3",
            providerLabel = "Referencia de nube",
            address = "20.150.81.128"
        ),
        ipv4(
            id = "reference-cl-edge",
            displayName = "Chile Edge, Santiago",
            countryCode = "CL",
            regionId = null,
            providerLabel = "Referencia heredada",
            address = "151.206.123.10"
        ),
        ipv4(
            id = "reference-cl-cloud-node",
            displayName = "Chile, nodo de nube",
            countryCode = "CL",
            regionId = null,
            providerLabel = "Referencia heredada",
            address = "34.176.0.1"
        ),
        ipv4(
            id = "reference-cl-edge-node",
            displayName = "Chile, nodo perimetral",
            countryCode = "CL",
            regionId = null,
            providerLabel = "Referencia heredada",
            address = "15.228.0.1"
        ),
        ipv4(
            id = "reference-cl-provider-1",
            displayName = "Chile, referencia de conectividad 1",
            countryCode = "CL",
            regionId = null,
            providerLabel = "Referencia de conectividad",
            address = "200.83.128.1"
        ),
        ipv4(
            id = "reference-cl-provider-2",
            displayName = "Chile, referencia de conectividad 2",
            countryCode = "CL",
            regionId = null,
            providerLabel = "Referencia de conectividad",
            address = "200.72.0.1"
        ),
        ipv4(
            id = "reference-cl-provider-3",
            displayName = "Chile, referencia de conectividad 3",
            countryCode = "CL",
            regionId = null,
            providerLabel = "Referencia de conectividad",
            address = "200.75.0.1"
        ),
        ipv4(
            id = "reference-ar-mendoza",
            displayName = "IP de Mendoza",
            countryCode = "AR",
            regionId = null,
            providerLabel = "Referencia heredada",
            address = "190.221.180.1"
        ),
        ipv4(
            id = "reference-ar-cloud-node",
            displayName = "Argentina, nodo de nube",
            countryCode = "AR",
            regionId = null,
            providerLabel = "Referencia heredada",
            address = "34.175.0.1"
        ),
        ipv4(
            id = "reference-br-cloud-node",
            displayName = "Brasil, nodo de nube",
            countryCode = "BR",
            regionId = null,
            providerLabel = "Referencia heredada",
            address = "34.151.0.1"
        ),
        ipv4(
            id = "reference-bypass-us",
            displayName = "Referencia de bypass, Estados Unidos",
            countryCode = "US",
            regionId = null,
            providerLabel = "Referencia heredada",
            address = "143.244.47.65"
        ),
        ipv4(
            id = "reference-bypass-jp",
            displayName = "Referencia de bypass, Japón",
            countryCode = "JP",
            regionId = null,
            providerLabel = "Referencia heredada",
            address = "138.199.21.239"
        ),
        ipv4(
            id = "reference-bypass-kr",
            displayName = "Referencia de bypass, Corea",
            countryCode = "KR",
            regionId = null,
            providerLabel = "Referencia heredada",
            address = "121.125.60.151"
        )
    )

    val allEntries: List<BypassEndpointReference>
        get() = listOf(disabled, customAddress) + diagnosticReferences

    val productionEntries: List<BypassEndpointReference>
        get() = allEntries.filter(BypassEndpointReference::selectable)

    private fun ipv4(
        id: String,
        displayName: String,
        countryCode: String,
        regionId: String?,
        providerLabel: String,
        address: String
    ): BypassEndpointReference {
        return BypassEndpointReference(
            id = id,
            displayName = displayName,
            countryCode = countryCode,
            regionId = regionId,
            providerLabel = providerLabel,
            address = NetworkAddressInfo(
                address = address,
                protocolVersion = InternetProtocolVersion.IPV4
            ),
            trust = EndpointTrust.UNVERIFIED_REFERENCE,
            selectionState = EndpointSelectionState.DIAGNOSTIC_ONLY,
            sourceNote = "Referencia heredada; función de streaming no acreditada."
        )
    }
}
