package com.jmbleo.xcloudgodmode

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jmbleo.xcloudgodmode.config.GodModeConfig
import com.jmbleo.xcloudgodmode.config.GodModeConfigManager
import com.jmbleo.xcloudgodmode.config.ResponseCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GodModeConfigManagerTest {

    @Test
    fun `sanitization clamps out of bounds values safely`() {
        val unsafe = GodModeConfig(
            innerDeadzone = -0.5f,
            outerDeadzone = 2.0f,
            brightness = 300,
            contrast = 10,
            saturation = 500,
            sharpness = -20
        )
        val safe = unsafe.sanitized()

        assertEquals(0.0f, safe.innerDeadzone, 1e-4f)
        assertEquals(1.0f, safe.outerDeadzone, 1e-4f)
        assertEquals(150, safe.brightness)
        assertEquals(50, safe.contrast)
        assertEquals(150, safe.saturation)
        assertEquals(0, safe.sharpness)
    }

    @Test
    fun `config manager persists and loads settings with listener updates`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = GodModeConfigManager.getInstance(context)

        var notified = false
        val listener: (GodModeConfig) -> Unit = { cfg ->
            if (cfg.responseCurve == ResponseCurve.EXPONENTIAL) {
                notified = true
            }
        }
        manager.addListener(listener)

        val updated = manager.getConfig().copy(
            innerDeadzone = 0.12f,
            outerDeadzone = 0.90f,
            responseCurve = ResponseCurve.EXPONENTIAL,
            swapAB = true
        )
        manager.updateConfig(updated)

        val current = manager.getConfig()
        assertEquals(0.12f, current.innerDeadzone, 1e-4f)
        assertEquals(0.90f, current.outerDeadzone, 1e-4f)
        assertEquals(ResponseCurve.EXPONENTIAL, current.responseCurve)
        assertTrue(current.swapAB)
        assertTrue(notified)

        manager.removeListener(listener)
    }
}
