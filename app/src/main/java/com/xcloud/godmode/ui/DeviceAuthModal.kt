package com.xcloud.godmode.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.xcloud.godmode.network.DeviceAuthFlowState
import com.xcloud.godmode.network.DeviceAuthorizationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Native MSAL Device Code Flow (RFC 8628) Modal.
 * Completely zero-webview, rendering pure Compose UI with in-memory QR generation.
 */
@Composable
fun DeviceAuthModal(
    authManager: DeviceAuthorizationManager,
    onDismissRequest: () -> Unit,
    onAuthSuccess: (gamertag: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val flowState by authManager.flowState.collectAsState()

    var copiedToClipboard by remember { mutableStateOf(false) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Start auth on mount if idle
    LaunchedEffect(Unit) {
        if (flowState is DeviceAuthFlowState.Idle) {
            authManager.startAuthorization(coroutineScope)
        }
    }

    // Generate QR in background whenever a user code arrives
    LaunchedEffect(flowState) {
        val prompt = when (val s = flowState) {
            is DeviceAuthFlowState.PromptReady -> s.prompt
            is DeviceAuthFlowState.Polling -> s.prompt
            else -> null
        }
        if (prompt != null && qrBitmap == null) {
            withContext(Dispatchers.Default) {
                try {
                    val writer = QRCodeWriter()
                    val bitMatrix = writer.encode(prompt.verificationUriComplete, BarcodeFormat.QR_CODE, 384, 384)
                    val width = bitMatrix.width
                    val height = bitMatrix.height
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    for (x in 0 until width) {
                        for (y in 0 until height) {
                            bmp.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                        }
                    }
                    qrBitmap = bmp
                } catch (e: Exception) {
                    qrBitmap = null
                }
            }
        }
    }

    Dialog(
        onDismissRequest = {
            authManager.cancel()
            onDismissRequest()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(24.dp))
                .testTag("device_auth_dialog"),
            color = Color(0xFF14181E),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Bar with Title and Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Inicio de Sesión Xbox",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    IconButton(
                        onClick = {
                            authManager.cancel()
                            onDismissRequest()
                        },
                        modifier = Modifier.testTag("close_auth_modal_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.Gray)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (val state = flowState) {
                    is DeviceAuthFlowState.Idle -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Color(0xFF107C10))
                        }
                    }

                    is DeviceAuthFlowState.PromptReady, is DeviceAuthFlowState.Polling -> {
                        val prompt = if (state is DeviceAuthFlowState.PromptReady) state.prompt else (state as DeviceAuthFlowState.Polling).prompt
                        val secondsRemaining = if (state is DeviceAuthFlowState.PromptReady) state.secondsRemaining else (state as DeviceAuthFlowState.Polling).secondsRemaining
                        val minutes = secondsRemaining / 60
                        val seconds = secondsRemaining % 60
                        val timerString = String.format("%02d:%02d", minutes, seconds)

                        Text(
                            text = "Escanea el código QR o ingresa el código en microsoft.com/link",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFB0B8C1),
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // QR Code Surface
                        Surface(
                            color = Color.White,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .size(180.dp)
                                .padding(4.dp)
                        ) {
                            qrBitmap?.let { bmp ->
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Código QR de inicio de sesión",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(8.dp)
                                )
                            } ?: Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = Color(0xFF107C10), strokeWidth = 3.dp)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // User Code Container
                        Surface(
                            color = Color(0xFF1E2630),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "CÓDIGO DE ACCESO",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Gray,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = prompt.formattedUserCode,
                                        style = MaterialTheme.typography.headlineMedium.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.ExtraBold,
                                            letterSpacing = 3.sp
                                        ),
                                        color = Color(0xFF4ADE80),
                                        modifier = Modifier.testTag("auth_user_code_text")
                                    )
                                }

                                Button(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("Xbox Code", prompt.userCode)
                                        clipboard.setPrimaryClip(clip)
                                        copiedToClipboard = true
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (copiedToClipboard) Color(0xFF2E7D32) else Color(0xFF2D3748)
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.testTag("copy_auth_code_button")
                                ) {
                                    Icon(
                                        if (copiedToClipboard) Icons.Default.Check else Icons.Default.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (copiedToClipboard) "¡Copiado!" else "Copiar",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Open in external browser button
                        OutlinedButton(
                            onClick = {
                                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(prompt.verificationUriComplete))
                                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(browserIntent)
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF3B4858))),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .testTag("open_browser_auth_button")
                        ) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Abrir microsoft.com/link en navegador", fontSize = 13.sp)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Polling indicator with countdown
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CircularProgressIndicator(
                                color = Color(0xFF107C10),
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Esperando confirmación... ($timerString)",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }

                    is DeviceAuthFlowState.Success -> {
                        LaunchedEffect(Unit) {
                            onAuthSuccess(state.gamertag)
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(vertical = 24.dp)
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = Color(0xFF107C10),
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "¡Sesión iniciada con éxito!",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Bienvenido, ${state.gamertag}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF4ADE80)
                            )
                        }
                    }

                    is DeviceAuthFlowState.Error -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(vertical = 16.dp)
                        ) {
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                            if (state.isRetryable) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        qrBitmap = null
                                        authManager.startAuthorization(coroutineScope)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10)),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.testTag("retry_auth_button")
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Reintentar")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
