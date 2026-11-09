package com.xcloud.godmode.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MacroStep(
    val actionId: String,   // e.g. "BUTTON_A", "BUTTON_X", "LT"
    val isPress: Boolean,
    val durationMs: Long = 50L,
    val delayAfterMs: Long = 50L
)

data class MacroSequence(
    val id: String,
    val name: String,
    val steps: List<MacroStep>,
    val isLooping: Boolean = false
)

/**
 * MacroSequencerEngine: Records, sequences, and automatically replays timed gamepad input macros.
 */
class MacroSequencerEngine(
    private val onStepDispatched: (actionId: String, isPressed: Boolean) -> Unit
) {
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private var playbackJob: Job? = null

    fun playMacro(sequence: MacroSequence, scope: CoroutineScope) {
        stopPlayback()
        if (sequence.steps.isEmpty()) return

        _isPlaying.value = true
        playbackJob = scope.launch(Dispatchers.Default) {
            do {
                for (step in sequence.steps) {
                    if (!isActive) break
                    onStepDispatched(step.actionId, step.isPress)
                    if (step.durationMs > 0) {
                        delay(step.durationMs)
                    }
                    if (!step.isPress && step.delayAfterMs > 0) {
                        delay(step.delayAfterMs)
                    }
                }
            } while (sequence.isLooping && isActive)
            _isPlaying.value = false
        }
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        _isPlaying.value = false
    }
}
