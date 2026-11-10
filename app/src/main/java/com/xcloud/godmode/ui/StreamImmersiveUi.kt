package com.xcloud.godmode.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.xcloud.godmode.network.CatalogRecordId
import com.xcloud.godmode.network.LaunchableCloudTitle
import com.xcloud.godmode.engine.LaunchAttemptId
import com.xcloud.godmode.engine.SurfaceHostRegistry
import com.xcloud.godmode.streaming.ProvisioningPollResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class StreamPhase { IDLE, PREPARING, STREAMING, SUSPENDED, FAILED }
enum class StreamErrorAction { REFRESH_CATALOG, SIGN_IN_AGAIN, RETRY_SERVICE, RETURN_TO_CATALOG }
data class StreamErrorUi(val title: String, val message: String, val primaryAction: StreamErrorAction, val correlationId: String? = null)

data class StreamUiState(
    val phase: StreamPhase = StreamPhase.IDLE,
    val launchAttemptId: LaunchAttemptId? = null,
    val gameTitle: String = "Xbox Game",
    val xCloudTitleId: String = "9NKX70BBCDRN",
    val surfaceRequired: Boolean = false,
    val showTouchOverlay: Boolean = false,
    val showDebugHud: Boolean = false,
    val hudShowNetwork: Boolean = true,
    val hudShowInput: Boolean = true,
    val hudShowFps: Boolean = true,
    val showGameBar: Boolean = false,
    val queueProgress: ProvisioningPollResult? = null,
    val error: StreamErrorUi? = null
)

class StreamUiController(private val mutableState: MutableStateFlow<StreamUiState> = MutableStateFlow(StreamUiState())) {
    val state: StateFlow<StreamUiState> = mutableState.asStateFlow()

    fun enterIdle() { mutableState.value = StreamUiState(phase = StreamPhase.IDLE) }
    fun enterPreparingControlPlane(attemptId: LaunchAttemptId, title: String = "Xbox Game", titleId: String = "9NKX70BBCDRN") {
        mutableState.value = StreamUiState(phase = StreamPhase.PREPARING, launchAttemptId = attemptId, gameTitle = title, xCloudTitleId = titleId)
    }
    fun updateQueueProgress(result: ProvisioningPollResult) {
        val current = mutableState.value
        mutableState.value = current.copy(queueProgress = result)
    }
    fun requestMediaSurface(attemptId: LaunchAttemptId) {
        val current = mutableState.value
        if (current.launchAttemptId == attemptId) mutableState.value = current.copy(surfaceRequired = true)
    }
    fun releaseMediaSurface(attemptId: LaunchAttemptId) {
        val current = mutableState.value
        if (current.launchAttemptId == attemptId) mutableState.value = current.copy(surfaceRequired = false)
    }
    fun isSurfaceRequested(attemptId: LaunchAttemptId): Boolean {
        val current = mutableState.value
        return current.launchAttemptId == attemptId && current.surfaceRequired
    }
    fun enterStreaming(attemptId: LaunchAttemptId) {
        val current = mutableState.value
        if (current.launchAttemptId == attemptId) mutableState.value = current.copy(phase = StreamPhase.STREAMING, surfaceRequired = true)
    }
    fun enterSuspended(attemptId: LaunchAttemptId) {
        val current = mutableState.value
        if (current.launchAttemptId == attemptId) mutableState.value = current.copy(phase = StreamPhase.SUSPENDED, surfaceRequired = false)
    }
    fun showNonBlockingFailure(error: StreamErrorUi) {
        mutableState.value = StreamUiState(phase = StreamPhase.FAILED, error = error)
    }

    fun toggleHudNetwork() {
        val current = mutableState.value
        mutableState.value = current.copy(hudShowNetwork = !current.hudShowNetwork)
    }

    fun toggleHudInput() {
        val current = mutableState.value
        mutableState.value = current.copy(hudShowInput = !current.hudShowInput)
    }

    fun toggleHudFps() {
        val current = mutableState.value
        mutableState.value = current.copy(hudShowFps = !current.hudShowFps)
    }
}

