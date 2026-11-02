package com.xcloud.godmode.services

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

class RefreshRateTracker(context: Context) {
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    fun startTracking(displayId: Int) {
        displayManager.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(id: Int) {
                if (id != displayId) return
                val display = displayManager.getDisplay(id) ?: return
                val refreshRate = display.mode.refreshRate.toInt()

                // Notificar a C++ para sincronizar AChoreographer
                // NativeStreamer.setDisplayRefreshRate(refreshRate)
            }
            override fun onDisplayAdded(id: Int) {}
            override fun onDisplayRemoved(id: Int) {}
        }, null)
    }
}
