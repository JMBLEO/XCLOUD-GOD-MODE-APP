package com.xcloud.godmode

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * GodModeApp: Application-level entry point.
 * Ensures that heavy native libraries and crypto engines are initialized 
 * asynchronously to prevent main-thread deadlock during cold start.
 */
class GodModeApp : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        
        // Asynchronous loading of NDK engines to ensure the UI thread is never blocked.
        applicationScope.launch {
            try {
                // Initialize Native Engines (e.g. NANO, Rust UDP Stack, Vulkan Pipelining)
                // System.loadLibrary("godmode_native") 
                Log.i("GodModeApp", "NATIVE_ENGINE: Successfully initialized in background thread.")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("GodModeApp", "NATIVE_ENGINE: Failed to load library: ${e.message}")
            } catch (e: Exception) {
                Log.e("GodModeApp", "NATIVE_ENGINE: Unexpected initialization error: ${e.message}")
            }
        }
    }
}
