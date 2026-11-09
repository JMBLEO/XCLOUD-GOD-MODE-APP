package com.xcloud.godmode.engine

import android.app.Application
import android.content.Context
import com.xcloud.godmode.services.AppConfigurationRepository
import com.xcloud.godmode.services.UnifiedPersistentAuthVault
import com.xcloud.godmode.network.*
import com.xcloud.godmode.streaming.*
import com.xcloud.godmode.streaming.decoder.DynamicHardwareDecoderLoader
import com.xcloud.godmode.ui.StreamUiController
import com.xcloud.godmode.bridge.OkHttpSessionClient
import com.xcloud.godmode.bridge.NanoGssvTransportNegotiator
import com.xcloud.godmode.bridge.NanoHardwareEngineRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * GodModeRootContainer: Central container for dependency resolution in the engine package.
 */
class GodModeRootContainer(
    private val application: Application
) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val context: Context get() = application.applicationContext

    val configRepository: AppConfigurationRepository =
        AppConfigurationRepository.getInstance(context)

    val networkClient = NetworkClient(enableDoh = true)
    val okHttpClient = networkClient.httpClient
    
    val sessionHttpClient = OkHttpSessionClient(okHttpClient)
    
    val diagnostics = CloudLaunchDiagnostics()
    
    val uiController = StreamUiController()
    
    val surfaceHostRegistry = SurfaceHostRegistry()
    val surfaceValidityRegistry = SurfaceValidityRegistry()
    val activeEngineSession = ActiveEngineSession()
    val startupGates = StreamStartupStateMachine()
    
    val authVault = UnifiedPersistentAuthVault.getInstance(context)
    val decoderLoader = DynamicHardwareDecoderLoader(context)

    val streamManager = NativeStreamPipelineManager(
        context = context,
        networkClient = networkClient,
        diagnostics = diagnostics,
        configRepository = configRepository
    )

    val nanoNegotiator = NanoGssvTransportNegotiator(sessionHttpClient)
    val nativeEngineRouter = NanoHardwareEngineRouter(streamManager)

    val nanoCoordinator = NanoPlayNowCoordinator(
        coordinatorScope = applicationScope,
        authVault = authVault,
        configRepository = configRepository,
        catalogRepository = CloudCatalogRepository(object : CloudCatalogApi {
            override suspend fun fetchLaunchableTitles(): CloudCatalogSnapshotDto {
                return CloudCatalogSnapshotDto(null, OfferingId("xgpuweb"), emptyList())
            }
        }),
        decoderLoader = decoderLoader,
        httpClient = sessionHttpClient,
        nanoNegotiator = nanoNegotiator,
        nativeEngineRouter = nativeEngineRouter,
        surfaceRegistry = surfaceHostRegistry,
        surfaceValidityRegistry = surfaceValidityRegistry,
        activeEngineSession = activeEngineSession,
        startupGates = startupGates,
        uiController = uiController
    )

    companion object {
        @Volatile
        private var instance: GodModeRootContainer? = null

        fun getInstance(application: Application): GodModeRootContainer =
            instance ?: synchronized(this) {
                instance ?: GodModeRootContainer(application).also { instance = it }
            }
    }
}
