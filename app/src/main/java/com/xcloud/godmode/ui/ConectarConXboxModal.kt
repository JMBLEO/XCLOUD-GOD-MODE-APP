package com.xcloud.godmode.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.xcloud.godmode.viewmodels.QrAndOneClickUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ConectarConXboxModal(
    state: QrAndOneClickUiState,
    onSelectOneClickTab: () -> Unit,
    onSelectQrTab: () -> Unit,
    onLaunchOneClickBrowser: (Context) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(1) } // Default to QR as it's more stable for TVs/Consoles

    LaunchedEffect(selectedTab) {
        if (selectedTab == 1) {
            onSelectQrTab()
        } else {
            onSelectOneClickTab()
        }
    }

    LaunchedEffect(state) {
        if (state is QrAndOneClickUiState.Authenticated) {
            onDismiss()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF0A0D10)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Conectar con Xbox",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Text("✕", color = Color.Gray, fontSize = 18.sp)
                    }
                }

                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = Color(0xFF107C10),
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = Color(0xFF107C10)
                        )
                    }
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Text(
                                "Inicio Rápido",
                                color = if (selectedTab == 0) Color(0xFF2ECC71) else Color.Gray
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Text(
                                "Vincular QR / TV",
                                color = if (selectedTab == 1) Color(0xFF2ECC71) else Color.Gray
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                if (selectedTab == 1) {
                    QrFlowContent(state)
                } else {
                    OneClickFlowContent(state, onLaunchOneClickBrowser)
                }

                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "God Mode utiliza autenticación oficial nativa de Microsoft. No almacenamos tus contraseñas.",
                    color = Color.DarkGray,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
}

@Composable
fun QrFlowContent(state: QrAndOneClickUiState) {
    val context = LocalContext.current
    
    when (state) {
        is QrAndOneClickUiState.MsaQrActive -> {
            Text(
                text = "1. Escanea el QR con tu móvil o abre:\n${state.verificationUri}",
                color = Color(0xFFD0D7DE),
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(14.dp))

            QrCodeCard(qrUrl = state.verificationUri)

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "2. Introduce este código:",
                color = Color.Gray,
                fontSize = 12.sp
            )

            Surface(
                color = Color(0xFF161B22),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Text(
                    text = state.userCode,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = Color(0xFF2ECC71),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 4.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Xbox Code", state.userCode))
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(state.verificationUri))
                    context.startActivity(intent)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Copiar código y abrir en este dispositivo", color = Color.White, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Esperando confirmación automática... (${state.remainingSeconds}s)",
                color = Color.Gray,
                fontSize = 11.sp
            )
        }

        is QrAndOneClickUiState.LoadingTicket,
        is QrAndOneClickUiState.ExchangingSessionTokens -> {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 40.dp)
            ) {
                CircularProgressIndicator(color = Color(0xFF2ECC71))
                Spacer(modifier = Modifier.height(16.dp))
                Text("Sincronizando con Xbox Live...", color = Color.White, fontSize = 14.sp)
            }
        }

        is QrAndOneClickUiState.Error -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = state.message,
                    color = Color(0xFFFF6B6B),
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp
                )
                // Retry is handled by the tab change or closing/reopening
            }
        }

        else -> {
            Box(Modifier.height(200.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF2ECC71))
            }
        }
    }
}

@Composable
fun OneClickFlowContent(state: QrAndOneClickUiState, onLaunch: (Context) -> Unit) {
    val context = LocalContext.current
    
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Conecta tu cuenta de Xbox con un solo toque usando la autenticación nativa preautorizada (SISU).",
            color = Color(0xFFD0D7DE),
            textAlign = TextAlign.Center,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(32.dp))

        when (state) {
            is QrAndOneClickUiState.WaitingBrowserCallback -> {
                CircularProgressIndicator(color = Color(0xFF107C10))
                Spacer(modifier = Modifier.height(16.dp))
                Text("Esperando al navegador...", color = Color.Gray)
            }
            is QrAndOneClickUiState.Error -> {
                Text(state.message, color = Color.Red, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { onLaunch(context) }) {
                    Text("Reintentar")
                }
            }
            else -> {
                Button(
                    onClick = { onLaunch(context) },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Iniciar Sesión con Xbox (1 Clic)",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun QrCodeCard(qrUrl: String) {
    var qrBitmap by remember(qrUrl) { mutableStateOf<Bitmap?>(null) }
    
    LaunchedEffect(qrUrl) {
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

    Surface(
        color = Color.White,
        shape = RoundedCornerShape(16.dp),
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
}
