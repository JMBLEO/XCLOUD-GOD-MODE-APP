package com.xcloud.godmode.services

import com.xcloud.godmode.constants.BypassEndpointCatalog
import com.xcloud.godmode.models.AppliedServerStatus
import com.xcloud.godmode.models.BypassConfiguration
import com.xcloud.godmode.models.BypassMode
import com.xcloud.godmode.models.CodecPreference
import com.xcloud.godmode.models.EndpointTrust
import com.xcloud.godmode.models.PendingConfigurationField
import com.xcloud.godmode.models.RegionPreference
import com.xcloud.godmode.models.RegionSelectionMode
import com.xcloud.godmode.models.RootApplicationConfiguration
import com.xcloud.godmode.models.RootConfigurationState
import com.xcloud.godmode.models.ServerSelectionMode
import com.xcloud.godmode.models.StoreLocalePreference
import com.xcloud.godmode.models.StreamServerConfiguration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface RootConfigurationRepository {
    val state: StateFlow<RootConfigurationState>

    suspend fun savePending(configuration: RootApplicationConfiguration)
    suspend fun promotePendingOnColdStart()
    suspend fun discardPending()
}

class DefaultRootConfigurationRepository : RootConfigurationRepository {

    companion object {
        val defaultRegionPreference = RegionPreference(
            mode = RegionSelectionMode.SOUTH_AMERICA_PREFERRED,
            preferredRegionIds = listOf("chilecentral", "brazilsouth")
        )

        val defaultServerConfiguration = StreamServerConfiguration(
            selectionMode = ServerSelectionMode.AUTOMATIC_VALIDATED,
            serverId = "automatic-south-america",
            regionId = null,
            displayName = "Selección automática validada",
            countryCode = null,
            hostName = null,
            port = null,
            resolvedAddress = null,
            status = AppliedServerStatus.AUTOMATIC_PENDING_DISCOVERY,
            trust = EndpointTrust.DYNAMICALLY_VALIDATED
        )

        val defaultBypassConfiguration = BypassConfiguration(
            mode = BypassMode.DISABLED,
            enabled = false,
            referenceId = BypassEndpointCatalog.disabled.id,
            displayName = "Desactivado",
            countryCode = null,
            address = null,
            trust = EndpointTrust.DYNAMICALLY_VALIDATED
        )

        val defaultRootConfiguration = RootApplicationConfiguration(
            regionPreference = defaultRegionPreference,
            server = defaultServerConfiguration,
            bypass = defaultBypassConfiguration,
            storeLocale = StoreLocalePreference.SYSTEM_DEFAULT,
            codecPreference = CodecPreference.AUTO_MAXIMUM_VERIFIED
        )
    }

    private val _state = MutableStateFlow(
        RootConfigurationState(
            applied = defaultRootConfiguration,
            pending = null,
            pendingFields = emptySet(),
            restartRequired = false
        )
    )
    override val state: StateFlow<RootConfigurationState> = _state.asStateFlow()

    override suspend fun savePending(configuration: RootApplicationConfiguration) {
        val currentApplied = _state.value.applied
        if (configuration == currentApplied) {
            _state.value = _state.value.copy(
                pending = null,
                pendingFields = emptySet(),
                restartRequired = false
            )
            return
        }

        val fields = mutableSetOf<PendingConfigurationField>()
        if (configuration.regionPreference != currentApplied.regionPreference) {
            fields.add(PendingConfigurationField.REGION)
        }
        if (configuration.server != currentApplied.server) {
            fields.add(PendingConfigurationField.SERVER)
        }
        if (configuration.bypass != currentApplied.bypass) {
            fields.add(PendingConfigurationField.BYPASS)
        }
        if (configuration.storeLocale != currentApplied.storeLocale) {
            fields.add(PendingConfigurationField.STORE_LOCALE)
        }
        if (configuration.codecPreference != currentApplied.codecPreference) {
            fields.add(PendingConfigurationField.CODEC)
        }

        _state.value = _state.value.copy(
            pending = configuration,
            pendingFields = fields,
            restartRequired = fields.isNotEmpty()
        )
    }

    override suspend fun promotePendingOnColdStart() {
        val pending = _state.value.pending
        if (pending != null) {
            _state.value = RootConfigurationState(
                applied = pending,
                pending = null,
                pendingFields = emptySet(),
                restartRequired = false
            )
        }
    }

    override suspend fun discardPending() {
        _state.value = _state.value.copy(
            pending = null,
            pendingFields = emptySet(),
            restartRequired = false
        )
    }
}
