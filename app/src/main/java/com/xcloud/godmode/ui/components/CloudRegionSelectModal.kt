package com.xcloud.godmode.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

data class CloudRegionItem(
    val id: String,
    val name: String,
    val flag: String,
    val ip: String,
    val isDefault: Boolean = false
)

val AVAILABLE_CLOUD_REGIONS = listOf(
    CloudRegionItem(id = "ChileCentral", name = "Chile Central (Baja Latencia Cono Sur)", flag = "🇨🇱", ip = "20.143.92.1", isDefault = true),
    CloudRegionItem(id = "BrazilSouth", name = "Brasil Sur (São Paulo Direct)", flag = "🇧🇷", ip = "200.221.2.45"),
    CloudRegionItem(id = "Mexico", name = "México (Latam Norte)", flag = "🇲🇽", ip = "187.188.12.34"),
    CloudRegionItem(id = "EastUS", name = "Estados Unidos Este (Virginia)", flag = "🇺🇸", ip = "40.76.0.1"),
    CloudRegionItem(id = "WestUS2", name = "Estados Unidos Oeste (Washington)", flag = "🇺🇸", ip = "20.185.0.1"),
    CloudRegionItem(id = "WestEurope", name = "Europa Oeste (Ámsterdam)", flag = "🇪🇺", ip = "51.144.0.1"),
    CloudRegionItem(id = "JapanEast", name = "Japón Este (Tokio)", flag = "🇯🇵", ip = "20.43.64.1"),
    CloudRegionItem(id = "KoreaCentral", name = "Corea Central (Seúl)", flag = "🇰🇷", ip = "20.194.0.1"),
    CloudRegionItem(id = "AustraliaEast", name = "Australia Este (Sídney)", flag = "🇦🇺", ip = "20.37.0.1")
)

@Composable
fun CloudRegionSelectModal(
    visible: Boolean,
    currentRegionId: String,
    onSelectRegion: (CloudRegionItem) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF0F1622),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            shadowElevation = 16.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp)
                .testTag("cloud_region_select_modal")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E676).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Public,
                            contentDescription = null,
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Servidor Xbox Cloud Gaming",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Text(
                            text = "Selecciona el datacenter más cercano a tu ubicación para mínima latencia.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.LightGray.copy(alpha = 0.70f)
                        )
                    }
                }

                Divider(color = Color.White.copy(alpha = 0.08f), thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))

                // Region List
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(AVAILABLE_CLOUD_REGIONS) { region ->
                        val isSelected = region.id.equals(currentRegionId, ignoreCase = true)

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) Color(0xFF00E676).copy(alpha = 0.12f) else Color(0xFF161F2E).copy(alpha = 0.60f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) Color(0xFF00E676).copy(alpha = 0.60f) else Color.White.copy(alpha = 0.05f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectRegion(region)
                                    onDismiss()
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = region.flag,
                                        fontSize = 22.sp,
                                        modifier = Modifier.padding(end = 12.dp)
                                    )
                                    Column {
                                        Text(
                                            text = region.name,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            ),
                                            color = if (isSelected) Color(0xFF00E676) else Color.White
                                        )
                                        Text(
                                            text = "${region.id} • IP: ${region.ip}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.Gray
                                        )
                                    }
                                }

                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Seleccionado",
                                        tint = Color(0xFF00E676),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.10f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cerrar", color = Color.White)
                }
            }
        }
    }
}
