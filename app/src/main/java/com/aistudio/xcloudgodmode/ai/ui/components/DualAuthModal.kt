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

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DualAuthModal(
    authManager: XboxFullAuthManager,
    settings: com.aistudio.xcloudgodmode.ai.models.SettingsModel = com.aistudio.xcloudgodmode.ai.models.SettingsModel(),
    onDismiss: () -> Unit,
    onSuccess: (XboxFullAuthManager.XboxReadySession) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val strings = com.aistudio.xcloudgodmode.ai.ui.theme.AppStrings.get(settings.appLanguage)
    var pendingAuth by remember { mutableStateOf<XboxFullAuthManager.PendingAuthContext?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Smart TV (Device Code), 1: WebView (PKCE)
    var isSubmitting by remember { mutableStateOf(false) }
    var webProgress by remember { mutableIntStateOf(0) }
    var showManualCodeEntry by remember { mutableStateOf(false) }
    var manualCodeInput by remember { mutableStateOf("") }
    var activeWebView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(selectedTab) {
        isLoading = true
        errorMessage = null
        try {
            pendingAuth = if (selectedTab == 0) {
                authManager.initDeviceLogin()
            } else {
                authManager.initWebLogin()
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
                    val tokens = authManager.checkLoginStatus(auth.deviceCode)
                    if (tokens != null) {
                        isSubmitting = true
                        val session = authManager.completeAuth(tokens)
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
                        text = { Text(strings.scanningQrTitle) },
                        icon = { Icon(Icons.Default.QrCode, contentDescription = null) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(strings.nativeLoginTitle) },
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
                            // Smart TV & Mobile Adaptive QR Experience
                            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                                val isWide = maxWidth > maxHeight || maxWidth >= 580.dp
                                val qrScale = (settings.qrScalePercent / 100f).coerceIn(0.8f, 1.5f)
                                val textScale = (settings.textScalePercent / 100f).coerceIn(0.85f, 1.6f)

                                if (isWide) {
                                    // Pantalla Panorámica / Horizontal (Tablets, TVs, Handhelds)
                                    val qrBoxSize = (maxHeight * 0.52f * qrScale).coerceIn(140.dp, 280.dp)
                                    Row(
                                        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(36.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Surface(
                                                color = Color.White,
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier.size(qrBoxSize)
                                            ) {
                                                MinimalQrCode(
                                                    text = "${auth.verificationUri}?otc=${auth.userCode}",
                                                    modifier = Modifier.padding(12.dp),
                                                    darkColor = Color.Black
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Text(
                                                strings.scanQrPrompt,
                                                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp * textScale),
                                                color = Color.Gray
                                            )
                                        }

                                        Column(
                                            modifier = Modifier.weight(1.3f),
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text(
                                                strings.scanningQrTitle,
                                                style = MaterialTheme.typography.titleLarge.copy(
                                                    fontSize = 20.sp * textScale,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = Color.White
                                            )
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Text(
                                                strings.step1Prompt,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp * textScale),
                                                color = Color.LightGray
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                strings.step2Prompt,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp * textScale),
                                                color = Color.LightGray
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Surface(
                                                color = Color.Black.copy(alpha = 0.35f),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .border(1.dp, Color(0xFF107C10), RoundedCornerShape(8.dp))
                                            ) {
                                                Text(
                                                    text = auth.userCode,
                                                    style = MaterialTheme.typography.displaySmall.copy(
                                                        fontWeight = FontWeight.Black,
                                                        letterSpacing = 4.sp,
                                                        fontSize = 32.sp * textScale,
                                                        textAlign = TextAlign.Center
                                                    ),
                                                    color = Color(0xFF4ADE80),
                                                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(16.dp))
                                            Text(
                                                strings.autoUpdateNotice,
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp * textScale),
                                                color = Color.Gray
                                            )
                                        }
                                    }
                                } else {
                                    // Pantalla Vertical (Teléfonos Móviles)
                                    val qrBoxSize = (maxWidth * 0.52f * qrScale).coerceIn(140.dp, 230.dp)
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(rememberScrollState())
                                            .padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        Surface(
                                            color = Color.White,
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.size(qrBoxSize)
                                        ) {
                                            MinimalQrCode(
                                                text = "${auth.verificationUri}?otc=${auth.userCode}",
                                                modifier = Modifier.padding(10.dp),
                                                darkColor = Color.Black
                                            )
                                        }
                                        Text(
                                            strings.scanQrPrompt,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp * textScale),
                                            color = Color.Gray
                                        )

                                        Text(
                                            strings.step1Prompt,
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp * textScale),
                                            color = Color.LightGray,
                                            textAlign = TextAlign.Center
                                        )

                                        Text(
                                            strings.step2Prompt,
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp * textScale),
                                            color = Color.LightGray,
                                            textAlign = TextAlign.Center
                                        )

                                        Surface(
                                            color = Color.Black.copy(alpha = 0.35f),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .fillMaxWidth(0.9f)
                                                .border(1.dp, Color(0xFF107C10), RoundedCornerShape(8.dp))
                                        ) {
                                            Text(
                                                text = auth.userCode,
                                                style = MaterialTheme.typography.titleLarge.copy(
                                                    fontWeight = FontWeight.Black,
                                                    letterSpacing = 4.sp,
                                                    fontSize = 24.sp * textScale,
                                                    textAlign = TextAlign.Center
                                                ),
                                                color = Color(0xFF4ADE80),
                                                modifier = Modifier.padding(12.dp)
                                            )
                                        }

                                        Text(
                                            strings.autoUpdateNotice,
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp * textScale),
                                            color = Color.Gray,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        } else {
                            // WebView PKCE Flow con Interceptación Completa y Cookies
                            Column(modifier = Modifier.fillMaxSize()) {
                                // Toolbar de ayuda para inicio nativo
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF16191D))
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(Color(0xFF107C10), RoundedCornerShape(4.dp))
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = strings.nativeLoginTitle,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color.LightGray
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = { activeWebView?.reload() },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Refresh,
                                                contentDescription = strings.reloadPage,
                                                tint = Color.LightGray,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                try {
                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(auth.authUrl))
                                                    context.startActivity(intent)
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "No se pudo abrir navegador: ${e.message}", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.OpenInNew,
                                                contentDescription = strings.openExternalBrowser,
                                                tint = Color(0xFF4ADE80),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        TextButton(
                                            onClick = { showManualCodeEntry = !showManualCodeEntry },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = if (showManualCodeEntry) "Ocultar" else strings.redeemCode,
                                                color = Color(0xFF38BDF8),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                }

                                // Indicador de carga de la página
                                if (webProgress in 1..99) {
                                    LinearProgressIndicator(
                                        progress = { webProgress / 100f },
                                        modifier = Modifier.fillMaxWidth().height(2.dp),
                                        color = Color(0xFF107C10),
                                        trackColor = Color(0xFF1C2024)
                                    )
                                }

                                // Tarjeta desplegable para pegar código manualmente
                                if (showManualCodeEntry) {
                                    Surface(
                                        color = Color(0xFF1A1F26),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp)
                                            .border(1.dp, Color(0xFF282D33), RoundedCornerShape(8.dp))
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text(
                                                text = strings.manualCodePrompt,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = Color.LightGray
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                OutlinedTextField(
                                                    value = manualCodeInput,
                                                    onValueChange = { manualCodeInput = it },
                                                    placeholder = { Text("ms-xal-... o código M.C544...", style = MaterialTheme.typography.bodySmall, color = Color.Gray) },
                                                    modifier = Modifier.weight(1f),
                                                    singleLine = true,
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        focusedBorderColor = Color(0xFF107C10),
                                                        unfocusedBorderColor = Color(0xFF333A42),
                                                        focusedTextColor = Color.White,
                                                        unfocusedTextColor = Color.White
                                                    )
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Button(
                                                    onClick = {
                                                        val code = authManager.extractAuthCode(manualCodeInput)
                                                        if (code.isNotEmpty()) {
                                                            coroutineScope.launch {
                                                                isSubmitting = true
                                                                try {
                                                                    val msaTokens = authManager.tokenExchange(code, auth.codeVerifier)
                                                                    val session = authManager.completeAuth(msaTokens)
                                                                    onSuccess(session)
                                                                } catch (e: Exception) {
                                                                    errorMessage = "Error en canje: ${e.message}"
                                                                } finally {
                                                                    isSubmitting = false
                                                                }
                                                            }
                                                        }
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10)),
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Text(strings.redeemCode, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }

                                // WebView Nativo con Soporte Completo
                                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                    key(auth.authUrl) {
                                        AndroidView(
                                            modifier = Modifier.fillMaxSize(),
                                            factory = { ctx ->
                                                WebView(ctx).apply {
                                                    activeWebView = this
                                                    layoutParams = android.view.ViewGroup.LayoutParams(
                                                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                                    )

                                                    // Habilitar cookies indispensables para Microsoft Live
                                                    val cookieManager = android.webkit.CookieManager.getInstance()
                                                    cookieManager.setAcceptCookie(true)
                                                    cookieManager.setAcceptThirdPartyCookies(this, true)

                                                    this.settings.apply {
                                                        javaScriptEnabled = true
                                                        domStorageEnabled = true
                                                        databaseEnabled = true
                                                        loadsImagesAutomatically = true
                                                        useWideViewPort = true
                                                        loadWithOverviewMode = true
                                                        javaScriptCanOpenWindowsAutomatically = true
                                                        cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                                                        userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                                                    }

                                                    webChromeClient = object : android.webkit.WebChromeClient() {
                                                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                                            webProgress = newProgress
                                                        }
                                                    }

                                                    webViewClient = object : WebViewClient() {
                                                        private fun handleUrlIntercept(url: String?): Boolean {
                                                            if (url == null) return false
                                                            if (url.startsWith("ms-xal-000000004c20a908://auth") ||
                                                                url.startsWith("ms-xal-") ||
                                                                url.contains("code=") ||
                                                                url.contains("approval_code=")
                                                            ) {
                                                                val code = authManager.extractAuthCode(url)
                                                                if (code.isNotEmpty() && !code.startsWith("http")) {
                                                                    coroutineScope.launch {
                                                                        isSubmitting = true
                                                                        try {
                                                                            val msaTokens = authManager.tokenExchange(code, auth.codeVerifier)
                                                                            val session = authManager.completeAuth(msaTokens)
                                                                            onSuccess(session)
                                                                        } catch (e: Exception) {
                                                                            errorMessage = "Error en canje de credenciales: ${e.message}"
                                                                        } finally {
                                                                            isSubmitting = false
                                                                        }
                                                                    }
                                                                    return true
                                                                }
                                                            }
                                                            return false
                                                        }

                                                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                                            val url = request?.url?.toString()
                                                            return handleUrlIntercept(url)
                                                        }

                                                        @Deprecated("Deprecated in Java")
                                                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                                            return handleUrlIntercept(url)
                                                        }

                                                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                                            handleUrlIntercept(url)
                                                        }

                                                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                                                            if (request?.isForMainFrame == true) {
                                                                val desc = error?.description?.toString() ?: ""
                                                                if (!desc.contains("ERR_UNKNOWN_URL_SCHEME") && !desc.contains("net::ERR_FAILED")) {
                                                                    errorMessage = "Conexión interrumpida ($desc). Toca recargar o abre en navegador externo."
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
                            }
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
