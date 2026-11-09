package com.xcloud.godmode.network

import android.util.Log

/**
 * Phase 84: Emergency Session Cache & Sub-500ms Auto-Reconnect.
 * Holds active stream credentials, correlation IDs, ICE endpoints, and GSSV server parameters
 * in volatile memory for instantaneous session re-anchoring when recovering from transient network dropouts.
 */
class EmergencySessionCache {

    companion object {
        private const val TAG = "EmergencySessionCache"
        const val MAX_CACHE_AGE_MS = 300_000L // 5 minutes validity
    }

    data class CachedSession(
        val sessionId: String,
        val serverEndpoint: String,
        val serverUdpPort: Int,
        val srtpKeyBytes: ByteArray,
        val correlationId: String,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean = (System.currentTimeMillis() - timestamp) > MAX_CACHE_AGE_MS

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CachedSession) return false
            return sessionId == other.sessionId &&
                    serverEndpoint == other.serverEndpoint &&
                    serverUdpPort == other.serverUdpPort &&
                    srtpKeyBytes.contentEquals(other.srtpKeyBytes) &&
                    correlationId == other.correlationId
        }

        override fun hashCode(): Int {
            var result = sessionId.hashCode()
            result = 31 * result + serverEndpoint.hashCode()
            result = 31 * result + serverUdpPort
            result = 31 * result + srtpKeyBytes.contentHashCode()
            result = 31 * result + correlationId.hashCode()
            return result
        }
    }

    private var activeSession: CachedSession? = null

    fun saveSession(
        sessionId: String,
        serverEndpoint: String,
        serverUdpPort: Int,
        srtpKey: ByteArray,
        correlationId: String
    ) {
        activeSession = CachedSession(
            sessionId = sessionId,
            serverEndpoint = serverEndpoint,
            serverUdpPort = serverUdpPort,
            srtpKeyBytes = srtpKey.clone(),
            correlationId = correlationId
        )
        Log.d(TAG, "Cached active session: $sessionId for instant reconnection")
    }

    fun getValidSession(): CachedSession? {
        val s = activeSession ?: return null
        return if (!s.isExpired()) s else null
    }

    fun clear() {
        activeSession = null
    }
}
