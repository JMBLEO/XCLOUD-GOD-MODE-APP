package com.xcloud.godmode.services

import com.xcloud.godmode.models.BypassConfiguration
import com.xcloud.godmode.models.RootApplicationConfiguration
import com.xcloud.godmode.models.StreamRootConfiguration
import com.xcloud.godmode.models.StreamConfigurationState
import com.xcloud.godmode.models.StreamServerConfiguration
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

interface StreamConfigurationRepository {
    val state: StateFlow<StreamConfigurationState>

    suspend fun savePending(configuration: StreamRootConfiguration)
    suspend fun promotePendingOnColdStart()
    suspend fun discardPending()
}

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
class DefaultStreamConfigurationRepository(
    private val rootRepository: RootConfigurationRepository
) : StreamConfigurationRepository {

    override val state: StateFlow<StreamConfigurationState> = rootRepository.state.map { rootState ->
        StreamConfigurationState(
            applied = StreamRootConfiguration(
                bypass = rootState.applied.bypass,
                server = rootState.applied.server
            ),
            pending = rootState.pending?.let { p ->
                StreamRootConfiguration(
                    bypass = p.bypass,
                    server = p.server
                )
            },
            restartRequired = rootState.restartRequired
        )
    }.stateIn(
        scope = kotlinx.coroutines.GlobalScope,
        started = kotlinx.coroutines.flow.SharingStarted.Eagerly,
        initialValue = StreamConfigurationState(
            applied = StreamRootConfiguration(
                bypass = rootRepository.state.value.applied.bypass,
                server = rootRepository.state.value.applied.server
            ),
            pending = null,
            restartRequired = false
        )
    )

    override suspend fun savePending(configuration: StreamRootConfiguration) {
        val currentApplied = rootRepository.state.value.applied
        val updated = currentApplied.copy(
            bypass = configuration.bypass,
            server = configuration.server
        )
        rootRepository.savePending(updated)
    }

    override suspend fun promotePendingOnColdStart() {
        rootRepository.promotePendingOnColdStart()
    }

    override suspend fun discardPending() {
        rootRepository.discardPending()
    }
}
