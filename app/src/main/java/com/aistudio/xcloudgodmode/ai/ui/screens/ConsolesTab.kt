package com.aistudio.xcloudgodmode.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun ConsolesTab(
    consoles: List<JSONObject>,
    onLaunchConsole: (String) -> Unit
) {
    if (consoles.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Computer, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.Gray)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Sin Consolas", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
                Text("Conecta tu Xbox para usar Remote Play.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { onLaunchConsole("demo_console") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10))
                ) {
                    Text("Probar Demo (Remote Play)")
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    "Mis Consolas Xbox",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            items(consoles) { console ->
                ConsoleCard(console = console, onClick = { onLaunchConsole(console.optString("id")) })
            }
        }
    }
}

@Composable
private fun ConsoleCard(console: JSONObject, onClick: () -> Unit) {
    val name = console.optString("name", "Xbox Console")
    val id = console.optString("id")
    val type = console.optString("consoleType", "Xbox")
    val state = console.optString("powerState", "Unknown")
    
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1C2024),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF282D33)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.SettingsRemote,
                contentDescription = null,
                tint = if (state == "ConnectedStandby") Color(0xFF4ADE80) else Color.Gray,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, color = Color.White, fontWeight = FontWeight.Bold)
                Text("$type • $state", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            }
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Conectar")
            }
        }
    }
}
