package com.xcloud.godmode.services

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PersistentDiagnosticExporter: Exports the circular launch trace and diagnostic logs
 * to a local file for support and troubleshooting.
 */
class PersistentDiagnosticExporter(private val context: Context) {
    
    /**
     * Dumps current session traces to a text file in the cache directory.
     */
    suspend fun exportLogsToCacheFile(traceEntries: List<String>): File = withContext(Dispatchers.IO) {
        val dumpFile = File(context.cacheDir, "godmode_diagnostic_dump.log")
        dumpFile.writeText(traceEntries.joinToString("\n"))
        dumpFile
    }

    /**
     * Clears old diagnostic dumps.
     */
    fun clearOldDumps() {
        runCatching {
            File(context.cacheDir, "godmode_diagnostic_dump.log").delete()
        }
    }
}
