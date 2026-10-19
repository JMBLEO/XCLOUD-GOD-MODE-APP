package com.aistudio.xcloudgodmode.ai.ui.screens

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aistudio.xcloudgodmode.ai.services.WebRTCStreamManager
import com.aistudio.xcloudgodmode.ai.ui.components.LatencyHUD
import org.webrtc.SurfaceViewRenderer

@Composable
fun StreamScreen(
    streamManager: WebRTCStreamManager,
    gsToken: String,
    sessionPath: String,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    var isHudVisible by remember { mutableStateOf(true) }

    val surfaceViewRenderer = remember {
        SurfaceViewRenderer(context).apply {
            init(streamManager.eglBase.eglBaseContext, null)
            setEnableHardwareScaler(true)
        }
    }

    LaunchedEffect(gsToken, sessionPath) {
        streamManager.setVideoSink(surfaceViewRenderer)
        streamManager.startStream(gsToken, sessionPath)
    }

    DisposableEffect(Unit) {
        onDispose {
            streamManager.stopStream()
            surfaceViewRenderer.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Video Renderer
        AndroidView(
            factory = { surfaceViewRenderer },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay Controls
        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            // Top Right: Controls
            Row(
                modifier = Modifier.align(Alignment.TopEnd),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = { isHudVisible = !isHudVisible },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Default.Info, contentDescription = "Toggle HUD", tint = Color.White)
                }
                IconButton(
                    onClick = onOpenSettings,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                }
                IconButton(
                    onClick = onClose,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }

            // Top Left: Latency HUD
            if (isHudVisible) {
                LatencyHUD(
                    streamManager = streamManager,
                    modifier = Modifier.align(Alignment.TopStart)
                )
            }
        }
    }
}
