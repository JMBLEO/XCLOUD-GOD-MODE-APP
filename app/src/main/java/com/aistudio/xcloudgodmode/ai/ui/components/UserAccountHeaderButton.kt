package com.aistudio.xcloudgodmode.ai.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aistudio.xcloudgodmode.ai.services.XboxFullAuthManager

private val XboxGreen = Color(0xFF107C10)

@Composable
fun UserAccountHeaderButton(
    userSession: XboxFullAuthManager.XboxReadySession?,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (userSession != null) {
        OutlinedButton(
            modifier = modifier,
            onClick = onLogoutClick,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, XboxGreen.copy(alpha = 0.8f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = XboxGreen),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (userSession.gamerpic.isNotBlank()) {
                    AsyncImage(
                        model = userSession.gamerpic,
                        contentDescription = "Gamerpic",
                        modifier = Modifier.size(28.dp).clip(CircleShape).border(1.5.dp, XboxGreen, CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.SportsEsports,
                        contentDescription = null,
                        tint = XboxGreen,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column {
                    Text(
                        text = userSession.gamertag,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Text(
                        text = "Xbox Live",
                        style = MaterialTheme.typography.labelSmall,
                        color = XboxGreen
                    )
                }

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                    contentDescription = "Cerrar sesión",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    } else {
        Button(
            modifier = modifier,
            onClick = onLoginClick,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(text = "Iniciar sesión")
        }
    }
}
