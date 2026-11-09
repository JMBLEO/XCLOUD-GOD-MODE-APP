package com.xcloud.godmode.services

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.SurfaceView
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ExternalDisplayTopology(
    val isExternalDisplayConnected: Boolean = false,
    val externalDisplayId: Int = -1,
    val displayName: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val isPrimaryScreenTouchControllerActive: Boolean = false
)

/**
 * ExternalDisplayProjectionEngine: Detects external monitors connected via USB-C DisplayPort Alt Mode,
 * Samsung DeX or Motorola Ready For. Projects video to external screen via an independent Presentation,
 * while transforming the phone's primary screen into an ergonomic dimmed touch controller.
 */
class ExternalDisplayProjectionEngine(
    private val context: Context,
    private val onExternalSurfaceReady: (surfaceView: SurfaceView) -> Unit,
    private val onTopologyChanged: (topology: ExternalDisplayTopology) -> Unit
) : DisplayManager.DisplayListener {

    companion object {
        private const val TAG = "ExtDisplayProjection"
    }

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    private var activePresentation: GameStreamPresentation? = null

    private val _topology = MutableStateFlow(ExternalDisplayTopology())
    val topology: StateFlow<ExternalDisplayTopology> = _topology.asStateFlow()

    fun startListening() {
        displayManager?.registerDisplayListener(this, null)
        checkCurrentDisplays()
    }

    fun stopListening() {
        displayManager?.unregisterDisplayListener(this)
        dismissPresentation()
    }

    private fun checkCurrentDisplays() {
        val displays = displayManager?.displays ?: return
        for (display in displays) {
            if (display.displayId != Display.DEFAULT_DISPLAY &&
                (display.flags and Display.FLAG_PRESENTATION) != 0) {
                setupPresentationOnDisplay(display)
                return
            }
        }
        dismissPresentation()
    }

    override fun onDisplayAdded(displayId: Int) {
        val display = displayManager?.getDisplay(displayId) ?: return
        if ((display.flags and Display.FLAG_PRESENTATION) != 0) {
            Log.i(TAG, "External presentation display added: ${display.name} (id=$displayId)")
            setupPresentationOnDisplay(display)
        }
    }

    override fun onDisplayRemoved(displayId: Int) {
        if (_topology.value.externalDisplayId == displayId) {
            Log.i(TAG, "External presentation display removed (id=$displayId)")
            dismissPresentation()
        }
    }

    override fun onDisplayChanged(displayId: Int) {
        if (_topology.value.externalDisplayId == displayId) {
            val display = displayManager?.getDisplay(displayId) ?: return
            _topology.value = _topology.value.copy(
                width = display.width,
                height = display.height
            )
            onTopologyChanged(_topology.value)
        }
    }

    private fun setupPresentationOnDisplay(display: Display) {
        dismissPresentation()

        try {
            val presentation = GameStreamPresentation(context, display) { surfaceView ->
                onExternalSurfaceReady(surfaceView)
            }
            presentation.show()
            activePresentation = presentation

            val newTopology = ExternalDisplayTopology(
                isExternalDisplayConnected = true,
                externalDisplayId = display.displayId,
                displayName = display.name,
                width = display.width,
                height = display.height,
                isPrimaryScreenTouchControllerActive = true
            )
            _topology.value = newTopology
            onTopologyChanged(newTopology)
            Log.i(TAG, "Dual Screen topology active: Video projected on ${display.name}, phone primary screen dimmed for touch control.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to instantiate presentation on display ${display.name}", e)
        }
    }

    private fun dismissPresentation() {
        activePresentation?.dismiss()
        activePresentation = null

        val newTopology = ExternalDisplayTopology(
            isExternalDisplayConnected = false,
            externalDisplayId = -1,
            displayName = null,
            width = 0,
            height = 0,
            isPrimaryScreenTouchControllerActive = false
        )
        _topology.value = newTopology
        onTopologyChanged(newTopology)
    }

    /**
     * Dedicated hardware presentation window on external screen.
     */
    private class GameStreamPresentation(
        context: Context,
        display: Display,
        private val onSurfaceCreated: (SurfaceView) -> Unit
    ) : Presentation(context, display) {

        private lateinit var surfaceView: SurfaceView

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            surfaceView = SurfaceView(context).apply {
                holder.addCallback(object : android.view.SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                        onSurfaceCreated(surfaceView)
                    }
                    override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, w: Int, h: Int) {}
                    override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {}
                })
            }
            setContentView(surfaceView)
        }
    }
}
