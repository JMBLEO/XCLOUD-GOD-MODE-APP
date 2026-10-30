package com.aistudio.xcloudgodmode.ai.ui.components

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aistudio.xcloudgodmode.ai.models.SettingsModel
import com.aistudio.xcloudgodmode.ai.services.TouchButtonConfig
import com.aistudio.xcloudgodmode.ai.services.TouchLayoutProfile
import com.aistudio.xcloudgodmode.ai.services.TouchLayoutStorage
import com.aistudio.xcloudgodmode.ai.ui.theme.AppStrings
import kotlin.math.roundToInt

@Composable
fun TouchControllerEditor(
    titleId: String,
    gameTitle: String = "Xbox Cloud Game",
    settings: SettingsModel = SettingsModel(),
    onDismiss: () -> Unit,
    onLayoutSaved: ((TouchLayoutProfile) -> Unit)? = null
) {
    val context = LocalContext.current
    val strings = AppStrings.get(settings.appLanguage)
    val storage = remember { TouchLayoutStorage(context) }

    var profile by remember(titleId) {
        mutableStateOf(storage.getLayout(titleId, gameTitle))
    }
    var buttons by remember(profile) {
        mutableStateOf(profile.buttons)
    }
    var selectedButtonId by remember { mutableStateOf<String?>("stick_left") }
    var snapToGrid by remember { mutableStateOf(profile.snapToGrid) }
    var isTestMode by remember { mutableStateOf(false) }
    var globalOpacity by remember { mutableFloatStateOf(profile.globalOpacity) }
    var hapticsEnabled by remember { mutableStateOf(profile.hapticsEnabled) }

    val vibrator = remember {
        context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator
    }

    val triggerHaptic: () -> Unit = {
        if (hapticsEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(25)
            }
        }
    }

    val selectedButton = buttons.find { it.id == selectedButtonId }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("touch_controller_editor_dialog"),
            color = Color(0xFF090B0E)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val screenWidth = maxWidth
                val screenHeight = maxHeight

                // 1. Grid Background (active when in edit mode)
                if (!isTestMode) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokeColor = Color.White.copy(alpha = if (snapToGrid) 0.12f else 0.05f)
                        val gridCountX = 20
                        val gridCountY = 12

                        for (i in 1 until gridCountX) {
                            val x = (size.width / gridCountX) * i
                            drawLine(
                                color = strokeColor,
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = if (snapToGrid && i % 4 == 0) 1.5f else 0.8f
                            )
                        }
                        for (j in 1 until gridCountY) {
                            val y = (size.height / gridCountY) * j
                            drawLine(
                                color = strokeColor,
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = if (snapToGrid && j % 3 == 0) 1.5f else 0.8f
                            )
                        }
                    }
                }

                // 2. Draggable Virtual Buttons Canvas
                buttons.forEach { button ->
                    val isSelected = button.id == selectedButtonId && !isTestMode
                    var isPressedInTest by remember { mutableStateOf(false) }

                    // Base dimensions in dp
                    val baseDimension = when (button.id) {
                        "stick_left", "stick_right" -> 90.dp
                        "dpad" -> 110.dp
                        "btn_lt", "btn_rt", "btn_lb", "btn_rb" -> 64.dp
                        "btn_nexus" -> 48.dp
                        "btn_view", "btn_menu" -> 42.dp
                        else -> 54.dp
                    }
                    val scaledSize = baseDimension * button.scale
                    val buttonColor = parseHexColor(button.colorHex)
                    val effectiveOpacity = (button.opacity * globalOpacity).coerceIn(0.15f, 1.0f)

                    Box(
                        modifier = Modifier
                            .offset {
                                val xPx = (constraints.maxWidth * button.xPercent) - (scaledSize.toPx() / 2)
                                val yPx = (constraints.maxHeight * button.yPercent) - (scaledSize.toPx() / 2)
                                IntOffset(xPx.roundToInt(), yPx.roundToInt())
                            }
                            .size(scaledSize)
                            .then(
                                if (isSelected) {
                                    Modifier.border(2.dp, Color(0xFF107C10), if (button.isCircle) CircleShape else RoundedCornerShape(12.dp))
                                } else Modifier
                            )
                            .pointerInput(button.id, isTestMode, snapToGrid) {
                                if (isTestMode) {
                                    // Test mode: touch response without dragging
                                    detectDragGestures(
                                        onDragStart = {
                                            isPressedInTest = true
                                            triggerHaptic()
                                        },
                                        onDragEnd = { isPressedInTest = false },
                                        onDragCancel = { isPressedInTest = false },
                                        onDrag = { _, _ -> }
                                    )
                                } else {
                                    // Editor mode: drag and position
                                    detectDragGestures(
                                        onDragStart = {
                                            selectedButtonId = button.id
                                            triggerHaptic()
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            val currentX = button.xPercent * constraints.maxWidth
                                            val currentY = button.yPercent * constraints.maxHeight
                                            var newXPx = currentX + dragAmount.x
                                            var newYPx = currentY + dragAmount.y

                                            var newXPercent = (newXPx / constraints.maxWidth).coerceIn(0.04f, 0.96f)
                                            var newYPercent = (newYPx / constraints.maxHeight).coerceIn(0.04f, 0.96f)

                                            if (snapToGrid) {
                                                newXPercent = (Math.round(newXPercent * 20f) / 20f).coerceIn(0.05f, 0.95f)
                                                newYPercent = (Math.round(newYPercent * 20f) / 20f).coerceIn(0.05f, 0.95f)
                                            }

                                            buttons = buttons.map { b ->
                                                if (b.id == button.id) b.copy(xPercent = newXPercent, yPercent = newYPercent) else b
                                            }
                                        }
                                    )
                                }
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                if (!isTestMode) {
                                    selectedButtonId = button.id
                                    triggerHaptic()
                                } else {
                                    triggerHaptic()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        RenderVirtualButtonGraphic(
                            button = button,
                            scaledSize = scaledSize,
                            buttonColor = buttonColor,
                            effectiveOpacity = effectiveOpacity,
                            isSelected = isSelected,
                            isPressed = isPressedInTest
                        )
                    }
                }

                // 3. Top Status Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.TouchApp, contentDescription = null, tint = Color(0xFF107C10), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = strings.touchEditorTitle,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }
                        Text(
                            text = "$gameTitle • TitleID: $titleId",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Test Mode Switch
                        FilterChip(
                            selected = isTestMode,
                            onClick = {
                                isTestMode = !isTestMode
                                triggerHaptic()
                            },
                            label = { Text(strings.touchEditorTestMode) },
                            leadingIcon = {
                                Icon(
                                    if (isTestMode) Icons.Default.SportsEsports else Icons.Default.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF107C10),
                                selectedLabelColor = Color.White,
                                containerColor = Color(0xFF1E242B),
                                labelColor = Color.LightGray
                            )
                        )

                        // Close Button
                        IconButton(
                            onClick = onDismiss,
                            colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF1E242B))
                        ) {
                            Icon(Icons.Default.Close, contentDescription = strings.touchEditorExit, tint = Color.White)
                        }
                    }
                }

                // 4. Bottom Controls Deck (when in edit mode)
                AnimatedVisibility(
                    visible = !isTestMode,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF12161B).copy(alpha = 0.96f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF272F38)),
                        tonalElevation = 8.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Selected Button Title Bar & Info
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = CircleShape,
                                        color = if (selectedButton != null) parseHexColor(selectedButton.colorHex) else Color(0xFF107C10),
                                        modifier = Modifier.size(14.dp)
                                    ) {}
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "${strings.touchEditorButtonSelected} ${selectedButton?.label ?: "Ninguno"}",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White
                                    )
                                    if (selectedButton != null) {
                                        Text(
                                            text = " (X: ${(selectedButton.xPercent * 100).toInt()}%, Y: ${(selectedButton.yPercent * 100).toInt()}%)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.Gray
                                        )
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    // Reset Single Button
                                    OutlinedButton(
                                        onClick = {
                                            selectedButton?.let { currentBtn ->
                                                val defaultBtn = storage.getDefaultXboxButtons().find { it.id == currentBtn.id }
                                                if (defaultBtn != null) {
                                                    buttons = buttons.map { if (it.id == currentBtn.id) defaultBtn else it }
                                                    triggerHaptic()
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(strings.touchEditorResetButton, fontSize = 11.sp)
                                    }

                                    // Reset All Buttons
                                    OutlinedButton(
                                        onClick = {
                                            buttons = storage.getDefaultXboxButtons()
                                            triggerHaptic()
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text(strings.touchEditorResetAll, fontSize = 11.sp)
                                    }
                                }
                            }

                            // Sliders: Scale and Opacity
                            if (selectedButton != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    // Scale Slider
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(strings.touchEditorScale, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                                            Text("${String.format("%.1f", selectedButton.scale)}x", color = Color(0xFF4ADE80), style = MaterialTheme.typography.labelSmall)
                                        }
                                        Slider(
                                            value = selectedButton.scale,
                                            onValueChange = { newScale ->
                                                buttons = buttons.map {
                                                    if (it.id == selectedButton.id) it.copy(scale = newScale) else it
                                                }
                                            },
                                            valueRange = 0.6f..2.2f,
                                            colors = SliderDefaults.colors(thumbColor = Color(0xFF107C10), activeTrackColor = Color(0xFF107C10))
                                        )
                                    }

                                    // Opacity Slider
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(strings.touchEditorOpacity, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                                            Text("${(selectedButton.opacity * 100).toInt()}%", color = Color(0xFF4ADE80), style = MaterialTheme.typography.labelSmall)
                                        }
                                        Slider(
                                            value = selectedButton.opacity,
                                            onValueChange = { newOpacity ->
                                                buttons = buttons.map {
                                                    if (it.id == selectedButton.id) it.copy(opacity = newOpacity) else it
                                                }
                                            },
                                            valueRange = 0.2f..1.0f,
                                            colors = SliderDefaults.colors(thumbColor = Color(0xFF107C10), activeTrackColor = Color(0xFF107C10))
                                        )
                                    }
                                }
                            }

                            // Quick Switches & Save Action
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Snap-to-Grid Checkbox
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = snapToGrid,
                                            onCheckedChange = { snapToGrid = it },
                                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF107C10))
                                        )
                                        Text(strings.touchEditorSnapGrid, color = Color.LightGray, fontSize = 12.sp)
                                    }

                                    // Haptic Feedback Checkbox
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = hapticsEnabled,
                                            onCheckedChange = { hapticsEnabled = it },
                                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF107C10))
                                        )
                                        Text(strings.touchEditorHaptics, color = Color.LightGray, fontSize = 12.sp)
                                    }
                                }

                                // Save Profile Button
                                Button(
                                    onClick = {
                                        val updatedProfile = profile.copy(
                                            buttons = buttons,
                                            snapToGrid = snapToGrid,
                                            hapticsEnabled = hapticsEnabled,
                                            globalOpacity = globalOpacity
                                        )
                                        storage.saveLayout(updatedProfile)
                                        profile = updatedProfile
                                        onLayoutSaved?.invoke(updatedProfile)
                                        triggerHaptic()
                                        Toast.makeText(context, strings.touchEditorSavedSuccess, Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF107C10)),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.testTag("save_touch_profile_button")
                                ) {
                                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(strings.touchEditorSaveProfile, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders the realistic visual styling of Xbox virtual buttons.
 */
@Composable
private fun RenderVirtualButtonGraphic(
    button: TouchButtonConfig,
    scaledSize: androidx.compose.ui.unit.Dp,
    buttonColor: Color,
    effectiveOpacity: Float,
    isSelected: Boolean,
    isPressed: Boolean
) {
    val scaleFactor = if (isPressed) 0.90f else 1.0f
    val baseAlpha = if (isPressed) 0.95f else effectiveOpacity

    when (button.id) {
        "stick_left", "stick_right" -> {
            // Analog Stick: Outer boundary ring + inner thumb stick
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f * baseAlpha))
                    .border(2.dp, buttonColor.copy(alpha = 0.6f * baseAlpha), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                // Stick center thumbpad
                Box(
                    modifier = Modifier
                        .size(scaledSize * 0.52f * scaleFactor)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    buttonColor.copy(alpha = 0.85f * baseAlpha),
                                    Color(0xFF1E293B).copy(alpha = 0.9f * baseAlpha)
                                )
                            )
                        )
                        .border(1.5.dp, Color.White.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = button.label,
                        fontWeight = FontWeight.Black,
                        fontSize = (13 * button.scale).sp,
                        color = Color.White.copy(alpha = baseAlpha)
                    )
                }
            }
        }
        "dpad" -> {
            // D-Pad Cross
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.4f * baseAlpha))
                    .border(1.5.dp, buttonColor.copy(alpha = 0.6f * baseAlpha), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                // Cross arms
                Box(modifier = Modifier.width(scaledSize * 0.35f).fillMaxHeight(0.92f).background(Color(0xFF1E293B).copy(alpha = 0.85f * baseAlpha), RoundedCornerShape(6.dp)))
                Box(modifier = Modifier.height(scaledSize * 0.35f).fillMaxWidth(0.92f).background(Color(0xFF1E293B).copy(alpha = 0.85f * baseAlpha), RoundedCornerShape(6.dp)))
                // Center hub
                Box(modifier = Modifier.size(scaledSize * 0.32f).background(buttonColor.copy(alpha = 0.7f * baseAlpha), CircleShape), contentAlignment = Alignment.Center) {
                    Text("✚", color = Color.White, fontSize = (14 * button.scale).sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        "btn_lt", "btn_rt", "btn_lb", "btn_rb" -> {
            // Bumpers & Triggers Pill Shape
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF334155).copy(alpha = 0.9f * baseAlpha),
                                Color(0xFF0F172A).copy(alpha = 0.9f * baseAlpha)
                            )
                        )
                    )
                    .border(1.5.dp, buttonColor.copy(alpha = 0.7f * baseAlpha), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = button.label,
                    fontWeight = FontWeight.Bold,
                    fontSize = (14 * button.scale).sp,
                    color = Color.White.copy(alpha = baseAlpha)
                )
            }
        }
        "btn_nexus" -> {
            // Xbox Guide Nexus Orb
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                Color.White.copy(alpha = 0.95f),
                                Color(0xFF107C10).copy(alpha = 0.9f * baseAlpha),
                                Color(0xFF064E3B).copy(alpha = 0.9f * baseAlpha)
                            )
                        )
                    )
                    .border(2.dp, Color.White.copy(alpha = 0.7f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "X",
                    fontWeight = FontWeight.Black,
                    fontSize = (18 * button.scale).sp,
                    color = Color.Black
                )
            }
        }
        "btn_view", "btn_menu" -> {
            // View / Menu Small Pills
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E293B).copy(alpha = 0.85f * baseAlpha))
                    .border(1.dp, Color(0xFF475569).copy(alpha = 0.7f * baseAlpha), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (button.id == "btn_view") "⧉" else "☰",
                    fontWeight = FontWeight.Bold,
                    fontSize = (14 * button.scale).sp,
                    color = Color.White.copy(alpha = baseAlpha)
                )
            }
        }
        else -> {
            // Standard A, B, X, Y Action Buttons with classic Xbox Colors
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                buttonColor.copy(alpha = 0.95f * baseAlpha),
                                buttonColor.copy(alpha = 0.70f * baseAlpha),
                                Color.Black.copy(alpha = 0.85f * baseAlpha)
                            )
                        )
                    )
                    .border(2.dp, Color.White.copy(alpha = 0.5f * baseAlpha), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = button.label,
                    fontWeight = FontWeight.Black,
                    fontSize = (19 * button.scale).sp,
                    color = Color.White
                )
            }
        }
    }
}

private fun parseHexColor(hex: String): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (e: Exception) {
        Color(0xFF107C10)
    }
}
