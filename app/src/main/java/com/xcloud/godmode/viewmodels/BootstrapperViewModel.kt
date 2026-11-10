package com.xcloud.godmode.viewmodels

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xcloud.godmode.core.GodModeAppCoreRoot
import com.xcloud.godmode.services.EncryptedVaultService
import com.xcloud.godmode.services.AppConfigurationRepository
import com.xcloud.godmode.network.XboxEndpoints
import com.xcloud.godmode.audio.GodModeSoundEngine
import com.xcloud.godmode.network.NetworkThreadGuard
import com.xcloud.godmode.network.NetworkClient
import com.xcloud.godmode.network.XboxFullAuthManager
import com.xcloud.godmode.streaming.NativeStreamPipelineManager
import com.xcloud.godmode.network.CloudLaunchDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException

/**
 * BootState: Lifecycle of the application's initial cold-start.
 */
sealed interface BootState {
    data object Loading : BootState
    data class Error(val message: String) : BootState
    data object ReadyToHome : BootState
    data object RequiresLogin : BootState
}

/**
 * BootstrapperViewModel: Orchestrates the high-performance engine startup.
 * Moves all heavy cryptographic and network initializations to background threads 
 * to ensure a zero-hang startup experience.
 */
class BootstrapperViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val _bootState = MutableStateFlow<BootState>(BootState.Loading)
    val bootState: StateFlow<BootState> = _bootState.asStateFlow()

    init {
        startOrchestratedBoot()
    }

    private fun startOrchestratedBoot() {
        viewModelScope.launch {
            try {
                Log.i("Bootstrapper", "STARTING_GOD_MODE_ENGINE_V10...")

                // 1. Core Architecture Initialization (Async)
                withContext(Dispatchers.IO) {
                    val coreRoot = GodModeAppCoreRoot.getInstance(context)
                    coreRoot.initialize()
                    
                    // 2. Cryptographic Vault Readiness
                    val vault = EncryptedVaultService.getInstance(context)
                    
                    // 3. Network & Endpoints Preparation
                    XboxEndpoints.init(context)
                    
                    // 4. Native Sound Engine Warmup
                    GodModeSoundEngine.init(context)
                    
                    // 5. Verification of Existing Session
                    val credentials = vault.loadCredentials()
                    val hasValidSession = credentials?.msaRefreshToken?.isNotBlank() == true || 
                                         credentials?.xCloudGsToken?.isNotBlank() == true

                    if (hasValidSession) {
                        Log.i("Bootstrapper", "BOOT_SUCCESS: Session Validated.")
                        _bootState.value = BootState.ReadyToHome
                    } else {
                        Log.i("Bootstrapper", "BOOT_SUCCESS: Fresh start required.")
                        _bootState.value = BootState.RequiresLogin
                    }
                }
            } catch (e: GeneralSecurityException) {
                Log.e("Bootstrapper", "CRYPTO_CORRUPTION_DETECTED: Purging artifacts.", e)
                purgeCorruptedVault()
                _bootState.value = BootState.RequiresLogin
            } catch (e: Exception) {
                Log.e("Bootstrapper", "BOOT_CRITICAL_FAILURE: ${e.message}", e)
                _bootState.value = BootState.Error("Fallo crítico en el motor de arranque: ${e.message}")
            }
        }
    }

    private suspend fun purgeCorruptedVault() = withContext(Dispatchers.IO) {
        runCatching {
            context.deleteSharedPreferences("god_mode_root_vault")
            context.deleteSharedPreferences("god_mode_secure_session_vault")
            context.noBackupFilesDir.listFiles()?.forEach { it.delete() }
            Log.w("Bootstrapper", "SELF_HEALING: Vault purged successfully.")
        }
    }
}
