package com.xcloud.godmode.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.xcloud.godmode.models.SettingsModel
import com.xcloud.godmode.network.XboxFullAuthManager
import com.xcloud.godmode.network.IndependentXboxAuthManager
import com.xcloud.godmode.network.IndependentClientProfile
import com.xcloud.godmode.network.CrossPlatformFirstPartyAuth
import com.xcloud.godmode.network.LanCompanionAndCookieHydrator
import com.xcloud.godmode.ui.theme.AppStrings
import com.xcloud.godmode.viewmodels.HomeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class AuthMenuScreen {
    MAIN,           // Master options menu
    DESKTOP_FLOW,   // Option 1: Direct Desktop Flow (oauth20_desktop.srf)
    QR,             // Option 2: Real-time QR Code flow
    CODE,           // Option 3: 8-character device code for microsoft.com/link
    LOOPBACK,       // Option 4: Localhost Loopback Interceptor (127.0.0.1:49215)
    CROSS_PLATFORM, // Option 5: Cross-Platform First-Party Auth
    LAN_RELAY,      // Option 6: LAN Companion Relay & Cookies
    RESCUE          // Option 7: External Browser with rescue manual input
}

enum class OrchestratorStatus {
    IDLE,
    LOADING,
    POLLING_QR,
    SUCCESS,
    ERROR
}

/**
 * Extension to safely resolve FragmentActivity from Compose Context
 */
fun Context.findFragmentActivity(): FragmentActivity? {
    var currentContext = this
    while (currentContext is ContextWrapper) {
        if (currentContext is FragmentActivity) return currentContext
        currentContext = currentContext.baseContext
    }
    return null
}

/**
 * BiometricPrompt API wrapper for secure local device authentication (Passkey preparation)
 */
object BiometricPromptHelper {
    fun authenticate(
        context: Context,
        title: String = "Verificación de Seguridad (Passkey)",
        subtitle: String = "Confirma tu identidad biométrica para acceder a la cuenta de Xbox",
        negativeButtonText: String = "Cancelar",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val activity = context.findFragmentActivity()
        if (activity == null) {
            // If running outside FragmentActivity, gracefully proceed
            onSuccess()
            return
        }

        val biometricManager = BiometricManager.from(activity)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or 
                             BiometricManager.Authenticators.DEVICE_CREDENTIAL
        
        val canAuth = biometricManager.canAuthenticate(authenticators)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            // Hardware doesn't support biometrics or none enrolled -> proceed directly
            Log.d("BiometricPromptHelper", "Biometrics not available or not enrolled ($canAuth). Proceeding without biometric lock.")
            onSuccess()
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    onError("Autenticación biométrica cancelada por el usuario.")
                } else {
                    onError(errString.toString())
                }
            }
        }

        val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            promptInfoBuilder.setAllowedAuthenticators(authenticators)
        } else {
            promptInfoBuilder.setNegativeButtonText(negativeButtonText)
        }

        val promptInfo = promptInfoBuilder.build()
        val biometricPrompt = BiometricPrompt(activity, executor, callback)
        biometricPrompt.authenticate(promptInfo)
    }
}

/**
 * Universal Authentication Orchestrator Modal (God Mode RPS Bypass).
 * Features:
 * 1. 4 User-friendly options menu (Chrome Custom Tabs, Live QR, 8-character device code, and rescue browser).
 * 2. Visual countdown timer next to the QR code showing exact validity remaining.
 * 3. BiometricPrompt API wrapper for Passkey security before launching the browser.
 */
