package com.xcloud.godmode.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xcloud.godmode.models.ApplicationRestartAction
import com.xcloud.godmode.models.BypassConfiguration
import com.xcloud.godmode.models.CodecPreference
import com.xcloud.godmode.models.EngineMenuEntry
import com.xcloud.godmode.models.EngineReadiness
import com.xcloud.godmode.models.RootConfigurationState
import com.xcloud.godmode.models.StreamConnectionMetrics
import com.xcloud.godmode.models.StreamServerConfiguration
import com.xcloud.godmode.models.VerifiedStreamQuality
import com.xcloud.godmode.models.countryFlagEmoji

@Composable
fun FloatingEngineMenu(
    entries: List<EngineMenuEntry>,
    configurationState: RootConfigurationState?,
    connectionMetrics: StreamConnectionMetrics?,
    verifiedQuality: VerifiedStreamQuality?,
    restartDialogVisible: Boolean,
    message: String?,
    onSelected: (EngineMenuEntry) -> Unit,
    onEditBypassPressed: () -> Unit,
    onEditServerPressed: () -> Unit,
    onEditCodecPressed: () -> Unit,
    onRestartAction: (ApplicationRestartAction) -> Unit,
    onDismiss: () -> Unit
) {
    if (restartDialogVisible) {
        RestartConfirmationDialog(
            onRestartAction = onRestartAction
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        },
        title = {
            Text("God Mode — Panel de Transmisión Nativa")
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                configurationState?.let { config ->
                    item {
                        BypassSummaryCard(
                            applied = config.applied.bypass,
                            pending = config.pending?.bypass,
                            onEdit = onEditBypassPressed
                        )
                    }
                    item {
                        ServerSummaryCard(
                            applied = config.applied.server,
                            pending = config.pending?.server,
                            onEdit = onEditServerPressed
                        )
                    }
                    item {
                        CodecPreferenceCard(
                            applied = config.applied.codecPreference,
                            pending = config.pending?.codecPreference,
                            onEdit = onEditCodecPressed
                        )
                    }
                    if (config.restartRequired) {
                        item {
                            RestartWarningBanner()
                        }
                    }
                }

                connectionMetrics?.let { metrics ->
                    item {
                        MetricsSummaryCard(metrics = metrics)
                    }
                }

                item {
                    VerifiedQualityCard(verifiedQuality = verifiedQuality)
                }

                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }

                item {
                    Text(
                        text = "Motores nativos (MediaCodec / Surface)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                items(entries) { entry ->
                    EngineMenuCard(
                        entry = entry,
                        onClick = { onSelected(entry) }
                    )
                }

                message?.let {
                    item {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun BypassSummaryCard(
    applied: BypassConfiguration,
    pending: BypassConfiguration?,
    onEdit: () -> Unit
) {
    val flag = countryFlagEmoji(applied.countryCode)
    val appliedAddressText = applied.address?.let {
        "${it.protocolVersion.displayName}: ${it.address}"
    } ?: "IP aplicada: Ninguna"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = flag, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = applied.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (applied.enabled) "Bypass activado" else "Bypass desactivado",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (applied.enabled) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = appliedAddressText,
                    style = MaterialTheme.typography.bodySmall
                )
                pending?.let { p ->
                    Text(
                        text = "Pendiente tras reinicio: ${p.displayName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFF57C00)
                    )
                }
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier.semantics {
                    role = Role.Button
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Editar bypass e IP"
                )
            }
        }
    }
}

@Composable
private fun ServerSummaryCard(
    applied: StreamServerConfiguration,
    pending: StreamServerConfiguration?,
    onEdit: () -> Unit
) {
    val flag = countryFlagEmoji(applied.countryCode)
    val hostPortText = if (applied.hostName != null && applied.port != null) {
        "${applied.hostName}:${applied.port}"
    } else {
        "Host: No disponible"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = flag, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = applied.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Estado: ${applied.status.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = hostPortText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                pending?.let { p ->
                    Text(
                        text = "Pendiente tras reinicio: ${p.displayName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFF57C00)
                    )
                }
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier.semantics {
                    role = Role.Button
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Editar servidor y región"
                )
            }
        }
    }
}

