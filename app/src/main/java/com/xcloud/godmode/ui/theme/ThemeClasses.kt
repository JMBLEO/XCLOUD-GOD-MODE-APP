package com.xcloud.godmode.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xcloud.godmode.models.SettingsModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ============================================================================
// 1. ESCALADO DE PANTALLA Y DENSIDAD RESPONSIVA
// ============================================================================

data class ScreenScale(
    val posterScale: Float = 1.0f,
    val uiScale: Float = 1.0f
) {
    fun scaledDp(dp: Float): Dp = (dp * uiScale).dp
}

val LocalScreenScale = compositionLocalOf { ScreenScale() }

@Composable
fun LocalScreenScaleProvider(settings: SettingsModel, content: @Composable () -> Unit) {
    val scale = remember(settings.posterScalePercent, settings.iconScalePercent) {
        val pScale = (settings.posterScalePercent.coerceIn(50, 200)) / 100f
        val uScale = (settings.iconScalePercent.coerceIn(50, 200)) / 100f
        ScreenScale(posterScale = pScale, uiScale = uScale)
    }
    CompositionLocalProvider(LocalScreenScale provides scale) {
        content()
    }
}

// ============================================================================
// 2. MODELO DE TEXTOS Y LOCALIZACIÓN (APP STRINGS)
// ============================================================================

