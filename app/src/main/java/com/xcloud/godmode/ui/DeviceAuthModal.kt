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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import com.xcloud.godmode.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Native Xbox Live Authentication Modal.
 * Redesigned for "Zero Friction" experience and eradicated from login.microsoftonline.com errors.
 */
@Composable
fun DeviceAuthModal(
    onDismissRequest: () -> Unit,
    onAuthSuccess: (gamertag: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val authManager = remember { ZeroFrictionXboxAuthManager.getInstance(context) }
    val uiState by authManager.loginState.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(Unit) {
        authManager.authEvents.collect { bundle ->
            onAuthSuccess(bundle.gamertag)
        }
    }

    // Generate QR Code when state is QrCodeReady
    LaunchedEffect(uiState) {
        if (uiState is LoginModalUiState.QrCodeReady) {
            val qrUrl = (uiState as LoginModalUiState.QrCodeReady).qrContentUrl
            withContext(Dispatchers.Default) {
                try {
                    val writer = QRCodeWriter()
                    val bitMatrix = writer.encode(qrUrl, BarcodeFormat.QR_CODE, 512, 512)
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
            authManager.resetModalState()
            onDismissRequest()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 500.dp)
                .clip(RoundedCornerShape(28.dp))
                .testTag("device_auth_dialog"),
            color = Color(0xFF0C0E10),
            tonalElevation = 12.dp,
            border = BorderStroke(1.dp, Color(0xFF1C2024))
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = Color(0xFF107C10),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Conectar con Xbox",
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                            color = Color.White
                        )
                    }
                    IconButton(
                        onClick = {
                            authManager.resetModalState()
                            onDismissRequest()
                        }
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.Gray)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Tabs
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = Color(0xFF107C10),
                    divider = {},
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = Color(0xFF107C10),
                            height = 3.dp
                        )
                    }
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Inicio Rápido", fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { 
                            selectedTab = 1
                            authManager.startCloudQrPairing()
                        },
                        text = { Text("Vincular QR / TV", fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal) }
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Content based on state and tab
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when (selectedTab) {
                        0 -> OneClickFlowContent(uiState) { authManager.launchOneClickBrowserLogin(context) }
                        1 -> CloudQrFlowContent(uiState, qrBitmap) { authManager.launchOneClickBrowserLogin(context) }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                
                // Privacy Note
                Text(
                    text = "Tus credenciales se procesan de forma nativa con Microsoft Consumer. God Mode no almacena ni ve tus contraseñas.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
}

@Composable
fun OneClickFlowContent(
    state: LoginModalUiState,
    onStart: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        when (state) {
            is LoginModalUiState.PreparingVerifiedUrl -> {
                CircularProgressIndicator(color = Color(0xFF107C10))
                Spacer(modifier = Modifier.height(16.dp))
                Text("Validando servidor seguro...", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
            is LoginModalUiState.ExchangingTokens -> {
                CircularProgressIndicator(color = Color(0xFF107C10))
                Spacer(modifier = Modifier.height(16.dp))
                Text("Finalizando autenticación...", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
            is LoginModalUiState.WaitingForBrowserReturn -> {
                Icon(Icons.Default.OpenInBrowser, contentDescription = null, tint = Color(0xFF107C10), modifier = Modifier.size(64.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text("Iniciando sesión segura...", color = Color.White, fontWeight = FontWeight.Bold)
                Text("Aprobación abierta en el navegador.", color = Color.Gray, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
            is LoginModalUiState.Error -> {
                Text("Algo salió mal", color = Color.Red, style = MaterialTheme.typography.titleMedium)
                Text(state.message, color = Color.Gray, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onStart, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10))) {
                    Text("Reintentar")
                }
            }
            is LoginModalUiState.Success -> {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF107C10), modifier = Modifier.size(64.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text("¡Bienvenido, ${state.gamertag}!", color = Color.White, style = MaterialTheme.typography.titleLarge)
            }
            else -> {
                Text(
                    "Recomendado para móviles y tablets. Inicia sesión con un solo toque usando el navegador del sistema.",
                    color = Color.LightGray,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10))
                ) {
                    Icon(Icons.Default.VpnKey, contentDescription = null)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Iniciar Sesión ahora", fontWeight = FontWeight.Black, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
fun CloudQrFlowContent(
    state: LoginModalUiState,
    qrBitmap: Bitmap?,
    onFallbackClick: () -> Unit
) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        when (state) {
            is LoginModalUiState.PreparingVerifiedUrl -> {
                CircularProgressIndicator(color = Color(0xFF107C10))
                Spacer(modifier = Modifier.height(16.dp))
                Text("Pre-validando conexión QR...", color = Color.White)
            }
            is LoginModalUiState.QrCodeReady -> {
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.size(200.dp).padding(4.dp)
                ) {
                    qrBitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier.fillMaxSize().padding(12.dp)
                        )
                    } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF107C10))
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                
                if (state.userCode != null) {
                    Text(
                        text = state.userCode,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 4.sp
                        ),
                        color = Color(0xFF107C10)
                    )
                    Text(
                        "Introduce este código en microsoft.com/link",
                        color = Color.Gray,
                        style = MaterialTheme.typography.labelMedium
                    )
                } else {
                    Text(
                        "Escanea el QR para vincular",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "Acceso directo preautorizado",
                        color = Color.Gray,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Válido por ${state.remainingSeconds / 60}m ${state.remainingSeconds % 60}s",
                    color = Color.DarkGray,
                    style = MaterialTheme.typography.labelSmall
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                OutlinedButton(
                    onClick = onFallbackClick,
                    border = BorderStroke(1.dp, Color(0xFF107C10).copy(alpha = 0.5f))
                ) {
                    Text("¿Problemas? Usar inicio rápido", color = Color(0xFF107C10), fontSize = 12.sp)
                }
            }
            is LoginModalUiState.ExchangingTokens -> {
                CircularProgressIndicator(color = Color(0xFF107C10))
                Spacer(modifier = Modifier.height(16.dp))
                Text("Sincronizando perfil de Xbox...", color = Color.White)
            }
            is LoginModalUiState.Error -> {
                Text("Error", color = Color.Red, style = MaterialTheme.typography.titleMedium)
                Text(state.message, color = Color.Gray, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { /* Could retry pairing */ }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10))) {
                    Text("Reintentar")
                }
            }
            is LoginModalUiState.Success -> {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF107C10), modifier = Modifier.size(64.dp))
                Text("¡Conectado!", color = Color.White, style = MaterialTheme.typography.titleLarge)
            }
            else -> {
                CircularProgressIndicator(color = Color(0xFF107C10))
            }
        }
    }
}
