package com.xcloud.godmode.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * SessionManagerCard: Displays active Xbox session details and status.
 * Integrated with Biometric vault status and secure invalidation.
 */
@Composable
fun SessionManagerCard(
    gamertag: String?,
    connectionStatus: String, // 'Authenticated', 'Expired', 'Disconnected'
    onInvalidateSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isSessionActive = gamertag != null && connectionStatus != "Disconnected"
    
    val statusColor = when (connectionStatus) {
        "Authenticated" -> Color(0xFF4ADE80)
        "Expired" -> Color(0xFFFB923C)
        else -> Color.Gray
    }

    val glowColor = if (isSessionActive) statusColor.copy(alpha = 0.15f) else Color.Transparent

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .border(
                BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                RoundedCornerShape(24.dp)
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF14171A).copy(alpha = 0.9f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Avatar / Icon with dynamic glow
                Surface(
                    modifier = Modifier
                        .size(56.dp)
                        .drawWithContent {
                            drawContent()
                        },
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.3f),
                    border = BorderStroke(2.dp, statusColor.copy(alpha = 0.5f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isSessionActive) Icons.Default.Person else Icons.Default.PersonOff,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(20.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = gamertag ?: "Sin Sesión Activa",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.5).sp
                        ),
                        color = Color.White
                    )
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = connectionStatus.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            ),
                            color = statusColor
                        )
                    }
                }

                if (isSessionActive) {
                    Surface(
                        color = Color(0xFF107C10).copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFF107C10).copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Fingerprint,
                                contentDescription = null,
                                tint = Color(0xFF4ADE80),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "VAULT",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF4ADE80)
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = isSessionActive,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(24.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = onInvalidateSession,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("invalidate_session_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFEF4444).copy(alpha = 0.12f),
                            contentColor = Color(0xFFEF4444)
                        ),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.3f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Logout,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Invalidate Session",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black)
                        )
                    }
                }
            }
        }
    }
}
