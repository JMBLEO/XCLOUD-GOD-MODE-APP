package com.xcloud.godmode.services

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.xcloud.godmode.network.StreamingSessionCredentials
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/**
 * SecureSessionStore: Implementación de la bóveda de sesión de Xbox con EncryptedSharedPreferences
 * y persistencia atómica en disco a prueba de caídas (Fase 3 Post-Stream).
 *
 * Flujo a prueba de caídas (Crash-Proof Atomic Persistence):
 * 1. Escritura en archivo temporal (.tmp).
 * 2. Volcado forzado a disco mediante fsync (FileChannel.force(true)).
 * 3. Verificación de integridad por CRC32.
 * 4. Respaldo previo (.bak) y renombrado atómico POSIX (tmp -> target).
 * 5. Recuperación automática desde .bak en caso de corte de energía o corrupción.
 */
class SecureSessionStore(private val context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secure_xbox_session",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val sessionTargetFile = File(context.filesDir, "session.dat")
    private val sessionTmpFile = File(context.filesDir, "session.dat.tmp")
    private val sessionBakFile = File(context.filesDir, "session.dat.bak")

    companion object {
        private const val TAG = "SecureSessionStore"
        private const val KEY_SESSION_DATA = "xbox_session_json"
        private const val KEY_GAMERTAG = "gamertag"
        private const val KEY_EXPIRY = "expires_at_ms"
    }

    fun saveSession(credentials: StreamingSessionCredentials) {
        val json = JSONObject().apply {
            put("gamertag", credentials.gamertag)
            put("xuid", credentials.xuid)
            put("uhs", credentials.uhs)
            put("msaRefreshToken", credentials.msaRefreshToken)
            put("xCloudGsToken", credentials.xCloudGsToken)
            put("xCloudBaseUri", credentials.xCloudBaseUri)
            put("expiresAtEpochMs", credentials.expiresAtEpochMs)
            put("longPlayToken", credentials.longPlayToken)
        }
        val jsonString = json.toString()

        // 1. Guardar en EncryptedSharedPreferences
        sharedPreferences.edit().apply {
            putString(KEY_SESSION_DATA, jsonString)
            putString(KEY_GAMERTAG, credentials.gamertag)
            putLong(KEY_EXPIRY, credentials.expiresAtEpochMs)
            apply()
        }

        // 2. Persistencia atómica a prueba de caídas (session.dat.tmp -> fsync -> rename)
        saveAtomicCrashProofFile(jsonString)
    }

    private fun saveAtomicCrashProofFile(jsonString: String) {
        runCatching {
            val payloadBytes = jsonString.toByteArray(StandardCharsets.UTF_8)
            val crc = CRC32().apply { update(payloadBytes) }.value

            val envelope = JSONObject().apply {
                put("crc32", crc)
                put("length", payloadBytes.size)
                put("payload", jsonString)
            }
            val envelopeBytes = envelope.toString().toByteArray(StandardCharsets.UTF_8)

            // Escribir en .tmp
            FileOutputStream(sessionTmpFile).use { fos ->
                fos.write(envelopeBytes)
                fos.flush()
                // fsync físico garantizado a nivel de bloque
                fos.channel.force(true)
            }

            // Si session.dat existe y es válido, rotar a .bak
            if (sessionTargetFile.exists()) {
                sessionTargetFile.copyTo(sessionBakFile, overwrite = true)
            }

            // Renombrado atómico POSIX
            if (!sessionTmpFile.renameTo(sessionTargetFile)) {
                // Fallback si renameTo falla en el filesystem
                sessionTmpFile.copyTo(sessionTargetFile, overwrite = true)
                sessionTmpFile.delete()
            }
            Log.d(TAG, "Sesión persistida atómicamente con CRC32: $crc")
        }.onFailure { err ->
            Log.w(TAG, "Fallo al escribir session.dat atómico: ${err.message}")
        }
    }

    fun loadSession(): StreamingSessionCredentials? {
        // Intento 1: Cargar desde session.dat atómico con verificación CRC32
        val fromFile = loadFromAtomicFile(sessionTargetFile)
        if (fromFile != null) return fromFile

        // Intento 2: Recuperar desde respaldo session.dat.bak si target falló o se corrompió
        Log.w(TAG, "session.dat inválido o inexistente. Intentando recuperar desde session.dat.bak...")
        val fromBak = loadFromAtomicFile(sessionBakFile)
        if (fromBak != null) {
            // Auto-curar session.dat desde el backup verificado
            runCatching { sessionBakFile.copyTo(sessionTargetFile, overwrite = true) }
            return fromBak
        }

        // Intento 3: Fallback a EncryptedSharedPreferences
        val jsonString = sharedPreferences.getString(KEY_SESSION_DATA, null) ?: return null
        return parseCredentialsFromJson(jsonString)
    }

    private fun loadFromAtomicFile(file: File): StreamingSessionCredentials? {
        if (!file.exists() || file.length() == 0L) return null
        return runCatching {
            val content = FileInputStream(file).use { it.bufferedReader(StandardCharsets.UTF_8).readText() }
            val envelope = JSONObject(content)
            val expectedCrc = envelope.getLong("crc32")
            val rawPayload = envelope.getString("payload")
            val payloadBytes = rawPayload.toByteArray(StandardCharsets.UTF_8)

            val actualCrc = CRC32().apply { update(payloadBytes) }.value
            if (expectedCrc != actualCrc) {
                Log.e(TAG, "Corrupción detectada en ${file.name}: CRC esperado $expectedCrc != actual $actualCrc")
                return null
            }
            parseCredentialsFromJson(rawPayload)
        }.getOrNull()
    }

    private fun parseCredentialsFromJson(jsonString: String): StreamingSessionCredentials? {
        return try {
            val json = JSONObject(jsonString)
            StreamingSessionCredentials(
                gamertag = json.getString("gamertag"),
                xuid = json.getString("xuid"),
                uhs = json.getString("uhs"),
                msaRefreshToken = json.getString("msaRefreshToken"),
                xCloudGsToken = json.getString("xCloudGsToken"),
                xCloudBaseUri = json.getString("xCloudBaseUri"),
                expiresAtEpochMs = json.getLong("expiresAtEpochMs"),
                longPlayToken = json.optString("longPlayToken", "")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun clearSession() {
        sharedPreferences.edit().clear().apply()
        runCatching {
            if (sessionTargetFile.exists()) sessionTargetFile.delete()
            if (sessionTmpFile.exists()) sessionTmpFile.delete()
            if (sessionBakFile.exists()) sessionBakFile.delete()
        }
    }

    fun isSessionExpired(): Boolean {
        val expiry = sharedPreferences.getLong(KEY_EXPIRY, 0L)
        return System.currentTimeMillis() > expiry
    }

    fun getGamertag(): String? = sharedPreferences.getString(KEY_GAMERTAG, null)
}