@Composable
fun DualAuthModal(
    authManager: XboxFullAuthManager,
    homeViewModel: HomeViewModel,
    settings: SettingsModel = SettingsModel(),
    onDismiss: () -> Unit,
    onSuccess: (XboxFullAuthManager.XboxReadySession) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val strings = AppStrings.get(settings.appLanguage)

    var currentScreen by remember { mutableStateOf(AuthMenuScreen.MAIN) }
    var authStatus by remember { mutableStateOf(OrchestratorStatus.IDLE) }
    var statusMessage by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pendingAuth by remember { mutableStateOf<XboxFullAuthManager.PendingAuthContext?>(null) }
    var manualUrlInput by remember { mutableStateOf("") }
    var desktopAuthUrl by remember { mutableStateOf("") }
    var desktopPastedUrl by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }

    // Visual Countdown Timer state for Device Code (QR and 8-digit code)
    var remainingSeconds by remember { mutableIntStateOf(900) }

    val capturedCode by homeViewModel.capturedAuthCode.collectAsState()

    // Helper to launch Chrome Custom Tabs or fall back to system browser
    val launchSecureBrowser: (String) -> Unit = { url ->
        try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabsIntent.launchUrl(context, Uri.parse(url))
        } catch (_: Exception) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    // Helper to start device code flow
    val startDeviceCodeFlow: suspend () -> Unit = {
        authStatus = OrchestratorStatus.LOADING
        statusMessage = "Generando conexión segura con Microsoft..."
        errorMessage = null
        try {
            val contextData = authManager.initDeviceLogin()
            pendingAuth = contextData
            remainingSeconds = contextData.expiresIn
            authStatus = OrchestratorStatus.POLLING_QR
        } catch (e: Exception) {
            authStatus = OrchestratorStatus.ERROR
            errorMessage = "No se pudo conectar con Microsoft: ${e.message}"
        }
    }

    // Countdown Timer Loop (Active when viewing QR or 8-digit code)
    LaunchedEffect(pendingAuth, currentScreen) {
        if ((currentScreen == AuthMenuScreen.QR || currentScreen == AuthMenuScreen.CODE) && pendingAuth != null) {
            remainingSeconds = pendingAuth!!.expiresIn
            while (remainingSeconds > 0 && (currentScreen == AuthMenuScreen.QR || currentScreen == AuthMenuScreen.CODE)) {
                delay(1000L)
                remainingSeconds--
            }
        }
    }

    // Central Funnel: Process any OAuth authorization code or deep link
    val processCode: (String) -> Unit = { codeOrUrl ->
        if (!isSubmitting) {
            isSubmitting = true
            authStatus = OrchestratorStatus.LOADING
            statusMessage = "Autorizando sesión vía RPS Bypass..."
            errorMessage = null
            coroutineScope.launch {
                try {
                    val session = authManager.processOAuthCode(codeOrUrl)
                    authStatus = OrchestratorStatus.SUCCESS
                    statusMessage = strings.successConfiguringProfile
                    homeViewModel.clearCapturedAuthCode()
                    delay(1200)
                    onSuccess(session)
                } catch (e: Exception) {
                    authStatus = OrchestratorStatus.ERROR
                    errorMessage = e.message ?: "Error al procesar autorización"
                } finally {
                    isSubmitting = false
                }
            }
        }
    }

    // 1. Listen for system deep links captured by LoginActivity / MainActivity
    LaunchedEffect(capturedCode) {
        val code = capturedCode
        if (code != null && !isSubmitting) {
            Log.d("DualAuthModal", "Captured deep link code received: $code")
            homeViewModel.clearCapturedAuthCode()
            processCode(code)
        }
    }

    // 2. Safe Polling for Device Code (QR and 8-digit Code screen)
    LaunchedEffect(pendingAuth, currentScreen) {
        val auth = pendingAuth
        if ((currentScreen == AuthMenuScreen.QR || currentScreen == AuthMenuScreen.CODE) && auth != null && auth.isDeviceCodeFlow) {
            // Respect Microsoft strictly: interval + 500ms safety margin
            val intervalMs = ((auth.sessionId.toIntOrNull() ?: 5) * 1000L) + 500L
            Log.d("DualAuthModal", "Starting synchronized safe polling loop (${intervalMs}ms)...")
            
            var isPollingInProgress = false
            
            while (currentScreen == AuthMenuScreen.QR || currentScreen == AuthMenuScreen.CODE) {
                if (isPollingInProgress) {
                    delay(500)
                    continue
                }

                delay(intervalMs)
                isPollingInProgress = true
                
                try {
                    val tokens = authManager.checkLoginStatus(auth.deviceCode)
                    if (tokens != null) {
                        isSubmitting = true
                        authStatus = OrchestratorStatus.LOADING
                        statusMessage = "Completando handshake RPS..."
                        try {
                            val session = authManager.completeAuth(tokens)
                            authStatus = OrchestratorStatus.SUCCESS
                            statusMessage = strings.successConfiguringProfile
                            delay(1200)
                            onSuccess(session)
                            break
                        } catch (e: Exception) {
                            authStatus = OrchestratorStatus.ERROR
                            errorMessage = "Error al completar sesión: ${e.message}"
                            isSubmitting = false
                        }
                    }
                } catch (e: Exception) {
                    val msg = e.message ?: ""
                    when {
                        msg.contains("authorization_pending") -> { /* Awaiting user approval, continue polling */ }
                        msg.contains("slow_down") -> { 
                            Log.w("DualAuthModal", "Microsoft requested slow down. Adding extra delay.")
                            delay(intervalMs) 
                        }
                        msg.contains("expired_token") || msg.contains("invalid_grant") -> {
                            if (remainingSeconds <= 5) {
                                authStatus = OrchestratorStatus.ERROR
                                errorMessage = "El código ha expirado. Por favor genera uno nuevo."
                                break
                            } else {
                                Log.d("DualAuthModal", "Ignorando respuesta transitoria RFC 8628 mientras resten $remainingSeconds s")
                            }
                        }
                        else -> {
                            if (!msg.contains("Status: null")) {
                                Log.w("DualAuthModal", "Temporary polling warning: $msg")
                            }
                        }
                    }
                } finally {
                    isPollingInProgress = false
                }
            }
        }
    }

    // Handle back button inside modal
    BackHandler {
        if (currentScreen != AuthMenuScreen.MAIN) {
            currentScreen = AuthMenuScreen.MAIN
            errorMessage = null
        } else {
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.94f)
                .testTag("dual_auth_modal"),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF0E1016),
            tonalElevation = 12.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF107C10).copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Modal Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF14171E))
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (currentScreen != AuthMenuScreen.MAIN) {
                            IconButton(
                                onClick = { 
                                    currentScreen = AuthMenuScreen.MAIN 
                                    errorMessage = null
                                },
                                modifier = Modifier.testTag("back_to_menu_button")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.backToMenu, tint = Color.LightGray)
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        } else {
                            Icon(Icons.Default.SportsEsports, contentDescription = null, tint = Color(0xFF107C10), modifier = Modifier.size(26.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                        }

                        Column {
                            Text(
                                text = "Conectar con Xbox",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                            Text(
                                text = "Motor de Autenticación Universal (RPS Bypass)",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF4ADE80)
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.LightGray)
                    }
                }

                // Status & Alert Banner Area
                AnimatedVisibility(visible = errorMessage != null) {
                    Surface(
                        color = Color(0xFF7F1D1D),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFECACA))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = errorMessage ?: "",
                                color = Color(0xFFFECACA),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { errorMessage = null }) {
                                Icon(Icons.Default.Close, contentDescription = "Cerrar alerta", tint = Color.White)
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = authStatus == OrchestratorStatus.SUCCESS) {
                    Surface(
                        color = Color(0xFF14532D),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF86EFAC))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = strings.successConfiguringProfile,
                                color = Color(0xFFDCFCE7),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Modal Body Content
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when (currentScreen) {
                        AuthMenuScreen.MAIN -> {
                            // 1. EL NUEVO MENÚ DE 4 OPCIONES (Amigable y Público General)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Text(
                                    text = "Selecciona cómo prefieres iniciar sesión en tu cuenta de Microsoft:",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.LightGray.copy(alpha = 0.85f),
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )

                                // Opción 1: Conexión Directa de Escritorio (Inmune a secuestro de apps oficiales)
                                OptionCard(
                                    icon = "⚡",
                                    title = "Conexión Directa de Escritorio",
                                    subtitle = "oauth20_desktop.srf (000000004C12AE6F). Inmune a interferencia de la app móvil oficial y con renovación a 90 días.",
                                    badge = "RECOMENDADO",
                                    badgeColor = Color(0xFF107C10),
                                    testTag = "option_desktop_direct",
                                    onClick = {
                                        BiometricPromptHelper.authenticate(
                                            context = context,
                                            title = "Verificación de Seguridad (Passkey)",
                                            subtitle = "Confirma tu identidad biométrica para acceder a tu cuenta de Xbox",
                                            onSuccess = {
                                                val url = IndependentXboxAuthManager.buildAuthorizeUrl(IndependentClientProfile.XBOX_CORE_DESKTOP)
                                                desktopAuthUrl = url
                                                launchSecureBrowser(url)
                                                currentScreen = AuthMenuScreen.DESKTOP_FLOW
                                                statusMessage = "Esperando autorización de escritorio..."
                                            },
                                            onError = { error ->
                                                errorMessage = "Verificación biométrica: $error"
                                            }
                                        )
                                    }
                                )

                                // Opción 2: Escanear Código QR (Auto-OTC)
                                OptionCard(
                                    icon = "📱",
                                    title = strings.loginOptionQr,
                                    subtitle = strings.loginOptionQrDesc,
                                    badge = "AUTO-OTC",
                                    badgeColor = Color(0xFF0284C7),
                                    testTag = "option_qr",
                                    onClick = {
                                        currentScreen = AuthMenuScreen.QR
                                        coroutineScope.launch { startDeviceCodeFlow() }
                                    }
                                )

                                // Opción 3: Código de Consola (microsoft.com/link)
                                OptionCard(
                                    icon = "⌨️",
                                    title = strings.loginOptionCode,
                                    subtitle = strings.loginOptionCodeDesc,
                                    testTag = "option_code",
                                    onClick = {
                                        currentScreen = AuthMenuScreen.CODE
                                        coroutineScope.launch {
                                            if (pendingAuth == null || !pendingAuth!!.isDeviceCodeFlow) {
                                                startDeviceCodeFlow()
                                            }
                                        }
                                    }
                                )

                                // Opción 4: Interceptor Loopback Local (127.0.0.1:49215)
                                OptionCard(
                                    icon = "🔄",
                                    title = "Interceptor Loopback Local",
                                    subtitle = "Inicia un micro-servidor local en el puerto 49215 para capturar el token en 0 clics sin copiar enlaces.",
                                    badge = "1-CLIC",
                                    badgeColor = Color(0xFF8B5CF6),
                                    testTag = "option_loopback",
                                    onClick = {
                                        currentScreen = AuthMenuScreen.LOOPBACK
                                        authStatus = OrchestratorStatus.LOADING
                                        statusMessage = "Iniciando servidor local en 127.0.0.1:49215..."
                                        coroutineScope.launch {
                                            try {
                                                val loopbackUrl = IndependentXboxAuthManager.buildAuthorizeUrl(IndependentClientProfile.GODMODE_LOCAL_LOOPBACK)
                                                launchSecureBrowser(loopbackUrl)
                                                val callbackPath = IndependentXboxAuthManager.awaitLoopbackRedirect(49215)
                                                processCode("http://127.0.0.1:49215$callbackPath")
                                            } catch (e: Exception) {
                                                authStatus = OrchestratorStatus.ERROR
                                                errorMessage = "Error en loopback: ${e.message}"
                                            }
                                        }
                                    }
                                )

                                // Opción 5: Perfil Cross-Platform First-Party
                                OptionCard(
                                    icon = "🛡️",
                                    title = "Perfil Cross-Platform First-Party",
                                    subtitle = "Genera firmas criptográficas Proof-of-Possession con JoseCripto para iOS, Switch o Win32.",
                                    testTag = "option_crossplatform",
                                    onClick = {
                                        currentScreen = AuthMenuScreen.CROSS_PLATFORM
                                    }
                                )

                                // Opción 6: Sincronización LAN y Cookies
                                OptionCard(
                                    icon = "📡",
                                    title = "Sincronización LAN y Cookies",
                                    subtitle = "Rehidrata cookies de sesión web existentes o recibe credenciales vía Wi-Fi local desde otro dispositivo.",
                                    testTag = "option_lan_sync",
                                    onClick = {
                                        currentScreen = AuthMenuScreen.LAN_RELAY
                                    }
                                )

                                // Opción 7: Navegador Externo (Modo Rescate)
                                OptionCard(
                                    icon = "🌐",
                                    title = strings.loginOptionRescue,
                                    subtitle = strings.loginOptionRescueDesc,
                                    badge = "RESCATE",
                                    badgeColor = Color(0xFFD97706),
                                    testTag = "option_rescue",
                                    onClick = {
                                        val url = authManager.generateStandardLoginUrl()
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(intent)
                                        } catch (_: Exception) {}
                                        currentScreen = AuthMenuScreen.RESCUE
                                    }
                                )
                            }
                        }

                        AuthMenuScreen.QR -> {
                            // 2. VISTA CÓDIGO QR EN TIEMPO REAL CON CONTADOR REGRESIVO VISUAL
                            if (authStatus == OrchestratorStatus.LOADING) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = Color(0xFF107C10))
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(statusMessage.ifBlank { "Generando conexión segura..." }, color = Color.LightGray)
                                }
                            } else if (pendingAuth != null) {
                                val auth = pendingAuth!!
                                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                    val isWide = maxWidth > 560.dp
                                    val qrUrl = "${auth.verificationUri}?otc=${auth.userCode}"

                                    if (isWide) {
                                        Row(
                                            modifier = Modifier.fillMaxSize(),
                                            horizontalArrangement = Arrangement.spacedBy(32.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // QR Code Column with Visual Countdown Timer adjacent
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                QrAuthDisplay(
                                                    authUrl = qrUrl,
                                                    verificationCode = auth.userCode,
                                                    onCheckStatusClick = {
                                                        if (!isSubmitting) {
                                                            isSubmitting = true
                                                            coroutineScope.launch {
                                                                try {
                                                                    authStatus = OrchestratorStatus.LOADING
                                                                    val tokens = authManager.checkLoginStatus(auth.deviceCode)
                                                                    if (tokens != null) {
                                                                        val session = authManager.completeAuth(tokens)
                                                                        authStatus = OrchestratorStatus.SUCCESS
                                                                        delay(1000)
                                                                        onSuccess(session)
                                                                    } else {
                                                                        authStatus = OrchestratorStatus.POLLING_QR
                                                                        errorMessage = "Aún esperando escaneo y aprobación en tu móvil."
                                                                    }
                                                                } catch (e: Exception) {
                                                                    authStatus = OrchestratorStatus.POLLING_QR
                                                                    errorMessage = e.message ?: "Esperando confirmación..."
                                                                } finally {
                                                                    isSubmitting = false
                                                                }
                                                            }
                                                        }
                                                    },
                                                    modifier = Modifier.size(230.dp)
                                                )
                                            }

                                            // Visual Countdown Card & Instructions
                                            Column(
                                                modifier = Modifier.weight(1.2f),
                                                verticalArrangement = Arrangement.Center
                                            ) {
                                                Text(strings.scanningQrTitle, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = Color.White)
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Text("Escanea el código QR con la cámara de tu smartphone. Inicia sesión en tu cuenta y esta pantalla se actualizará automáticamente.", style = MaterialTheme.typography.bodyMedium, color = Color.LightGray)
                                                
                                                Spacer(modifier = Modifier.height(16.dp))

                                                // VISUAL COUNTDOWN TIMER NEXT TO QR CODE
                                                QrCountdownTimerCard(
                                                    remainingSeconds = remainingSeconds,
                                                    totalSeconds = auth.expiresIn,
                                                    onRefresh = { coroutineScope.launch { startDeviceCodeFlow() } }
                                                )

                                                Spacer(modifier = Modifier.height(14.dp))
                                                Text("Código de vinculación: ${auth.userCode}", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Color(0xFF4ADE80))
                                                
                                                Spacer(modifier = Modifier.height(16.dp))
                                                OutlinedButton(
                                                    onClick = { coroutineScope.launch { startDeviceCodeFlow() } },
                                                    modifier = Modifier.testTag("refresh_qr_button")
                                                ) {
                                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("Generar nuevo código")
                                                }
                                            }
                                        }
                                    } else {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .verticalScroll(rememberScrollState()),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            // Visual Countdown Timer at top of QR in vertical layout
                                            QrCountdownTimerCard(
                                                remainingSeconds = remainingSeconds,
                                                totalSeconds = auth.expiresIn,
                                                onRefresh = { coroutineScope.launch { startDeviceCodeFlow() } }
                                            )
                                            Spacer(modifier = Modifier.height(12.dp))

                                            QrAuthDisplay(
                                                authUrl = qrUrl,
                                                verificationCode = auth.userCode,
                                                onCheckStatusClick = {
                                                    if (!isSubmitting) {
                                                        isSubmitting = true
                                                        coroutineScope.launch {
                                                            try {
                                                                authStatus = OrchestratorStatus.LOADING
                                                                val tokens = authManager.checkLoginStatus(auth.deviceCode)
                                                                if (tokens != null) {
                                                                    val session = authManager.completeAuth(tokens)
                                                                    authStatus = OrchestratorStatus.SUCCESS
                                                                    delay(1000)
                                                                    onSuccess(session)
                                                                } else {
                                                                    authStatus = OrchestratorStatus.POLLING_QR
                                                                    errorMessage = "Aún esperando escaneo y confirmación."
                                                                }
                                                            } catch (e: Exception) {
                                                                authStatus = OrchestratorStatus.POLLING_QR
                                                                errorMessage = e.message ?: "Esperando confirmación..."
                                                            } finally {
                                                                isSubmitting = false
                                                            }
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.size(210.dp)
                                            )
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Text("Escanea con tu teléfono o visita microsoft.com/link", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                        }
                                    }
                                }
                            }
                        }

                        AuthMenuScreen.CODE -> {
                            // 3. VISTA CÓDIGO DE 8 LETRAS (Smart TV / Otro dispositivo)
                            if (authStatus == OrchestratorStatus.LOADING) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = Color(0xFF107C10))
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(statusMessage.ifBlank { "Generando código de 8 dígitos..." }, color = Color.LightGray)
                                }
                            } else if (pendingAuth != null) {
                                val auth = pendingAuth!!
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState()),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    // Visual Countdown Pill
                                    QrCountdownTimerCard(
                                        remainingSeconds = remainingSeconds,
                                        totalSeconds = auth.expiresIn,
                                        onRefresh = { coroutineScope.launch { startDeviceCodeFlow() } }
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))

                                    Text(
                                        text = "1. Desde cualquier PC o teléfono entra a:",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = Color.LightGray
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "microsoft.com/link",
                                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                                        color = Color(0xFF38BDF8)
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "2. E introduce este código de acceso:",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.LightGray
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))

                                    Surface(
                                        color = Color.Black.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(12.dp),
                                        border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF107C10)),
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    ) {
                                        Text(
                                            text = auth.userCode,
                                            style = MaterialTheme.typography.displayMedium.copy(
                                                fontWeight = FontWeight.Black,
                                                letterSpacing = 6.sp,
                                                textAlign = TextAlign.Center
                                            ),
                                            color = Color(0xFF4ADE80),
                                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Button(
                                            onClick = {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                val clip = ClipData.newPlainText("Xbox Code", auth.userCode)
                                                clipboard.setPrimaryClip(clip)
                                                Toast.makeText(context, strings.copySuccess, Toast.LENGTH_SHORT).show()
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E232A))
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Copiar Código")
                                        }

                                        Button(
                                            onClick = { coroutineScope.launch { startDeviceCodeFlow() } },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E232A))
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(strings.refreshCode)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(20.dp))
                                    Text(
                                        text = "La aplicación detectará automáticamente tu autorización sin recargar la pantalla.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }

                        AuthMenuScreen.RESCUE -> {
                            // 4. VISTA MODO RESCATE / NAVEGADOR EXTERNO CON ENLACE DE EMERGENCIA
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Icon(Icons.Default.HourglassTop, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(48.dp))
                                Text(
                                    text = "Esperando confirmación en el navegador...",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                                Text(
                                    text = "Hemos lanzado el inicio de sesión oficial de Microsoft. Una vez que apruebes el acceso, el sistema redirigirá de vuelta a la app.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.LightGray,
                                    textAlign = TextAlign.Center
                                )

                                Button(
                                    onClick = {
                                        val url = authManager.generateStandardLoginUrl()
                                        launchSecureBrowser(url)
                                    },
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10)),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Reabrir Navegador Seguro", fontWeight = FontWeight.Bold)
                                }

                                HorizontalDivider(color = Color(0xFF1F242D), modifier = Modifier.padding(vertical = 4.dp))

                                // Cuadro de Rescate Manual de Emergencia
                                Surface(
                                    color = Color.Black.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A313D)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            text = "¿La página quedó en blanco o no regresó sola? Pega aquí el enlace de la barra o código:",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.LightGray
                                        )

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedTextField(
                                                value = manualUrlInput,
                                                onValueChange = { 
                                                    manualUrlInput = it 
                                                    // Auto-redeem if valid ms-xal is pasted
                                                    if (it.contains("code=") || it.length > 30) {
                                                        processCode(it)
                                                    }
                                                },
                                                placeholder = { Text("ms-xal-00000000... o código", style = MaterialTheme.typography.bodySmall, color = Color.Gray) },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .testTag("manual_code_input"),
                                                singleLine = true,
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = Color(0xFF107C10),
                                                    unfocusedBorderColor = Color(0xFF333A42),
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White
                                                )
                                            )

                                            Button(
                                                onClick = { processCode(manualUrlInput) },
                                                enabled = manualUrlInput.isNotBlank() && !isSubmitting,
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10)),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.testTag("confirm_manual_code_button")
                                            ) {
                                                Text("Canjear")
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        AuthMenuScreen.DESKTOP_FLOW -> {
                            // 1. VISTA FLUJO DE ESCRITORIO DIRECTO (oauth20_desktop.srf)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Icon(Icons.Default.Bolt, contentDescription = null, tint = Color(0xFF4ADE80), modifier = Modifier.size(48.dp))
                                Text(
                                    text = "Conexión Directa de Escritorio",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                                Text(
                                    text = "Hemos abierto el portal oficial de Microsoft en modo escritorio. Este flujo no puede ser interceptado por aplicaciones oficiales externas.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.LightGray,
                                    textAlign = TextAlign.Center
                                )

                                Button(
                                    onClick = {
                                        val url = if (desktopAuthUrl.isNotBlank()) desktopAuthUrl
                                        else IndependentXboxAuthManager.buildAuthorizeUrl(IndependentClientProfile.XBOX_CORE_DESKTOP)
                                        launchSecureBrowser(url)
                                    },
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10)),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Abrir Portal de Microsoft en Navegador", fontWeight = FontWeight.Bold)
                                }

                                HorizontalDivider(color = Color(0xFF1F242D), modifier = Modifier.padding(vertical = 4.dp))

                                Surface(
                                    color = Color.Black.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A313D)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            text = "Pega aquí la URL final tras iniciar sesión (o código de retorno):",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.LightGray
                                        )

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedTextField(
                                                value = desktopPastedUrl,
                                                onValueChange = {
                                                    desktopPastedUrl = it
                                                    if (it.contains("access_token=") || it.contains("code=")) {
                                                        processCode(it)
                                                    }
                                                },
                                                placeholder = { Text("https://login.live.com/oauth20_desktop.srf#...", style = MaterialTheme.typography.bodySmall, color = Color.Gray) },
                                                modifier = Modifier.weight(1f).testTag("desktop_url_input"),
                                                singleLine = true,
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = Color(0xFF107C10),
                                                    unfocusedBorderColor = Color(0xFF333A42),
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White
                                                )
                                            )

                                            Button(
                                                onClick = { processCode(desktopPastedUrl) },
                                                enabled = desktopPastedUrl.isNotBlank() && !isSubmitting,
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10)),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.testTag("confirm_desktop_button")
                                            ) {
                                                Text("Validar")
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        AuthMenuScreen.LOOPBACK -> {
                            // 4. VISTA INTERCEPTOR LOOPBACK LOCAL (127.0.0.1:49215)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                CircularProgressIndicator(color = Color(0xFF8B5CF6), strokeWidth = 3.dp)
                                Text(
                                    text = "Servidor Loopback Activo en 127.0.0.1:49215",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                                Text(
                                    text = "God Mode está escuchando en segundo plano. Cuando termines el login en el navegador, el servidor local interceptará las credenciales automáticamente.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.LightGray,
                                    textAlign = TextAlign.Center
                                )
                                Button(
                                    onClick = {
                                        val loopbackUrl = IndependentXboxAuthManager.buildAuthorizeUrl(IndependentClientProfile.GODMODE_LOCAL_LOOPBACK)
                                        launchSecureBrowser(loopbackUrl)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
                                ) {
                                    Text("Reabrir Ventana de Loopback")
                                }
                            }
                        }

                        AuthMenuScreen.CROSS_PLATFORM -> {
                            // 5. VISTA PERFIL CROSS-PLATFORM FIRST-PARTY (JoseCripto)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Icon(Icons.Default.Shield, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(44.dp))
                                Text(
                                    text = "Autenticación First-Party JoseCripto",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                                Text(
                                    text = "Genera firmas criptográficas Proof-of-Possession basadas en ECDSA P-256 (IEEE P1363) para registrar tokens de dispositivo.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.LightGray,
                                    textAlign = TextAlign.Center
                                )

                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            try {
                                                authStatus = OrchestratorStatus.LOADING
                                                statusMessage = "Firmando dispositivo con JoseCripto..."
                                                CrossPlatformFirstPartyAuth.registerDeviceToken()
                                                statusMessage = "Dispositivo registrado con éxito. Abriendo portal..."
                                                delay(800)
                                                val url = IndependentXboxAuthManager.buildAuthorizeUrl(IndependentClientProfile.XBOX_CORE_DESKTOP)
                                                desktopAuthUrl = url
                                                launchSecureBrowser(url)
                                                currentScreen = AuthMenuScreen.DESKTOP_FLOW
                                            } catch (e: Exception) {
                                                authStatus = OrchestratorStatus.ERROR
                                                errorMessage = "Error First-Party: ${e.message}"
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                                ) {
                                    Text("Iniciar Handshake JoseCripto", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        AuthMenuScreen.LAN_RELAY -> {
                            // 6. VISTA SINCRONIZACIÓN LAN Y COOKIES
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Icon(Icons.Default.Wifi, contentDescription = null, tint = Color(0xFF00E676), modifier = Modifier.size(44.dp))
                                Text(
                                    text = "Sincronización LAN y Cookies",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                                Text(
                                    text = "Rehidrata sesiones existentes desde las cookies de WebView o recibe credenciales de forma inalámbrica desde tu teléfono en la misma red Wi-Fi.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.LightGray,
                                    textAlign = TextAlign.Center
                                )

                                Button(
                                    onClick = {
                                        val cookies = LanCompanionAndCookieHydrator.tryHydrateFromSystemCookies()
                                        if (cookies.isNotEmpty()) {
                                            errorMessage = null
                                            statusMessage = "Se detectaron ${cookies.size} cookies de sesión activas."
                                        } else {
                                            errorMessage = "No se encontraron cookies activas de Xbox en este dispositivo."
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2631))
                                ) {
                                    Text("Rehidratar desde Cookies del Sistema")
                                }

                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            try {
                                                statusMessage = "Esperando sincronización LAN en puerto 49216..."
                                                val token = LanCompanionAndCookieHydrator.awaitCompanionTokenPush(49216)
                                                processCode(token)
                                            } catch (e: Exception) {
                                                errorMessage = "Error de sincronización LAN: ${e.message}"
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10))
                                ) {
                                    Text("Esperar Vinculación Wi-Fi Local (Puerto 49216)")
                                }
                            }
                        }
                    }

                    // Blocking overlay during submission
                    if (isSubmitting) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.75f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Color(0xFF107C10))
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = statusMessage.ifBlank { "Procesando credenciales God Mode..." },
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Componente visual para mostrar el contador regresivo de validez del código QR y código de 8 letras
 */
@Composable
private fun QrCountdownTimerCard(
    remainingSeconds: Int,
    totalSeconds: Int,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val minutes = (remainingSeconds / 60).coerceAtLeast(0)
    val seconds = (remainingSeconds % 60).coerceAtLeast(0)
    val formattedTime = String.format("%02d:%02d", minutes, seconds)
    val totalSafe = totalSeconds.coerceAtLeast(1).toFloat()
    val progress = (remainingSeconds.toFloat() / totalSafe).coerceIn(0f, 1f)

    val timerColor = when {
        remainingSeconds <= 0 -> Color(0xFFEF4444)
        remainingSeconds <= 120 -> Color(0xFFEF4444) // Red alert (< 2m)
        remainingSeconds <= 300 -> Color(0xFFFBBF24) // Amber warning (< 5m)
        else -> Color(0xFF4ADE80) // Xbox Green
    }

    Surface(
        color = Color(0xFF131720),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, timerColor.copy(alpha = 0.5f)),
        modifier = modifier.testTag("qr_countdown_card")
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp)) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxSize(),
                        color = timerColor,
                        trackColor = Color.White.copy(alpha = 0.1f),
                        strokeWidth = 3.5.dp
                    )
                    Icon(
                        imageVector = if (remainingSeconds > 0) Icons.Default.Timer else Icons.Default.TimerOff,
                        contentDescription = null,
                        tint = timerColor,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (remainingSeconds > 0) "Validez del código" else "Código expirado",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.LightGray.copy(alpha = 0.8f)
                    )
                    Text(
                        text = if (remainingSeconds > 0) "Expira en: $formattedTime" else "Toca para regenerar",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                        color = timerColor
                    )
                }

                if (remainingSeconds <= 0) {
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.size(32.dp).background(timerColor.copy(alpha = 0.2f), CircleShape)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Regenerar", tint = timerColor, modifier = Modifier.size(18.dp))
                    }
                }
            }
            
            // Visual Progress Bar (Linear) at the bottom of the card
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = timerColor,
                trackColor = Color.Transparent
            )
        }
    }
}

/**
 * Componente de tarjeta de opción para el Menú Principal
 */
@Composable
private fun OptionCard(
    icon: String,
    title: String,
    subtitle: String,
    badge: String? = null,
    badgeColor: Color = Color(0xFF107C10),
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF151821),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF262C38)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = icon, fontSize = 28.sp)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    if (badge != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = badgeColor.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor)
                        ) {
                            Text(
                                text = badge,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black, fontSize = 9.sp),
                                color = Color(0xFF4ADE80),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray.copy(alpha = 0.7f)
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray)
        }
    }
}
