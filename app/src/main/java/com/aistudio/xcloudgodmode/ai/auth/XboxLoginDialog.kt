package com.aistudio.xcloudgodmode.ai.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Diálogo de autenticación oficial integrada para Xbox Live.
 * Utiliza XboxLoginWebView con fondo blanco, motor limpio y sin inyección de God Mode
 * para evitar colisiones con Microsoft OAuth y MSAL.js.
 */
@Composable
fun XboxLoginDialog(
    onDismiss: () -> Unit,
    onAuthenticated: () -> Unit = {},
    onLoginSuccess: (() -> Unit)? = null
) {
    val completionCallback = onLoginSuccess ?: onAuthenticated
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF101010)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Barra superior de autenticación
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1B1B1B))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = "Xbox Live",
                        tint = Color(0xFF107C10),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Iniciar Sesión en Xbox Live",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Portal oficial de Microsoft • Sesión segura y persistente",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = Color.White
                        )
                    }
                }

                if (errorMessage != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF8B0000))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = errorMessage ?: "",
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }
                }

                // Contenedor WebView con fondo blanco puro y render oficial de Xbox
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .background(Color.White)
                ) {
                    XboxLoginWebView(
                        onAuthenticated = {
                            completionCallback()
                        },
                        onAuthenticationError = { err ->
                            errorMessage = err
                        }
                    )
                }
            }
        }
    }
}