@Composable
fun CloudGameCard(title: LaunchableCloudTitle, uiState: StreamUiState, onPlay: (CatalogRecordId) -> Unit) {
    Button(enabled = uiState.phase == StreamPhase.IDLE || uiState.phase == StreamPhase.FAILED, onClick = { onPlay(title.recordId) }) {
        Text(title.displayName)
    }
}

@Composable
fun ImmersiveStreamEffect(phase: StreamPhase) {
    val view = LocalView.current
    val context = view.context
    DisposableEffect(phase) {
        val activity = context.findActivity() as? BaseImmersiveStreamActivity
        if (phase == StreamPhase.PREPARING || phase == StreamPhase.STREAMING || phase == StreamPhase.SUSPENDED || phase == StreamPhase.FAILED) {
            view.post { activity?.restoreImmersiveMode() }
        }
        onDispose { }
    }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun StreamRoute(
    uiState: StreamUiState, surfaceRegistry: SurfaceHostRegistry, surfaceFactory: (Context) -> SurfaceView,
    onAction: (StreamErrorAction) -> Unit, touchOverlayContent: @Composable () -> Unit = {},
    debugHudContent: @Composable () -> Unit = {}, gameBarContent: @Composable () -> Unit = {}
) {
    ImmersiveStreamEffect(uiState.phase)

    // Guardia de seguridad: jamás permitir fondo negro infinito si el coordinador está IDLE
    LaunchedEffect(uiState.phase) {
        if (uiState.phase == StreamPhase.IDLE) {
            onAction(StreamErrorAction.RETURN_TO_CATALOG)
        }
    }

    val backgroundColor = if (uiState.phase == StreamPhase.STREAMING) Color.Transparent else Color.Black

    Box(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
        if (uiState.surfaceRequired && uiState.launchAttemptId != null) {
            key(uiState.launchAttemptId.value) {
                AndroidView(
                    factory = { ctx -> surfaceFactory(ctx).also { v -> surfaceRegistry.onSurfaceViewCreated(uiState.launchAttemptId, v) } },
                    modifier = Modifier.matchParentSize()
                )
            }
        }
        if (uiState.phase == StreamPhase.PREPARING) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
        }
        if (uiState.phase == StreamPhase.SUSPENDED) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Text(text = "Sesión suspendida. Desconectando presentación...", color = Color.LightGray, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (uiState.phase == StreamPhase.STREAMING && uiState.showTouchOverlay) touchOverlayContent()
        if (uiState.phase == StreamPhase.STREAMING && uiState.showDebugHud) debugHudContent()
        if (uiState.phase == StreamPhase.STREAMING && uiState.showGameBar) gameBarContent()

        uiState.error?.let { error ->
            StreamErrorPanel(error = error, onPrimaryAction = { onAction(error.primaryAction) }, onCatalogReturn = { onAction(StreamErrorAction.RETURN_TO_CATALOG) })
        }
    }
}

@Composable
private fun StreamErrorPanel(error: StreamErrorUi, onPrimaryAction: () -> Unit, onCatalogReturn: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Card(modifier = Modifier.widthIn(max = 440.dp).padding(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF16181A))) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(text = error.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text(text = error.message, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFD0D4D9))
                if (!error.correlationId.isNullOrBlank()) Text(text = "ID de correlación: ${error.correlationId}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF8A9099))
                
                Button(onClick = onPrimaryAction, modifier = Modifier.fillMaxWidth()) {
                    Text(when (error.primaryAction) {
                        StreamErrorAction.REFRESH_CATALOG -> "Actualizar catálogo"
                        StreamErrorAction.SIGN_IN_AGAIN -> "Volver a iniciar sesión"
                        StreamErrorAction.RETRY_SERVICE -> "Reintentar conexión"
                        StreamErrorAction.RETURN_TO_CATALOG -> "Volver al catálogo"
                    })
                }
                if (error.primaryAction != StreamErrorAction.RETURN_TO_CATALOG) {
                    OutlinedButton(onClick = onCatalogReturn, modifier = Modifier.fillMaxWidth()) { Text("Volver al catálogo") }
                }
            }
        }
    }
}

abstract class BaseImmersiveStreamActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
    }
    override fun onResume() { super.onResume(); window.decorView.post { hideSystemBars() } }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.postDelayed({ hideSystemBars() }, 100L)
    }
    fun restoreImmersiveMode() = hideSystemBars()
    protected fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
