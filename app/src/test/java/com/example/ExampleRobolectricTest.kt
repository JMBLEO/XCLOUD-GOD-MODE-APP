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
    assertEquals("XCloud God Mode AI", appName)
  }

  @Test
  fun `verify userscript asset exists and is non-empty`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val scriptContent = context.assets.open("xcloud-god-mode-ai.user.js")
      .bufferedReader(Charsets.UTF_8)
      .use { it.readText() }
    assertTrue(scriptContent.contains("GodModeNativeBridge"))
    assertTrue(scriptContent.contains("XCloud (God Mode AI)"))
  }
}
