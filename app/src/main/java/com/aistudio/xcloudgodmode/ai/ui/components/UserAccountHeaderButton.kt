package com.aistudio.xcloudgodmode.ai.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val XboxGreen = Color(
    red = 16,
    green = 124,
    blue = 16
)

@Composable
fun UserAccountHeaderButton(
    isConnected: Boolean,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isConnected) {
        OutlinedButton(
            modifier = modifier,
            onClick = onLogoutClick,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(
                width = 1.dp,
                color = XboxGreen.copy(
                    alpha = 0.8f
                )
            ),
            colors = ButtonDefaults
                .outlinedButtonColors(
                    contentColor = XboxGreen
                )
        ) {
            Row(
                verticalAlignment =
                    Alignment.CenterVertically,
                horizontalArrangement =
                    Arrangement.Center
            ) {
                Icon(
                    imageVector =
                        Icons.Default.SportsEsports,
                    contentDescription = null,
                    tint = XboxGreen
                )

                Spacer(
                    modifier = Modifier.width(8.dp)
                )

                Text(
                    text = "Conectado (Xbox Live)"
                )

                Spacer(
                    modifier = Modifier.width(10.dp)
                )

                Icon(
                    imageVector =
                        Icons.AutoMirrored.Filled
                            .ExitToApp,
                    contentDescription =
                        "Cerrar sesión"
                )
            }
        }
    } else {
        Button(
            modifier = modifier,
            onClick = onLoginClick,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults
                .buttonColors(
                    containerColor =
                        MaterialTheme
                            .colorScheme
                            .surfaceVariant,
                    contentColor =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null
            )

            Spacer(
                modifier = Modifier.width(8.dp)
            )

            Text(
                text = "Iniciar sesión"
            )
        }
    }
}