@Composable
private fun CodecPreferenceCard(
    applied: CodecPreference,
    pending: CodecPreference?,
    onEdit: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Códec: ${applied.displayName}",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = applied.description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                pending?.let { p ->
                    Text(
                        text = "Pendiente tras reinicio: ${p.displayName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFF57C00)
                    )
                }
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier.semantics {
                    role = Role.Button
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Editar preferencia de códec"
                )
            }
        }
    }
}

@Composable
private fun RestartWarningBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Advertencia de reinicio necesario",
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "RESTART_REQUIRED: Los cambios guardados requieren cerrar y reabrir la aplicación antes de iniciar el stream.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun MetricsSummaryCard(metrics: StreamConnectionMetrics) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = "Métricas de Conexión (Evidencia)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            val latencyText = metrics.latencyMs?.let { "${it.toInt()} ms (${metrics.latencyEvidence.displayName})" } ?: "No medida"
            val bandwidthText = metrics.downstreamMbps?.let { "${it.toInt()} Mbps (${metrics.bandwidthEvidence.displayName})" } ?: "No medida"
            Text(text = "Latencia: $latencyText", style = MaterialTheme.typography.bodySmall)
            Text(text = "Ancho de banda: $bandwidthText", style = MaterialTheme.typography.bodySmall)
            Text(text = "Nota: ${metrics.measurementNote}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun VerifiedQualityCard(verifiedQuality: VerifiedStreamQuality?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = "Calidad comprobada",
                style = MaterialTheme.typography.labelLarge
            )
            val text = if (verifiedQuality?.verified == true) {
                "${verifiedQuality.width}x${verifiedQuality.height} @ ${verifiedQuality.frameRate.toInt()} FPS (${verifiedQuality.mimeType}, ${verifiedQuality.decoderName})"
            } else {
                "Calidad comprobada: No disponible"
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (verifiedQuality?.verified == true) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EngineMenuCard(
    entry: EngineMenuEntry,
    onClick: () -> Unit
) {
    val statusText = when (entry.readiness) {
        EngineReadiness.IMPLEMENTED -> "Disponible"
        EngineReadiness.EXPERIMENTAL -> "Experimental"
        EngineReadiness.UNAVAILABLE -> "No implementado"
    }

    val statusColor = when (entry.readiness) {
        EngineReadiness.IMPLEMENTED -> Color(0xFF2E7D32)
        EngineReadiness.EXPERIMENTAL -> Color(0xFFF57C00)
        EngineReadiness.UNAVAILABLE -> Color(0xFF757575)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Button
                stateDescription = statusText
                if (!entry.selectable) {
                    disabled()
                }
            }
            .clickable(
                enabled = entry.selectable,
                role = Role.Button,
                onClick = onClick
            ),
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = statusText,
                    color = statusColor,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
}

@Composable
private fun RestartConfirmationDialog(
    onRestartAction: (ApplicationRestartAction) -> Unit
) {
    AlertDialog(
        onDismissRequest = { onRestartAction(ApplicationRestartAction.Deferred) },
        title = { Text("Reinicio necesario") },
        text = {
            Text("Los cambios de bypass, servidor o códec están guardados como pendientes. Cierre y vuelva a abrir la aplicación para aplicarlos desde la raíz.")
        },
        confirmButton = {
            TextButton(onClick = { onRestartAction(ApplicationRestartAction.Requested) }) {
                Text("Cerrar y reiniciar")
            }
        },
        dismissButton = {
            TextButton(onClick = { onRestartAction(ApplicationRestartAction.Deferred) }) {
                Text("Reiniciar más tarde")
            }
        }
    )
}
