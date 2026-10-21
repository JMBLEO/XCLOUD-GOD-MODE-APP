package com.jmbleo.xcloudgodmode.stream

import android.content.Context

class ScriptInjector(private val context: Context) {
    fun getScriptCode(): String {
        return try {
            context.assets.open("xcloud-god-mode-ai.user.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            "console.log('xcloud-god-mode');"
        }
    }

    fun wrapInIsolatedSandbox(code: String): String {
        return "(function() {\n'use strict';\n$code\n})();"
    }
}
