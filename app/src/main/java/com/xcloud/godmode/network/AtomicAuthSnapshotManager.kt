package com.xcloud.godmode.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Snapshot inmutable de las credenciales activas de Xbox Live / xCloud.
 */
data class AuthSnapshot(
    val gamertag: String = "",
    val xuid: String = "",
    val uhs: String = "",
    val msaToken: String = "",
    val msaRefreshToken: String = "",
    val xstsCoreToken: String = "",
    val xstsStreamingToken: String = "",
    val gsToken: String = "",
    val preferredBaseUri: String = "",
    val issuedAtEpochMs: Long = 0L,
    val expiresAtEpochMs: Long = 0L,
    val version: Long = 0L
) {
    companion object {
        val EMPTY = AuthSnapshot()
    }

    val isValid: Boolean
        get() = gsToken.isNotBlank() && preferredBaseUri.isNotBlank()

    fun getRemainingLifetimeMs(nowEpochMs: Long): Long =
        (expiresAtEpochMs - nowEpochMs).coerceAtLeast(0L)

    fun isExpiringSoon(thresholdMs: Long = 15 * 60 * 1000L, nowEpochMs: Long = CloudTimeSynchronizer.getCloudEpochMs()): Boolean {
        return getRemainingLifetimeMs(nowEpochMs) <= thresholdMs
    }
}

/**
 * AtomicAuthSnapshotManager: Arquitectura RCU de doble búfer (snapshots[2]) con índice atómico
 * para lectura no bloqueante (0 ns de espera) durante el streaming activo (Fase 2).
 *
 * Los hilos de renderizado, game bar y overlays leen instantáneamente del buffer activo
 * mientras el demonio de renovación en segundo plano refresca los tokens en el buffer inactivo
 * al alcanzar el 75% del TTL (o menos de 20 minutos de vida).
 */
object AtomicAuthSnapshotManager {

    private const val TAG = "AtomicAuthSnapshot"

    // Doble búfer pre-asignado en memoria
    private val snapshots = arrayOf(AuthSnapshot.EMPTY, AuthSnapshot.EMPTY)
    private val activeIndex = AtomicInteger(0)
    private val versionCounter = AtomicLong(1L)

    private var renewalJob: Job? = null

    /**
     * Lectura libre de bloqueos en 0 ns para hilos de streaming y UI.
     */
    fun getActiveSnapshot(): AuthSnapshot {
        return snapshots[activeIndex.get()]
    }

    /**
     * Actualiza atómicamente el buffer inactivo y conmuta el índice activo.
     */
    fun updateSnapshot(
        gamertag: String,
        xuid: String,
        uhs: String,
        msaToken: String,
        msaRefreshToken: String,
        xstsCoreToken: String,
        xstsStreamingToken: String,
        gsToken: String,
        preferredBaseUri: String,
        expiresAtEpochMs: Long
    ): AuthSnapshot {
        val now = CloudTimeSynchronizer.getCloudEpochMs()
        val nextVersion = versionCounter.getAndIncrement()

        val newSnapshot = AuthSnapshot(
            gamertag = gamertag,
            xuid = xuid,
            uhs = uhs,
            msaToken = msaToken,
            msaRefreshToken = msaRefreshToken,
            xstsCoreToken = xstsCoreToken,
            xstsStreamingToken = xstsStreamingToken,
            gsToken = gsToken,
            preferredBaseUri = preferredBaseUri,
            issuedAtEpochMs = now,
            expiresAtEpochMs = expiresAtEpochMs,
            version = nextVersion
        )

        val currentActive = activeIndex.get()
        val inactive = 1 - currentActive

        // Escribir en buffer inactivo
        snapshots[inactive] = newSnapshot

        // Conmutación atómica de lectura
        activeIndex.set(inactive)

        Log.i(TAG, "Snapshot atómico actualizado v$nextVersion (Buffer $inactive activo). TTL restante: ${newSnapshot.getRemainingLifetimeMs(now) / 1000}s")
        return newSnapshot
    }

    /**
     * Pre-Stream Freshness Barrier (Fase 1):
     * Verifica que el token tenga al menos 15 minutos de vigencia antes de solicitar /play.
     */
    fun satisfiesFreshnessBarrier(minRemainingMs: Long = 15 * 60 * 1000L): Boolean {
        val snapshot = getActiveSnapshot()
        if (!snapshot.isValid) return false
        val now = CloudTimeSynchronizer.getCloudEpochMs()
        return !snapshot.isExpiringSoon(minRemainingMs, now)
    }

    /**
     * Demonio de Renovación Silenciosa en Segundo Plano (Fase 2):
     * Evalúa el TTL periódicamente y ejecuta el refresco silencioso al llegar al 75% de vida
     * o a menos de 20 minutos de caducidad.
     */
    fun startSilentRenewalDaemon(
        scope: CoroutineScope,
        refreshOperation: suspend () -> CompleteXboxSession?
    ) {
        stopSilentRenewalDaemon()
        renewalJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "Demonio de renovación silenciosa de tokens iniciado.")
            while (isActive) {
                delay(60_000L) // Chequeo cada 60 segundos

                val snapshot = getActiveSnapshot()
                if (!snapshot.isValid) continue

                val now = CloudTimeSynchronizer.getCloudEpochMs()
                val totalTtl = (snapshot.expiresAtEpochMs - snapshot.issuedAtEpochMs).coerceAtLeast(60_000L)
                val remainingMs = snapshot.getRemainingLifetimeMs(now)
                val elapsedRatio = 1.0 - (remainingMs.toDouble() / totalTtl.toDouble())

                // Disparar si transcurrió el 75% del TTL o quedan menos de 20 minutos
                if (elapsedRatio >= 0.75 || remainingMs < 20 * 60 * 1000L) {
                    Log.i(TAG, "Renovando tokens en segundo plano (Elapsed: ${(elapsedRatio * 100).toInt()}%, Quedan: ${remainingMs / 60000}m)...")
                    runCatching {
                        val refreshed = refreshOperation()
                        if (refreshed != null) {
                            updateSnapshot(
                                gamertag = refreshed.gamertag,
                                xuid = refreshed.xuid,
                                uhs = refreshed.uhs,
                                msaToken = refreshed.msaAccessToken,
                                msaRefreshToken = refreshed.msaRefreshToken,
                                xstsCoreToken = refreshed.webXstsToken,
                                xstsStreamingToken = refreshed.gssvXstsToken,
                                gsToken = refreshed.xgpuGsToken,
                                preferredBaseUri = refreshed.preferredBaseUri,
                                expiresAtEpochMs = refreshed.gsTokenExpiresAtEpochMs
                            )
                        }
                    }.onFailure { err ->
                        Log.w(TAG, "Error en renovación silenciosa en segundo plano: ${err.message}")
                    }
                }
            }
        }
    }

    fun stopSilentRenewalDaemon() {
        renewalJob?.cancel()
        renewalJob = null
    }

    fun clear() {
        stopSilentRenewalDaemon()
        snapshots[0] = AuthSnapshot.EMPTY
        snapshots[1] = AuthSnapshot.EMPTY
        activeIndex.set(0)
    }
}
