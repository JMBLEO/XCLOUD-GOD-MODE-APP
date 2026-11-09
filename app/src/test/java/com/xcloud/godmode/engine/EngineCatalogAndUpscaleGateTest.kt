package com.xcloud.godmode.engine

import com.xcloud.godmode.constants.BypassEndpointCatalog
import com.xcloud.godmode.models.AuthenticationState
import com.xcloud.godmode.models.BypassConfiguration
import com.xcloud.godmode.models.BypassMode
import com.xcloud.godmode.models.CodecPreference
import com.xcloud.godmode.models.EndpointTrust
import com.xcloud.godmode.models.EngineCatalogVisibility
import com.xcloud.godmode.network.DefaultStreamMetricsProvider
import com.xcloud.godmode.services.DefaultAuthenticationRepository
import com.xcloud.godmode.services.DefaultRootConfigurationRepository
import com.xcloud.godmode.viewmodels.PlayNowAction
import com.xcloud.godmode.viewmodels.PlayNowViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineCatalogAndUpscaleGateTest {

    @Test
    fun playNowBlocksWhenUnauthenticated() {
        val availability = ProductionEngineAvailability()
        val catalog = EngineCatalog(availability)
        val gate = NativeEngineGate(availability)
        val rootRepo = DefaultRootConfigurationRepository()
        val authRepo = DefaultAuthenticationRepository() // NOT_CONFIGURED
        val metricsProvider = DefaultStreamMetricsProvider()

        val viewModel = PlayNowViewModel(
            engineCatalog = catalog,
            nativeEngineGate = gate,
            rootConfigurationRepository = rootRepo,
            authenticationRepository = authRepo,
            streamMetricsProvider = metricsProvider
        )

        val action = viewModel.onPlayNowPressed()
        assertTrue(action is PlayNowAction.Blocked)
        val blocked = action as PlayNowAction.Blocked
        assertTrue(blocked.reason.startsWith("AUTHENTICATION_REQUIRED:"))
    }

    @Test
    fun playNowDoesNotOpenMenuWhenNoEngineIsImplemented() = runBlocking {
        val availability = ProductionEngineAvailability()
        val catalog = EngineCatalog(availability)
        val gate = NativeEngineGate(availability)
        val rootRepo = DefaultRootConfigurationRepository()
        val authRepo = DefaultAuthenticationRepository()
        authRepo.authenticate() // AUTHENTICATED

        val metricsProvider = DefaultStreamMetricsProvider()

        val viewModel = PlayNowViewModel(
            engineCatalog = catalog,
            nativeEngineGate = gate,
            rootConfigurationRepository = rootRepo,
            authenticationRepository = authRepo,
            streamMetricsProvider = metricsProvider
        )

        val action = viewModel.onPlayNowPressed()
        assertTrue(action is PlayNowAction.Blocked)
        val blocked = action as PlayNowAction.Blocked
        assertTrue(blocked.reason.startsWith("NATIVE_ENGINE_UNAVAILABLE:"))

        val state = viewModel.uiState.value
        assertFalse(state.engineMenuVisible)
        assertTrue(state.engineEntries.isEmpty())
        assertNull(state.selectedEngineId)
    }

    @Test
    fun rootConfigurationRepositoryManagesAppliedAndPendingState() = runBlocking {
        val repo = DefaultRootConfigurationRepository()
        val initialState = repo.state.value

        assertFalse(initialState.restartRequired)
        assertNull(initialState.pending)

        val newBypass = BypassConfiguration(
            mode = BypassMode.CUSTOM_ADDRESS,
            enabled = true,
            referenceId = null,
            displayName = "IP personalizada",
            countryCode = "AR",
            address = null,
            trust = EndpointTrust.USER_DEFINED
        )

        val modifiedConfig = initialState.applied.copy(bypass = newBypass)
        repo.savePending(modifiedConfig)

        val pendingState = repo.state.value
        assertTrue(pendingState.restartRequired)
        assertEquals(modifiedConfig, pendingState.pending)
        // Applied state MUST remain unchanged
        assertEquals(initialState.applied, pendingState.applied)

        // Cold start promotion
        repo.promotePendingOnColdStart()
        val promotedState = repo.state.value
        assertFalse(promotedState.restartRequired)
        assertNull(promotedState.pending)
        assertEquals(modifiedConfig, promotedState.applied)
    }

    @Test
    fun bypassEndpointCatalogSeparatesSelectableAndDiagnosticEntries() {
        val disabled = BypassEndpointCatalog.disabled
        val custom = BypassEndpointCatalog.customAddress
        val diagnostics = BypassEndpointCatalog.diagnosticReferences

        assertTrue(disabled.selectable)
        assertTrue(custom.selectable)
        assertTrue(diagnostics.none { it.selectable })
        assertTrue(diagnostics.all { it.trust == EndpointTrust.UNVERIFIED_REFERENCE })
    }

    @Test
    fun diagnosticVisibilityExposesUnavailableEntriesWithoutMakingThemSelectable() {
        val availability = ProductionEngineAvailability()
        val catalog = EngineCatalog(availability)

        val entries = catalog.entries(EngineCatalogVisibility.INCLUDE_UNAVAILABLE)

        assertTrue(entries.isNotEmpty())
        assertTrue(entries.none { it.selectable })
    }

    @Test
    fun nativeModeIsAvailableWithValidColorInput() {
        val gate = UpscaleCompatibilityGate(
            adapterAvailability = ProductionUpscaleAdapterAvailability(),
            temporalAdapterAvailability = ProductionTemporalUpscaleAdapterAvailability()
        )
        val result = gate.validate(
            mode = UpscaleMode.NATIVE,
            inputs = UpscaleFrameInputs.Spatial(
                colorHandle = 1L,
                presentationTimestampNs = 1_000L
            )
        )
        assertEquals(UpscaleAvailability.Available, result)
    }

    @Test
    fun spatialModeRemainsUnavailableWithoutImplementation() {
        val gate = UpscaleCompatibilityGate(
            adapterAvailability = ProductionUpscaleAdapterAvailability(),
            temporalAdapterAvailability = ProductionTemporalUpscaleAdapterAvailability()
        )
        val result = gate.validate(
            mode = UpscaleMode.SPATIAL_EDGE_RECONSTRUCTION,
            inputs = UpscaleFrameInputs.Spatial(
                colorHandle = 1L,
                presentationTimestampNs = 1_000L
            )
        )
        assertTrue(result is UpscaleAvailability.Unavailable)
        val unavailable = result as UpscaleAvailability.Unavailable
        assertTrue(
            unavailable.reason.startsWith(
                "SPATIAL_UPSCALE_ADAPTER_UNAVAILABLE:"
            )
        )
    }

    @Test
    fun temporalModeRemainsUnavailableWithCompleteInputs() {
        val gate = UpscaleCompatibilityGate(
            adapterAvailability = ProductionUpscaleAdapterAvailability(),
            temporalAdapterAvailability = ProductionTemporalUpscaleAdapterAvailability()
        )
        val result = gate.validate(
            mode = UpscaleMode.TEMPORAL_ML_LITE,
            inputs = UpscaleFrameInputs.Temporal(
                colorHandle = 1L,
                depthHandle = 2L,
                motionVectorHandle = 3L,
                jitterX = 0.25f,
                jitterY = -0.25f,
                presentationTimestampNs = 1_000L
            )
        )
        assertTrue(result is UpscaleAvailability.Unavailable)
        val unavailable = result as UpscaleAvailability.Unavailable
        assertTrue(
            unavailable.reason.startsWith(
                "TEMPORAL_UPSCALE_ADAPTER_UNAVAILABLE:"
            )
        )
    }

    @Test
    fun temporalModeRejectsSpatialOnlyInput() {
        val gate = UpscaleCompatibilityGate(
            adapterAvailability = ProductionUpscaleAdapterAvailability(),
            temporalAdapterAvailability = ProductionTemporalUpscaleAdapterAvailability()
        )
        val result = gate.validate(
            mode = UpscaleMode.TEMPORAL_ML_FULL,
            inputs = UpscaleFrameInputs.Spatial(
                colorHandle = 1L,
                presentationTimestampNs = 1_000L
            )
        )
        assertTrue(result is UpscaleAvailability.Unavailable)
        val unavailable = result as UpscaleAvailability.Unavailable
        assertTrue(
            unavailable.reason.startsWith(
                "TEMPORAL_UPSCALE_INPUTS_UNAVAILABLE:"
            )
        )
    }

    @Test
    fun upscaleInputsRejectInvalidHandlesAndTimestamps() {
        assertThrows(IllegalArgumentException::class.java) {
            UpscaleFrameInputs.Spatial(
                colorHandle = 0L,
                presentationTimestampNs = 1_000L
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpscaleFrameInputs.Spatial(
                colorHandle = 1L,
                presentationTimestampNs = -1L
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpscaleFrameInputs.Temporal(
                colorHandle = 1L,
                depthHandle = 2L,
                motionVectorHandle = 3L,
                jitterX = Float.NaN,
                jitterY = 0.0f,
                presentationTimestampNs = 1_000L
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpscaleFrameInputs.Temporal(
                colorHandle = 1L,
                depthHandle = 2L,
                motionVectorHandle = 3L,
                jitterX = 0.0f,
                jitterY = 0.0f,
                reactiveMaskHandle = 0L,
                presentationTimestampNs = 1_000L
            )
        }
    }
}
