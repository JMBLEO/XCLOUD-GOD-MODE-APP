package com.xcloud.godmode

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * GlobalAuthCapture: Bridge for MainActivity's onNewIntent to send captured OAuth codes
 * back to the active XboxAuthModalViewModel.
 */
object GlobalAuthCapture {
    private val _codes = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val codes = _codes.asSharedFlow()

    fun emit(code: String) {
        _codes.tryEmit(code)
    }
}
