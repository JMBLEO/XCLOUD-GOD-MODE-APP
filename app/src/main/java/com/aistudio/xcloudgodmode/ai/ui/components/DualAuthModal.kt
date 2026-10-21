package com.aistudio.xcloudgodmode.ai.ui.components

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aistudio.xcloudgodmode.ai.services.XboxFullAuthManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DualAuthModal(
    authManager: XboxFullAuthManager,
    onDismiss: () -> Unit,
    onSuccess: (XboxFullAuthManager.XboxReadySession) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var pendingAuth by remember { mutableStateOf<XboxFullAuthManager.PendingAuthContext?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Smart TV (Device Code), 1: WebView (PKCE)
    var isSubmitting by remember { mutableStateOf(false) }

    LaunchedEffect(selectedTab) {
        isLoading = true
        errorMessage = null
        try {
            pendingAuth = if (selectedTab == 0) {
                authManager.startDeviceCodeFlow()
            } else {
                authManager.startPkceFlow()
            }
        } catch (e: Exception) {
            errorMessage = e.message
        } finally {
            isLoading = false
        }
    }

    // Polling for Device Code
    LaunchedEffect(pendingAuth, selectedTab) {
        val auth = pendingAuth
        if (selectedTab == 0 && auth != null && auth.isDeviceCodeFlow) {
            while (true) {
                delay(5000)
                try {
                    val tokens = authManager.pollDeviceCode(auth.deviceCode)
                    if (tokens != null) {
                        isSubmitting = true
                        val session = authManager.finalizeGamingSession(tokens)
                        onSuccess(session)
                        break
                    }
                } catch (e: Exception) {
                    if (!e.message?.contains("authorization_pending")!!) {
                        errorMessage = e.message
                        break
                    }
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f)
                .testTag("dual_auth_modal"),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF14171A),
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1C2024))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = Color(0xFF107C10),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Autenticación God Mode",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                            Text(
                                text = "Ruta RPS Directa (Inmune a Error 403)",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF4ADE80)
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.LightGray)
                    }
                }

                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color(0xFF16191D),
                    contentColor = Color(0xFF107C10)
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Smart TV / QR") },
                        icon = { Icon(Icons.Default.QrCode, contentDescription = null) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Nativo (WebView)") },
                        icon = { Icon(Icons.Default.Language, contentDescription = null) }
                    )
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (isLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color(0xFF107C10))
                        }
                    } else if (pendingAuth != null) {
                        val auth = pendingAuth!!
                        if (selectedTab == 0) {
                            // Smart TV Experience
                            Row(
                                modifier = Modifier.fillMaxSize().padding(32.dp),
                                horizontalArrangement = Arrangement.spacedBy(48.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Left: QR
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Surface(
                                        color = Color.White,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.size(240.dp)
                                    ) {
                                        MinimalQrCode(
                                            text = "${auth.verificationUri}?otc=${auth.userCode}",
                                            modifier = Modifier.padding(16.dp),
                                            darkColor = Color.Black
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        "Escanea con tu cámara",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color.Gray
                                    )
                                }

                                // Right: Text & Code
                                Column(modifier = Modifier.weight(1.2f)) {
                                    Text(
                                        "Sigue estos pasos:",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        "1. Escanea el código QR o entra a microsoft.com/link",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = Color.LightGray
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        "2. Ingresa este código de 8 dígitos:",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = Color.LightGray
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Surface(
                                        color = Color.Black.copy(alpha = 0.3f),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF107C10), RoundedCornerShape(8.dp))
                                    ) {
                                        Text(
                                            text = auth.userCode,
                                            style = MaterialTheme.typography.displaySmall.copy(
                                                fontWeight = FontWeight.Black,
                                                letterSpacing = 4.sp,
                                                textAlign = TextAlign.Center
                                            ),
                                            color = Color(0xFF4ADE80),
                                            modifier = Modifier.padding(16.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(24.dp))
                                    Text(
                                        "Esta pantalla se actualizará automáticamente una vez que autorices el acceso.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray
                                    )
                                }
                            }
                        } else {
                            // WebView PKCE Flow
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                                        
                                        webViewClient = object : WebViewClient() {
                                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                                if (url != null && (url.startsWith("ms-xal-000000004c20a908://auth") || url.contains("code="))) {
                                                    coroutineScope.launch {
                                                        isSubmitting = true
                                                        try {
                                                            val code = authManager.extractAuthCode(url)
                                                            val msaTokens = authManager.exchangePkceCode(code, auth.codeVerifier)
                                                            val session = authManager.finalizeGamingSession(msaTokens)
                                                            onSuccess(session)
                                                        } catch (e: Exception) {
                                                            errorMessage = e.message
                                                        } finally {
                                                            isSubmitting = false
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        loadUrl(auth.authUrl)
                                    }
                                }
                            )
                        }
                    }

                    if (errorMessage != null) {
                        Surface(
                            color = Color(0xFF7F1D1D),
                            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Error, contentDescription = null, tint = Color(0xFFFECACA))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(errorMessage!!, color = Color(0xFFFECACA), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                IconButton(onClick = { errorMessage = null }) {
                                    Icon(Icons.Default.Close, contentDescription = null, tint = Color.White)
                                }
                            }
                        }
                    }

                    if (isSubmitting) {
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Color(0xFF107C10))
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Finalizando apretón de manos con xCloud...", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}
