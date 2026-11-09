package com.xcloud.godmode.network

import android.util.Log

/**
 * Phase 95: Quick Resume State Tracker (Xbox Series X|S Velocity Architecture).
 * Queries and parses suspended game state on the remote console,
 * allowing instant switching between suspended games without cold boots.
 */
class QuickResumeStateTracker {

    companion object {
        private const val TAG = "QuickResume"
    }

    data class SuspendedTitle(
        val titleId: String,
        val titleName: String,
        val posterUri: String?,
        val suspendedTimestamp: Long
    )

    private val suspendedTitles = mutableListOf<SuspendedTitle>()

    fun updateSuspendedList(titles: List<SuspendedTitle>) {
        synchronized(suspendedTitles) {
            suspendedTitles.clear()
            suspendedTitles.addAll(titles)
        }
        Log.d(TAG, "Quick Resume cache updated: ${titles.size} suspended titles available")
    }

    fun getSuspendedTitles(): List<SuspendedTitle> {
        return synchronized(suspendedTitles) { suspendedTitles.toList() }
    }

    /**
     * Formats switch command payload for console remote play channel.
     */
    fun createSwitchCommandPayload(titleId: String): ByteArray {
        val json = """{"action":"QuickResumeSwitch","titleId":"$titleId"}"""
        return json.toByteArray(Charsets.UTF_8)
    }
}
