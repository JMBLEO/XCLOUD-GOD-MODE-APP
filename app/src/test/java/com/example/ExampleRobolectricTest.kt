package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aistudio.xcloudgodmode.ai.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("God Mode", appName)
  }

  @Test
  fun `verify userscript asset exists and is non-empty`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val scriptContent = context.assets.open("xcloud-god-mode-ai.user.js")
      .bufferedReader(Charsets.UTF_8)
      .use { it.readText() }
    assertTrue(scriptContent.contains("GodModeNativeBridge"))
    assertTrue(scriptContent.contains("XCloud (God Mode AI)"))
    assertTrue(scriptContent.isNotEmpty())
  }

  @Test
  fun `verify script injector loads and creates isolated sandbox`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val injector = com.jmbleo.xcloudgodmode.stream.ScriptInjector(context)
    val code = injector.getScriptCode()
    assertTrue(code.isNotEmpty())
    val wrapped = injector.wrapInIsolatedSandbox("console.log('test');")
    assertTrue(wrapped.contains("(function()"))
    assertTrue(wrapped.contains("'use strict';"))
    assertTrue(wrapped.contains("console.log('test');"))
  }

  @Test
  fun `verify touch layout storage saves and loads per TitleID`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val storage = com.aistudio.xcloudgodmode.ai.services.TouchLayoutStorage(context)
    val titleId = "TEST_TITLE_FORZA_HORIZON_5"
    val defaultLayout = storage.getLayout(titleId, "Forza Horizon 5")
    
    assertTrue(defaultLayout.buttons.isNotEmpty())
    assertTrue(defaultLayout.buttons.any { it.id == "stick_left" })
    assertTrue(defaultLayout.buttons.any { it.id == "btn_a" })

    // Modify and save
    val customProfile = defaultLayout.copy(
      snapToGrid = true,
      globalOpacity = 0.95f,
      buttons = defaultLayout.buttons.map {
        if (it.id == "btn_a") it.copy(scale = 1.8f, opacity = 0.5f) else it
      }
    )
    storage.saveLayout(customProfile)

    val reloaded = storage.getLayout(titleId, "Forza Horizon 5")
    assertTrue(reloaded.snapToGrid)
    assertEquals(0.95f, reloaded.globalOpacity, 0.01f)
    val btnA = reloaded.buttons.find { it.id == "btn_a" }
    assertEquals(1.8f, btnA?.scale ?: 0f, 0.01f)
  }

  @Test
  fun `verify app locale manager switching`() {
    com.aistudio.xcloudgodmode.ai.ui.theme.AppLocaleManager.setLanguageByCode("es")
    assertEquals("es", com.aistudio.xcloudgodmode.ai.ui.theme.AppLocaleManager.effectiveCode)
    assertTrue(com.aistudio.xcloudgodmode.ai.ui.theme.AppLocaleManager.isSpanish)

    com.aistudio.xcloudgodmode.ai.ui.theme.AppLocaleManager.setLanguageByCode("en")
    assertEquals("en", com.aistudio.xcloudgodmode.ai.ui.theme.AppLocaleManager.effectiveCode)
    assertTrue(!com.aistudio.xcloudgodmode.ai.ui.theme.AppLocaleManager.isSpanish)
  }
}
