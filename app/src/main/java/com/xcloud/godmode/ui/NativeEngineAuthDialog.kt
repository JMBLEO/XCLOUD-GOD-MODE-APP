package com.xcloud.godmode.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.xcloud.godmode.viewmodels.EngineDialogState

/**
 * NativeEngineStatusDialog: Unlocked, deterministic status dialog.
 * Resolves popup lockups by cleanly dismissing on first touch and routing
 * directly to the interactive authentication flow when required.
 */
@Composable
fun NativeEngineStatusDialog(
    dialogState: EngineDialogState,
    onDismiss: () -> Unit,
    onLoginNow: () -> Unit
) {
    when (dialogState) {
        is EngineDialogState.Hidden -> Unit

        is EngineDialogState.AuthRequired -> {
            AlertDialog(
                onDismissRequest = { onDismiss() },
                title = {
                    Text(
                        text = dialogState.title,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                text = {
                    Text(
                        text = dialogState.message,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                dismissButton = {
                    TextButton(
                        onClick = { onDismiss() },
                        modifier = Modifier.testTag("auth_dialog_dismiss_button")
                    ) {
                        Text("Cerrar")
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDismiss()
                            onLoginNow()
                        },
                        modifier = Modifier.testTag("auth_dialog_login_button")
                    ) {
                        Text(
                            text = "Iniciar sesión",
                            color = Color(0xFF4CAF50)
                        )
                    }
                }
            )
        }

        is EngineDialogState.GenericError -> {
            AlertDialog(
                onDismissRequest = { onDismiss() },
                title = {
                    Text(
                        text = dialogState.title,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                text = {
                    Text(
                        text = dialogState.message,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { onDismiss() },
                        modifier = Modifier.testTag("engine_dialog_understood_button")
                    ) {
                        Text(
                            text = "Entendido",
                            color = Color(0xFF4CAF50)
                        )
                    }
                }
            )
        }
    }
}

/**
 * UserProfileTopBarChip: Always interactive profile indicator.
 * Displays online status and expands to a full profile management menu.
 */
@Composable
fun UserProfileTopBarChip(
    isLoggedIn: Boolean,
    gamertag: String?,
    showMenu: Boolean,
    onChipClick: () -> Unit,
    onDismissMenu: () -> Unit,
    onRefreshCredentials: () -> Unit,
    onForceReLogin: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .clickable(onClick = onChipClick)
                .testTag("user_profile_topbar_chip"),
            shape = RoundedCornerShape(50),
            color = Color(0xFF1E2228)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isLoggedIn && !gamertag.isNullOrBlank()) gamertag else "Iniciar sesión",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "●",
                    color = if (isLoggedIn) Color(0xFF4CAF50) else Color(0xFFE53935)
                )
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = onDismissMenu
        ) {
            DropdownMenuItem(
                text = { Text("Renovar credenciales de streaming") },
                onClick = {
                    onDismissMenu()
                    onRefreshCredentials()
                },
                modifier = Modifier.testTag("menu_refresh_credentials")
            )
            DropdownMenuItem(
                text = { Text("Cambiar cuenta / Re-autenticar") },
                onClick = {
                    onDismissMenu()
                    onForceReLogin()
                },
                modifier = Modifier.testTag("menu_relogin")
            )
            DropdownMenuItem(
                text = { Text("Cerrar sesión", color = Color(0xFFE53935)) },
                onClick = {
                    onDismissMenu()
                    onLogout()
                },
                modifier = Modifier.testTag("menu_logout")
            )
        }
    }
}
