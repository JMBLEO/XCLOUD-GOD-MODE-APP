package com.xcloud.godmode.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
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
import com.xcloud.godmode.network.XboxFullAuthManager
import com.xcloud.godmode.network.XboxSessionProfile
import androidx.compose.runtime.*

private val XboxGreen = Color(0xFF107C10)

@Composable
fun UserAccountHeaderButton(
    userSession: XboxFullAuthManager.XboxReadySession?,
    savedAccounts: List<XboxSessionProfile>,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onSwitchAccount: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    if (userSession != null) {
        Box {
            OutlinedButton(
                modifier = modifier,
                onClick = { if (savedAccounts.size > 1) showMenu = true else onLogoutClick() },
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
                    val matchingProfile = savedAccounts.find { it.uhs == userSession.uhs }
                    val badge = matchingProfile?.tierBadge?.ifBlank { "Game Pass" } ?: "Xbox Live"
                    Text(
                        text = badge,
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
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Text(
                "Mis cuentas",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.labelSmall,
                color = XboxGreen
            )
            savedAccounts.forEach { profile ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (profile.gamerpic.isNotBlank()) {
                                AsyncImage(
                                    model = profile.gamerpic,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp).clip(CircleShape)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(profile.gamertag, color = Color.White)
                            if (profile.uhs == userSession.uhs) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(Icons.Default.Check, contentDescription = null, tint = XboxGreen, modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    onClick = {
                        showMenu = false
                        onSwitchAccount(profile.uhs)
                    }
                )
            }
            HorizontalDivider(color = Color.DarkGray)
            DropdownMenuItem(
                text = { Text("Agregar cuenta", color = XboxGreen) },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, tint = XboxGreen) },
                onClick = {
                    showMenu = false
                    onLoginClick()
                }
            )
            DropdownMenuItem(
                text = { Text("Cerrar sesión actual", color = Color.Red) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = Color.Red) },
                onClick = {
                    showMenu = false
                    onLogoutClick()
                }
            )
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
