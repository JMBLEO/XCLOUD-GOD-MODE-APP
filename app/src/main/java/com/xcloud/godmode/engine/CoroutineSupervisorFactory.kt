package com.xcloud.godmode.engine

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import android.util.Log

/**
 * CoroutineSupervisorFactory: Provides safe CoroutineScopes tied to a SupervisorJob
 * to prevent crash propagation and deadlocks.
 */
object CoroutineSupervisorFactory {
    fun createSafeRootScope(tag: String): CoroutineScope {
        val handler = CoroutineExceptionHandler { _, throwable ->
            Log.e("GodModeSupervisor", "Uncaught exception in scope $tag: ${throwable.message}", throwable)
        }
        return CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + handler)
    }
}
