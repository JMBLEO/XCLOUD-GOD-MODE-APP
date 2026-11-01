package com.xcloud.godmode.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * QrAuthDisplay: Native QR Code renderer for Xbox Smart TV login.
 * Uses ZXing to generate the QR bitmap locally.
 */
@Composable
fun QrAuthDisplay(
    authUrl: String, 
    verificationCode: String,
    onCheckStatusClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(authUrl) {
        if (authUrl.isNotBlank()) {
            try {
                val writer = QRCodeWriter()
                val bitMatrix = writer.encode(authUrl, BarcodeFormat.QR_CODE, 512, 512)
                val width = bitMatrix.width
                val height = bitMatrix.height
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                for (x in 0 until width) {
                    for (y in 0 until height) {
                        bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                    }
                }
                qrBitmap = bmp
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.padding(16.dp)
    ) {
        Text(
            "Escanea para iniciar sesión", 
            style = MaterialTheme.typography.titleMedium,
            color = androidx.compose.ui.graphics.Color.White
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Surface(
            color = androidx.compose.ui.graphics.Color.White,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.size(220.dp).padding(4.dp)
        ) {
            qrBitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Login QR",
                    modifier = Modifier.fillMaxSize().padding(8.dp)
                )
            } ?: Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.Black)
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = verificationCode, 
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.Black,
                letterSpacing = 4.sp
            ),
            color = androidx.compose.ui.graphics.Color(0xFF4ADE80)
        )
        
        Text(
            "O visita microsoft.com/link", 
            style = MaterialTheme.typography.labelMedium,
            color = androidx.compose.ui.graphics.Color.Gray
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = onCheckStatusClick,
            colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF107C10)),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("confirm_qr_activation_button")
        ) {
            Icon(androidx.compose.material.icons.Icons.Default.CheckCircle, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Confirmar Activación", fontWeight = FontWeight.Bold)
        }
    }
}
