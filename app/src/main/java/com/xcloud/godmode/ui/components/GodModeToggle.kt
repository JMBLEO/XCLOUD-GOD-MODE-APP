package com.xcloud.godmode.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * GodModeToggle: Enhanced toggle button for improved state visibility and tactile feedback.
 */
@Composable
fun GodModeToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = Color(0xFF107C10)
) {
    val backgroundColor by animateColorAsState(
        if (checked) accentColor.copy(alpha = 0.2f) else Color(0xFF1E2328),
        label = "bg_color"
    )
    val borderColor by animateColorAsState(
        if (checked) accentColor else Color(0xFF2D3238),
        label = "border_color"
    )
    val textColor = if (checked) Color.White else Color.LightGray

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal
            ),
            color = textColor
        )
        
        Surface(
            modifier = Modifier.size(24.dp),
            shape = RoundedCornerShape(6.dp),
            color = if (checked) accentColor else Color.Transparent,
            border = androidx.compose.foundation.BorderStroke(2.dp, if (checked) accentColor else Color.Gray)
        ) {
            if (checked) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
            }
        }
    }
}