data class AppStrings(
    val accessibilityCount: String,
    val allIncludedSubscription: String,
    val aspectRatioLabel: String,
    val backToMenu: String,
    val backupImportSuccess: String,
    val backupInvalidJson: String,
    val compatibleWith: String,
    val consolesTab: String,
    val controller: String,
    val copySuccess: String,
    val fsrSharpnessLabel: String,
    val hudPositionBottom: String,
    val hudPositionLabel: String,
    val hudPositionTop: String,
    val languagesCount: String,
    val loginOptionCode: String,
    val loginOptionCodeDesc: String,
    val loginOptionHere: String,
    val loginOptionHereDesc: String,
    val loginOptionQr: String,
    val loginOptionQrDesc: String,
    val loginOptionRescue: String,
    val loginOptionRescueDesc: String,
    val mouseKeyboard: String,
    val playNow: String,
    val refreshCode: String,
    val scanningQrTitle: String,
    val serverInfoTitle: String,
    val settingsBypass: String,
    val settingsInput: String,
    val settingsStreaming: String,
    val settingsTab: String,
    val showHudLabel: String,
    val streamSettingsTitle: String,
    val subscriptionNotice: String,
    val successConfiguringProfile: String,
    val touch: String,
    val touchEditorButtonSelected: String,
    val touchEditorCustomize: String,
    val touchEditorExit: String,
    val touchEditorHaptics: String,
    val touchEditorOpacity: String,
    val touchEditorResetAll: String,
    val touchEditorResetButton: String,
    val touchEditorSavedSuccess: String,
    val touchEditorSaveProfile: String,
    val touchEditorScale: String,
    val touchEditorSnapGrid: String,
    val touchEditorTestMode: String,
    val touchEditorTitle: String,
    val visibleMetricsLabel: String,
    val volumeBoostLabel: String,
    val waitingForAuth: String,
    val xcloudTab: String
) {
    companion object {
        private val SPANISH = AppStrings(
            accessibilityCount = "Funciones de accesibilidad",
            allIncludedSubscription = "Incluido con Game Pass",
            aspectRatioLabel = "Relación de aspecto",
            backToMenu = "Volver al menú",
            backupImportSuccess = "Configuración importada con éxito",
            backupInvalidJson = "JSON de configuración inválido",
            compatibleWith = "Compatible con",
            consolesTab = "Consolas",
            controller = "Controlador",
            copySuccess = "Copiado al portapapeles",
            fsrSharpnessLabel = "Nitidez FSR",
            hudPositionBottom = "Inferior",
            hudPositionLabel = "Posición del HUD",
            hudPositionTop = "Superior",
            languagesCount = "Idiomas disponibles",
            loginOptionCode = "Código en navegador",
            loginOptionCodeDesc = "Introduce el código en login.live.com/oauth20_remoteconnect.srf",
            loginOptionHere = "Iniciar sesión en esta pantalla",
            loginOptionHereDesc = "Autenticación segura directa en la app",
            loginOptionQr = "Código QR (Smart TV)",
            loginOptionQrDesc = "Escanea el código QR desde tu teléfono o tablet",
            loginOptionRescue = "Navegador de rescate",
            loginOptionRescueDesc = "Abre el navegador del sistema si tienes problemas",
            mouseKeyboard = "Teclado y ratón",
            playNow = "JUGAR AHORA",
            refreshCode = "Generar nuevo código",
            scanningQrTitle = "Escanea el código QR",
            serverInfoTitle = "Información del servidor",
            settingsBypass = "Bypass y Red",
            settingsInput = "Controles y Periféricos",
            settingsStreaming = "Streaming y Rendimiento",
            settingsTab = "Ajustes",
            showHudLabel = "Mostrar HUD de telemetría",
            streamSettingsTitle = "Ajustes de Stream",
            subscriptionNotice = "Requiere suscripción activa de Xbox Game Pass Ultimate o juego F2P compatible",
            successConfiguringProfile = "¡Perfil configurado con éxito!",
            touch = "Pantalla táctil",
            touchEditorButtonSelected = "Botón seleccionado",
            touchEditorCustomize = "Personalizar controles táctiles",
            touchEditorExit = "Salir del editor",
            touchEditorHaptics = "Vibración háptica",
            touchEditorOpacity = "Opacidad",
            touchEditorResetAll = "Restablecer todos",
            touchEditorResetButton = "Restablecer botón",
            touchEditorSavedSuccess = "Diseño de controles guardado",
            touchEditorSaveProfile = "Guardar perfil táctil",
            touchEditorScale = "Escala",
            touchEditorSnapGrid = "Alinear a la cuadrícula",
            touchEditorTestMode = "Modo de prueba",
            touchEditorTitle = "Editor de Controles Táctiles",
            visibleMetricsLabel = "Métricas visibles",
            volumeBoostLabel = "Amplificación de volumen",
            waitingForAuth = "Esperando autorización...",
            xcloudTab = "Xbox Cloud"
        )

        private val ENGLISH = AppStrings(
            accessibilityCount = "Accessibility features",
            allIncludedSubscription = "Included with Game Pass",
            aspectRatioLabel = "Aspect Ratio",
            backToMenu = "Back to menu",
            backupImportSuccess = "Settings imported successfully",
            backupInvalidJson = "Invalid settings JSON",
            compatibleWith = "Compatible with",
            consolesTab = "Consoles",
            controller = "Controller",
            copySuccess = "Copied to clipboard",
            fsrSharpnessLabel = "FSR Sharpness",
            hudPositionBottom = "Bottom",
            hudPositionLabel = "HUD Position",
            hudPositionTop = "Top",
            languagesCount = "Available languages",
            loginOptionCode = "Browser code",
            loginOptionCodeDesc = "Enter the 8-digit code at login.live.com/oauth20_remoteconnect.srf",
            loginOptionHere = "Sign in on this screen",
            loginOptionHereDesc = "Direct secure authentication inside the app",
            loginOptionQr = "QR Code (Smart TV)",
            loginOptionQrDesc = "Scan the QR code from your phone or tablet",
            loginOptionRescue = "Rescue browser",
            loginOptionRescueDesc = "Open system browser if you encounter issues",
            mouseKeyboard = "Mouse & Keyboard",
            playNow = "PLAY NOW",
            refreshCode = "Generate new code",
            scanningQrTitle = "Scan the QR Code",
            serverInfoTitle = "Server Information",
            settingsBypass = "Bypass & Network",
            settingsInput = "Controls & Peripherals",
            settingsStreaming = "Streaming & Performance",
            settingsTab = "Settings",
            showHudLabel = "Show telemetry HUD",
            streamSettingsTitle = "Stream Settings",
            subscriptionNotice = "Requires active Xbox Game Pass Ultimate subscription or compatible F2P game",
            successConfiguringProfile = "Profile configured successfully!",
            touch = "Touch screen",
            touchEditorButtonSelected = "Selected button",
            touchEditorCustomize = "Customize touch controls",
            touchEditorExit = "Exit editor",
            touchEditorHaptics = "Haptic feedback",
            touchEditorOpacity = "Opacity",
            touchEditorResetAll = "Reset all",
            touchEditorResetButton = "Reset button",
            touchEditorSavedSuccess = "Touch layout saved",
            touchEditorSaveProfile = "Save touch profile",
            touchEditorScale = "Scale",
            touchEditorSnapGrid = "Snap to grid",
            touchEditorTestMode = "Test mode",
            touchEditorTitle = "Touch Controls Editor",
            visibleMetricsLabel = "Visible metrics",
            volumeBoostLabel = "Volume boost",
            waitingForAuth = "Waiting for authorization...",
            xcloudTab = "Xbox Cloud"
        )

        fun get(languageCode: String?): AppStrings {
            val code = languageCode?.lowercase() ?: "es"
            return if (code.startsWith("en")) ENGLISH else SPANISH
        }
    }
}

// ============================================================================
// 3. GESTOR DE IDIOMAS Y LOCAL STRINGS COMPOSITION LOCAL
// ============================================================================

object AppLocaleManager {
    private val _currentLanguageState = MutableStateFlow("es-CL")
    val currentLanguageState: StateFlow<String> = _currentLanguageState.asStateFlow()

    private val _currentStrings = MutableStateFlow(AppStrings.get("es-CL"))
    val currentStrings: StateFlow<AppStrings> = _currentStrings.asStateFlow()

    fun setLanguageByCode(code: String) {
        val normalized = if (code.equals("AUTO", ignoreCase = true)) "es-CL" else code
        _currentLanguageState.value = normalized
        _currentStrings.value = AppStrings.get(normalized)
    }
}

val LocalAppStrings = compositionLocalOf { AppStrings.get("es-CL") }
