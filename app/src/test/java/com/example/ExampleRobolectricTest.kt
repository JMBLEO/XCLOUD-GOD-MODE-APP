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
    assertEquals("XCloud God Mode", appName)
  }

  @Test
  fun `verify userscript asset exists and is non-empty`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val scriptContent = context.assets.open("xcloud-god-mode-ai.user.js")
      .bufferedReader(Charsets.UTF_8)
      .use { it.readText() }
    assertTrue(scriptContent.contains("GodModeNativeBridge"))
    assertTrue(scriptContent.contains("XCloud (God Mode AI)"))
    // Copilot IA removed as requested
    assertTrue(!scriptContent.contains("btn-copilot-footer"))
    assertTrue(!scriptContent.contains("btn-open-ai"))
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
}
