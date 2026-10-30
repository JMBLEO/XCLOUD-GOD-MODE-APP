package com.aistudio.xcloudgodmode.ai.ui.theme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.xcloudgodmode.ai.models.DeviceType
import com.aistudio.xcloudgodmode.ai.models.SettingsModel

/**
 * Calculated responsive scaling factors for typography, icons, cover art, and QR code.
 */
data class ScreenScaleFactors(
    val fontScale: Float = 1.0f,
    val iconScale: Float = 1.0f,
    val posterScale: Float = 1.0f,
    val qrScale: Float = 1.0f,
    val deviceMode: DeviceType = DeviceType.PHONE,
    val isLandscape: Boolean = false,
    val maxWidthDp: Dp = 360.dp,
    val maxHeightDp: Dp = 640.dp
) {
    fun scaledSp(baseSp: Float): TextUnit = (baseSp * fontScale).sp
    fun scaledDp(baseDp: Float): Dp = (baseDp * iconScale).dp
    fun posterWidth(baseWidth: Float = 140f): Dp = (baseWidth * posterScale).dp
    fun posterHeight(baseHeight: Float = 210f): Dp = (baseHeight * posterScale).dp
    fun qrSize(baseSize: Float = 180f): Dp = (baseSize * qrScale).dp
}

val LocalScreenScale = compositionLocalOf { ScreenScaleFactors() }

val LocalAppStrings = compositionLocalOf { AppStrings.Spanish }

/**
 * Responsive scale container that uses BoxWithConstraints to calculate
 * fluid responsive scaling factors combined with user manual slider overrides in real-time.
 */
@Composable
fun LocalScreenScaleProvider(
    settings: SettingsModel,
    modifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.() -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val width = maxWidth
        val height = maxHeight
        val isLandscape = width > height

        // Base automatic scale according to aspect ratio and diagonal width
        val baseAutoFactor = when {
            width >= 1200.dp -> 1.30f // TV / Large Desktop
            width >= 768.dp -> 1.15f  // Tablet / Foldable open
            width >= 480.dp -> 1.05f  // Large phone / Handheld landscape
            else -> 1.0f              // Compact phone
        }

        // Multiply base responsive scale by user manual settings percentages
        val fontMultiplier = (settings.textScalePercent / 100f) * baseAutoFactor
        val iconMultiplier = (settings.iconScalePercent / 100f) * baseAutoFactor
        val posterMultiplier = (settings.posterScalePercent / 100f) * (if (isLandscape && width < 900.dp) 0.9f else baseAutoFactor)
        val qrMultiplier = (settings.qrScalePercent / 100f) * (if (height < 600.dp) 0.85f else baseAutoFactor)

        val deviceMode = when {
            settings.deviceOverride == "Force Smart TV" || width >= 1200.dp -> DeviceType.TV
            settings.deviceOverride == "Force Tablet" || width >= 768.dp -> DeviceType.TABLET
            else -> DeviceType.PHONE
        }

        val scaleFactors = ScreenScaleFactors(
            fontScale = fontMultiplier,
            iconScale = iconMultiplier,
            posterScale = posterMultiplier,
            qrScale = qrMultiplier,
            deviceMode = deviceMode,
            isLandscape = isLandscape,
            maxWidthDp = width,
            maxHeightDp = height
        )

        CompositionLocalProvider(LocalScreenScale provides scaleFactors) {
            content()
        }
    }
}
