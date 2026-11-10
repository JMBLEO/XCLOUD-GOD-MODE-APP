package com.xcloud.godmode.network

import android.util.Log
import java.net.HttpURLConnection
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

enum class XboxSubscriptionTier {
    ULTIMATE,
    FREE_TO_PLAY_ONLY
}

sealed interface XstsErrorAction {
    data class OpenBrowserResolution(val redirectUrl: String, val userMessage: String) : XstsErrorAction
    data class FatalAccountIssue(val userMessage: String) : XstsErrorAction
}

/**
 * SelfHealingSessionInterceptor: Implements automatic self-repair routines for Xbox Live
 * and Cloud Gaming (Cases 6 to 17):
 * - Case 6: Automatic clock skew compensation for PoP EC P-256 binary signatures.
 * - Case 7: KeyStore corruption/invalidation recovery without app crash.
 * - Case 10: XErr parsing and user-action routing.
 * - Case 13: Resilient Tizen vs. Windows 11 device profile spoofing.
 */
object SelfHealingSessionInterceptor {

    private const val TAG = "SelfHealingInterceptor"
    private const val WINDOWS_EPOCH_OFFSET = 11644473600L

    // Offset dinámico en segundos entre el reloj del servidor de Xbox y el reloj del teléfono
    private val serverClockOffsetSeconds = AtomicLong(0L)

    /**
     * CASO 6: Sincroniza automáticamente el reloj interno leyendo la cabecera HTTP "Date" de Microsoft.
     */
    fun captureServerClockOffset(connection: HttpURLConnection) {
        val dateHeader = connection.getHeaderField("Date") ?: return
        runCatching {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
            val serverEpochMs = format.parse(dateHeader)?.time ?: return
            val localEpochMs = System.currentTimeMillis()
            val diffSeconds = (serverEpochMs - localEpochMs) / 1000L
            if (kotlin.math.abs(diffSeconds) > 30L) {
                serverClockOffsetSeconds.set(diffSeconds)
                Log.i(TAG, "Reloj desfasado detectado. Offset aplicado: ${diffSeconds}s")
            }
        }
    }

    fun getCorrectedEpochSeconds(): Long {
        return (System.currentTimeMillis() / 1000L) + serverClockOffsetSeconds.get()
    }

    fun getCorrectedWindowsFileTime(): Long {
        return (getCorrectedEpochSeconds() + WINDOWS_EPOCH_OFFSET) * 10_000_000L
    }

    /**
     * CASO 7: Autorreparación de Android KeyStore cuando cambia la huella/PIN o se corrompe el cifrado.
     */
    fun <T> executeWithKeyStoreSelfHealing(
        keyAlias: String,
        clearEncryptedFile: () -> Unit,
        block: () -> T
    ): T? {
        return try {
            block()
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "KeyStore corrupto o invalidado (${e.javaClass.simpleName}). Regenerando bóveda...")
            runCatching {
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (ks.containsAlias(keyAlias)) {
                    ks.deleteEntry(keyAlias)
                }
            }
            runCatching { clearEncryptedFile() }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error inesperado en bóveda de sesión", e)
            null
        }
    }

    /**
     * CASO 10: Traduce los códigos XErr de xsts.auth.xboxlive.com en acciones automáticas para el usuario.
     */
    fun parseXstsErrorResponse(errorBodyJson: String): XstsErrorAction {
        val json = runCatching { JSONObject(errorBodyJson) }.getOrNull()
        val xErr = json?.optLong("XErr", 0L) ?: 0L
        val redirectUrl = json?.optString("Redirect").orEmpty()

        return when (xErr) {
            2148916233L -> XstsErrorAction.OpenBrowserResolution(
                redirectUrl = redirectUrl.ifBlank { "https://www.xbox.com" },
                userMessage = "Tu cuenta Microsoft aún no tiene un Gamertag de Xbox. Créalo gratis en la ventana que se abrirá y vuelve a la app."
            )
            2148916236L, 2148916237L -> XstsErrorAction.OpenBrowserResolution(
                redirectUrl = redirectUrl.ifBlank { "https://account.xbox.com" },
                userMessage = "Xbox requiere que confirmes tu edad o aceptes los términos actualizados para continuar."
            )
            2148916238L -> XstsErrorAction.FatalAccountIssue(
                userMessage = "Esta cuenta pertenece a un menor de edad y debe ser añadida a un grupo familiar de Microsoft por un adulto."
            )
            2148916227L -> XstsErrorAction.FatalAccountIssue(
                userMessage = "Esta cuenta tiene una suspensión activa en Xbox Live."
            )
            else -> XstsErrorAction.FatalAccountIssue(
                userMessage = "No se pudo autorizar el perfil en Xbox Live (Código XErr: $xErr). Intenta iniciar sesión nuevamente."
            )
        }
    }

    /**
     * CASO 13: Fallback automático de cabecera X-MS-Device-Info si el servidor rechaza el perfil Tizen HQ.
     */
    fun buildResilientDeviceInfoHeader(useTizenHighBitrateProfile: Boolean): String {
        val osName = if (useTizenHighBitrateProfile) "Tizen" else "Windows 11"
        val make = if (useTizenHighBitrateProfile) "Samsung" else "Microsoft"
        val model = if (useTizenHighBitrateProfile) "SmartTV" else "Surface Pro"

        return JSONObject().apply {
            put("appInfo", JSONObject().apply {
                put("env", JSONObject().apply {
                    put("clientAppId", "Microsoft.GamingApp")
                    put("clientAppType", "native")
                    put("clientAppVersion", "2409.1001.4.0")
                    put("clientSdkVersion", "10.3.0")
                    put("httpEnvironment", "prod")
                    put("sdkInstallId", "")
                })
            })
            put("dev", JSONObject().apply {
                put("hw", JSONObject().apply {
                    put("make", make)
                    put("model", model)
                    put("sdktype", "native")
                })
                put("os", JSONObject().apply {
                    put("name", osName)
                    put("ver", "22631.2715")
                    put("platform", "desktop")
                })
            })
        }.toString()
    }
}
