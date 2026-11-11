package com.xcloud.godmode.network

import android.util.Log
import java.net.HttpURLConnection
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong

/**
 * CloudTimeSynchronizer: Sincronización criptográfica de reloj interno contra los servidores
 * de Microsoft / Azure (Fase 1 Pre-Stream).
 *
 * Lee la cabecera HTTP "Date" (RFC 1123 / RFC 7231) en cada respuesta y mantiene el offset
 * temporal exacto (Delta = T_servidor - T_local). Garantiza que las firmas PoP EC P-256
 * y la verificación de caducidad de tokens operen en el tiempo UTC real de la nube,
 * evitando rechazos por desincronización horaria del dispositivo móvil.
 */
object CloudTimeSynchronizer {

    private const val TAG = "CloudTimeSync"
    private const val WINDOWS_EPOCH_OFFSET = 11644473600L // Segundos entre 1601 y 1970

    // Desfase en milisegundos entre el reloj de Xbox y el reloj del sistema local
    private val clockOffsetMs = AtomicLong(0L)
    @Volatile
    private var hasSyncedOnce = false

    /**
     * Captura la cabecera HTTP "Date" devuelta por cualquier servicio de Xbox / Live / Azure.
     */
    fun captureHttpDate(connection: HttpURLConnection) {
        val dateHeader = connection.getHeaderField("Date") ?: return
        captureDateHeader(dateHeader)
    }

    /**
     * Procesa una cadena de fecha RFC 1123 (ej: "Wed, 11 Nov 2026 12:00:00 GMT").
     */
    fun captureDateHeader(dateHeader: String) {
        if (dateHeader.isBlank()) return
        runCatching {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
            val serverEpochMs = format.parse(dateHeader)?.time ?: return
            val localEpochMs = System.currentTimeMillis()
            val deltaMs = serverEpochMs - localEpochMs

            // Aplicar compensación si el desvío es relevante (> 5 segundos)
            if (!hasSyncedOnce || kotlin.math.abs(deltaMs) > 5000L) {
                clockOffsetMs.set(deltaMs)
                hasSyncedOnce = true
                Log.i(TAG, "Reloj de la nube sincronizado. Desfase detectado: ${deltaMs}ms")
            }
        }.onFailure {
            Log.w(TAG, "No se pudo parsear cabecera Date: '$dateHeader'")
        }
    }

    /**
     * Retorna el tiempo actual en milisegundos corregido con la hora del servidor en la nube.
     */
    fun getCloudEpochMs(): Long {
        return System.currentTimeMillis() + clockOffsetMs.get()
    }

    /**
     * Retorna el tiempo actual en segundos corregido con la hora del servidor en la nube.
     */
    fun getCloudEpochSeconds(): Long {
        return getCloudEpochMs() / 1000L
    }

    /**
     * Retorna el tiempo en formato Windows FileTime (intervalos de 100 ns desde el 1 de enero de 1601),
     * requerido obligatoriamente para las firmas binarias de Proof-of-Possession (PoP).
     */
    fun getWindowsFileTime(): Long {
        return (getCloudEpochSeconds() + WINDOWS_EPOCH_OFFSET) * 10_000_000L
    }

    /**
     * Devuelve el desfase detectado actual en milisegundos.
     */
    fun getClockOffsetMs(): Long = clockOffsetMs.get()

    /**
     * Indica si ya se ha recibido al menos una cabecera Date del servidor.
     */
    fun isSynchronized(): Boolean = hasSyncedOnce
}
