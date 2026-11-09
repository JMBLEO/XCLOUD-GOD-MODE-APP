package com.xcloud.godmode.input

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TextDialogRequest(
    val dialogId: String,
    val title: String,
    val description: String,
    val defaultText: String = "",
    val maxLength: Int = 256,
    val isPassword: Boolean = false
)

/**
 * VirtualKeyboardDialogHandler: Intercepts Xbox ShowTextDialog events and coordinates
 * native Android IME keyboard input returning text to the active stream control channel.
 */
class VirtualKeyboardDialogHandler(
    private val onTextSubmitted: (dialogId: String, text: String) -> Unit,
    private val onDialogCancelled: (dialogId: String) -> Unit
) {
    private val _activeDialog = MutableStateFlow<TextDialogRequest?>(null)
    val activeDialog: StateFlow<TextDialogRequest?> = _activeDialog.asStateFlow()

    /**
     * Called when a ShowTextDialog control packet is received from the server.
     */
    fun onShowTextDialogReceived(
        dialogId: String,
        title: String,
        description: String,
        defaultText: String = "",
        maxLength: Int = 256,
        isPassword: Boolean = false
    ) {
        _activeDialog.value = TextDialogRequest(
            dialogId = dialogId,
            title = title,
            description = description,
            defaultText = defaultText,
            maxLength = maxLength,
            isPassword = isPassword
        )
    }

    fun submitText(text: String) {
        val current = _activeDialog.value ?: return
        _activeDialog.value = null
        onTextSubmitted(current.dialogId, text.take(current.maxLength))
    }

    fun cancelDialog() {
        val current = _activeDialog.value ?: return
        _activeDialog.value = null
        onDialogCancelled(current.dialogId)
    }
}
