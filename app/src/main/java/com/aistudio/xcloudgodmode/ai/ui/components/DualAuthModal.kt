package com.aistudio.xcloudgodmode.ai.ui.components

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import android.util.Log

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DualAuthModal(
    authManager: XboxFullAuthManager,
    homeViewModel: com.aistudio.xcloudgodmode.ai.viewmodels.HomeViewModel,
    settings: com.aistudio.xcloudgodmode.ai.models.SettingsModel = com.aistudio.xcloudgodmode.ai.models.SettingsModel(),
    onDismiss: () -> Unit,
    onSuccess: (XboxFullAuthManager.XboxReadySession) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val strings = com.aistudio.xcloudgodmode.ai.ui.theme.AppStrings.get(settings.appLanguage)
    var pendingAuth by remember { mutableStateOf<XboxFullAuthManager.PendingAuthContext?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingQr by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Smart TV (Device Code), 1: WebView (PKCE/Sisu)
    var isSubmitting by remember { mutableStateOf(false) }
    var webProgress by remember { mutableIntStateOf(0) }
    var showManualCodeEntry by remember { mutableStateOf(false) }
    var manualCodeInput by remember { mutableStateOf("") }
    var activeWebView by remember { mutableStateOf<WebView?>(null) }
    
    val capturedCode by homeViewModel.capturedAuthCode.collectAsState()

    // Handle automatically captured deep link
    LaunchedEffect(capturedCode, pendingAuth) {
        val code = capturedCode
        val auth = pendingAuth
        if (code != null && auth != null && !auth.isDeviceCodeFlow) {
            isSubmitting = true
            try {
                val session = authManager.completeSisuAuth(code, auth)
                homeViewModel.clearCapturedAuthCode()
                onSuccess(session)
            } catch (e: Exception) {
                errorMessage = "Error en canje automático: ${e.message}"
            } finally {
                isSubmitting = false
            }
        }
    }

    val refreshQr = suspend {
        isLoadingQr = true
        errorMessage = null
        try {
            pendingAuth = authManager.initDeviceLogin()
        } catch (e: Exception) {
            errorMessage = "Error al generar código: ${e.message}"
        } finally {
            isLoadingQr = false
        }
    }

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
                    val msg = e.message ?: ""
                    if (!msg.contains("authorization_pending") && !msg.contains("Status: null")) {
                        if (msg.contains("expired_token")) {
                           errorMessage = "El código ha expirado. Por favor actualízalo."
                        }
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
                                text = "Handshake RPS + Sisu (God Mode)",
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
                            // Smart TV Tab with Manual Confirm button
                            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                                val isWide = maxWidth > maxHeight || maxWidth >= 580.dp
                                val qrScale = (settings.qrScalePercent / 100f).coerceIn(0.8f, 1.5f)
                                val textScale = (settings.textScalePercent / 100f).coerceIn(0.85f, 1.6f)

                                if (isWide) {
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
                                            QrAuthDisplay(
                                                authUrl = "${auth.verificationUri}?otc=${auth.userCode}",
                                                verificationCode = auth.userCode,
                                                onCheckStatusClick = {
                                                    coroutineScope.launch {
                                                        isLoadingQr = true
                                                        try {
                                                            val tokens = authManager.checkLoginStatus(auth.deviceCode)
                                                            if (tokens != null) {
                                                                isSubmitting = true
                                                                val session = authManager.completeAuth(tokens)
                                                                Toast.makeText(context, strings.confirmQrSuccess, Toast.LENGTH_SHORT).show()
                                                                onSuccess(session)
                                                            } else {
                                                                errorMessage = "Activación pendiente. Ingresa el código en tu celular/PC."
                                                            }
                                                        } catch (e: Exception) {
                                                            errorMessage = "Error: ${e.message}"
                                                        } finally {
                                                            isLoadingQr = false
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.size(qrBoxSize)
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
                                            
                                            Surface(
                                                color = Color.Black.copy(alpha = 0.35f),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .border(1.dp, Color(0xFF107C10), RoundedCornerShape(8.dp))
                                            ) {
                                                Column(modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp)) {
                                                    Text(
                                                        text = auth.userCode,
                                                        style = MaterialTheme.typography.displaySmall.copy(
                                                            fontWeight = FontWeight.Black,
                                                            letterSpacing = 4.sp,
                                                            fontSize = 32.sp * textScale,
                                                            textAlign = TextAlign.Center
                                                        ),
                                                        color = Color(0xFF4ADE80),
                                                        modifier = Modifier.fillMaxWidth()
                                                    )
                                                    
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.Center,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        TextButton(
                                                            onClick = {
                                                                coroutineScope.launch {
                                                                    isLoadingQr = true
                                                                    try {
                                                                        val tokens = authManager.checkLoginStatus(auth.deviceCode)
                                                                        if (tokens != null) {
                                                                            isSubmitting = true
                                                                            val session = authManager.completeAuth(tokens)
                                                                            Toast.makeText(context, strings.confirmQrSuccess, Toast.LENGTH_SHORT).show()
                                                                            onSuccess(session)
                                                                        } else {
                                                                            errorMessage = "Activación pendiente. Ingresa el código en tu celular/PC."
                                                                        }
                                                                    } catch (e: Exception) {
                                                                        errorMessage = "Error: ${e.message}"
                                                                    } finally {
                                                                        isLoadingQr = false
                                                                    }
                                                                }
                                                            }
                                                        ) {
                                                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Text(strings.confirmQr, style = MaterialTheme.typography.labelSmall)
                                                        }
                                                        
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        
                                                        TextButton(
                                                            onClick = { coroutineScope.launch { refreshQr() } }
                                                        ) {
                                                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Text(strings.refreshCode, style = MaterialTheme.typography.labelSmall)
                                                        }
                                                    }
                                                }
                                            }
                                            
                                            Spacer(modifier = Modifier.height(16.dp))
                                            Text(
                                                strings.step1Prompt,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp * textScale),
                                                color = Color.LightGray
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                strings.step2Prompt,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp * textScale),
                                                color = Color.LightGray
                                            )
                                            Spacer(modifier = Modifier.height(16.dp))
                                            Text(
                                                strings.autoUpdateNotice,
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp * textScale),
                                                color = Color.Gray
                                            )
                                        }
                                    }
                                } else {
                                    // Pantalla Vertical
                                    val qrBoxSize = (maxWidth * 0.52f * qrScale).coerceIn(140.dp, 230.dp)
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(rememberScrollState())
                                            .padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        QrAuthDisplay(
                                            authUrl = "${auth.verificationUri}?otc=${auth.userCode}",
                                            verificationCode = auth.userCode,
                                            onCheckStatusClick = {
                                                coroutineScope.launch {
                                                    isLoadingQr = true
                                                    try {
                                                        val tokens = authManager.checkLoginStatus(auth.deviceCode)
                                                        if (tokens != null) {
                                                            isSubmitting = true
                                                            val session = authManager.completeAuth(tokens)
                                                            Toast.makeText(context, strings.confirmQrSuccess, Toast.LENGTH_SHORT).show()
                                                            onSuccess(session)
                                                        } else {
                                                            errorMessage = "Aún no autorizado."
                                                        }
                                                    } catch (e: Exception) {
                                                        errorMessage = "Error: ${e.message}"
                                                    } finally {
                                                        isLoadingQr = false
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(qrBoxSize)
                                        )
                                    }
                                }
                            }
                        } else {
                            // WebView PKCE / Sisu Tab
                            Column(modifier = Modifier.fillMaxSize()) {
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
                                            Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(18.dp))
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
                                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = Color(0xFF4ADE80), modifier = Modifier.size(18.dp))
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
                                        
                                        if (capturedCode != null) {
                                            Button(
                                                onClick = {
                                                    coroutineScope.launch {
                                                        isSubmitting = true
                                                        try {
                                                            val session = authManager.completeSisuAuth(capturedCode!!, auth)
                                                            homeViewModel.clearCapturedAuthCode()
                                                            onSuccess(session)
                                                        } catch (e: Exception) {
                                                            errorMessage = "Error en canje: ${e.message}"
                                                        } finally {
                                                            isSubmitting = false
                                                        }
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10)),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                                modifier = Modifier.height(32.dp)
                                            ) {
                                                Text("Confirmar Inicio", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }

                                if (webProgress in 1..99) {
                                    LinearProgressIndicator(
                                        progress = { webProgress / 100f },
                                        modifier = Modifier.fillMaxWidth().height(2.dp),
                                        color = Color(0xFF107C10),
                                        trackColor = Color(0xFF1C2024)
                                    )
                                }

                                if (showManualCodeEntry) {
                                    Surface(
                                        color = Color(0xFF1A1F26),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth().padding(12.dp).border(1.dp, Color(0xFF282D33), RoundedCornerShape(8.dp))
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text(text = strings.manualCodePrompt, style = MaterialTheme.typography.labelMedium, color = Color.LightGray)
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                OutlinedTextField(
                                                    value = manualCodeInput,
                                                    onValueChange = { manualCodeInput = it },
                                                    placeholder = { Text("ms-xal-...", style = MaterialTheme.typography.bodySmall, color = Color.Gray) },
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
                                                                    val session = authManager.completeSisuAuth(code, auth)
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
                                                    val cookieManager = android.webkit.CookieManager.getInstance()
                                                    cookieManager.setAcceptCookie(true)
                                                    cookieManager.setAcceptThirdPartyCookies(this, true)

                                                    com.aistudio.xcloudgodmode.ai.engine.GodModeEngineUtils.applyWebViewFlags(
                                                        webView = this,
                                                        enableGpu = settings.enableGpuRasterization,
                                                        enableDark = settings.autoDarkModeWebContents,
                                                        enforceTls13 = settings.enforceTls13Strict
                                                    )

                                                    this.settings.apply {
                                                        javaScriptEnabled = true
                                                        domStorageEnabled = true
                                                        databaseEnabled = true
                                                        loadsImagesAutomatically = true
                                                        useWideViewPort = true
                                                        loadWithOverviewMode = true
                                                        javaScriptCanOpenWindowsAutomatically = true
                                                        cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                                                        val defaultUA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                                                        userAgentString = defaultUA.replace("; wv", "")
                                                    }

                                                    webChromeClient = object : android.webkit.WebChromeClient() {
                                                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                                            webProgress = newProgress
                                                        }
                                                    }

                                                    addJavascriptInterface(object {
                                                        @android.webkit.JavascriptInterface
                                                        fun onTokenIntercepted(tokenData: String) {
                                                            Log.d("DualAuthModal", "Token Intercepted via JS: $tokenData")
                                                            try {
                                                                val json = JSONObject(tokenData)
                                                                val gsToken = json.optString("gsToken")
                                                                val region = json.optString("region")
                                                                if (gsToken.isNotBlank()) {
                                                                    coroutineScope.launch {
                                                                        // If we intercept a valid streaming token, we can potentially bypass the rest of the flow
                                                                        // for a "Fast Login" experience.
                                                                        // For now, we'll just log it and maybe show a "Token captured" toast.
                                                                        withContext(Dispatchers.Main) {
                                                                            Toast.makeText(context, "Streaming Token Interceptado ✅", Toast.LENGTH_SHORT).show()
                                                                        }
                                                                    }
                                                                }
                                                            } catch (_: Exception) {}
                                                        }
                                                    }, "AndroidTokenBridge")

                                                    webViewClient = object : WebViewClient() {
                                                        override fun onPageFinished(view: WebView?, url: String?) {
                                                            super.onPageFinished(view, url)
                                                            if (url?.contains("xbox.com") == true && url.contains("play")) {
                                                                val js = """
                                                                    (function() {
                                                                        try {
                                                                            for (let i = 0; i < localStorage.length; i++) {
                                                                                let key = localStorage.key(i);
                                                                                if (key && key.includes('xCloudToken')) {
                                                                                    AndroidTokenBridge.onTokenIntercepted(localStorage.getItem(key));
                                                                                    return;
                                                                                }
                                                                            }
                                                                        } catch(e) {}
                                                                    })();
                                                                """.trimIndent()
                                                                view?.evaluateJavascript(js, null)
                                                            }
                                                        }
                                                        private fun handleUrlIntercept(url: String?): Boolean {
                                                            if (url == null) return false
                                                            if (url.startsWith("ms-xal-") || url.contains("code=") || url.contains("approval_code=")) {
                                                                val code = authManager.extractAuthCode(url)
                                                                if (code.isNotEmpty() && !code.startsWith("http")) {
                                                                    coroutineScope.launch {
                                                                        isSubmitting = true
                                                                        try {
                                                                            val session = authManager.completeSisuAuth(code, auth)
                                                                            onSuccess(session)
                                                                        } catch (e: Exception) {
                                                                            errorMessage = "Error en canje: ${e.message}"
                                                                        } finally {
                                                                            isSubmitting = false
                                                                        }
                                                                    }
                                                                    return true
                                                                }
                                                            }
                                                            return false
                                                        }

                                                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = handleUrlIntercept(request?.url?.toString())
                                                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { handleUrlIntercept(url) }
                                                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                                                            if (request?.isForMainFrame == true) {
                                                                val desc = error?.description?.toString() ?: ""
                                                                if (!desc.contains("ERR_UNKNOWN_URL_SCHEME")) {
                                                                    errorMessage = "Error de conexión ($desc). Abre en navegador externo."
                                                                }
                                                            }
                                                        }
                                                        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                                                            view?.reload()
                                                            return true
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
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(errorMessage!!, color = Color(0xFFFECACA), style = MaterialTheme.typography.bodySmall)
                                    if (selectedTab == 1 && (errorMessage!!.contains("403") || errorMessage!!.contains("conexión"))) {
                                        TextButton(
                                            onClick = {
                                                try {
                                                    val auth = pendingAuth
                                                    if (auth != null) {
                                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(auth.authUrl))
                                                        context.startActivity(intent)
                                                    }
                                                } catch (_: Exception) {}
                                            },
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text(strings.fix403Error, color = Color(0xFF4ADE80), style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
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
                                Text("Finalizando apretón de manos God Mode...", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}
