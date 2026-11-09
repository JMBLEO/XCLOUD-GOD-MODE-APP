package com.xcloud.godmode.input

/**
 * Normalizes touch points between the device screen and the 16:9 stream video viewport,
 * compensating for letterbox/pillarbox margins on tablets and foldables.
 */
class TouchProjectionMatrix {
    var screenWidth: Float = 1920f
    var screenHeight: Float = 1080f
    var streamAspectRatio: Float = 16f / 9f

    fun updateScreenDimensions(width: Float, height: Float) {
        screenWidth = width
        screenHeight = height
    }

    /**
     * Maps raw screen (x, y) into normalized [0.0f, 1.0f] stream viewport coordinates.
     * Returns null if touch falls in letterbox / black bar areas.
     */
    fun mapToStreamViewport(screenX: Float, screenY: Float): Pair<Float, Float>? {
        val currentAspect = screenWidth / screenHeight

        val (viewportWidth, viewportHeight, offsetX, offsetY) = if (currentAspect > streamAspectRatio) {
            // Pillarbox: black bars on left/right
            val vpHeight = screenHeight
            val vpWidth = screenHeight * streamAspectRatio
            val offX = (screenWidth - vpWidth) / 2f
            Quad(vpWidth, vpHeight, offX, 0f)
        } else {
            // Letterbox: black bars on top/bottom
            val vpWidth = screenWidth
            val vpHeight = screenWidth / streamAspectRatio
            val offY = (screenHeight - vpHeight) / 2f
            Quad(vpWidth, vpHeight, 0f, offY)
        }

        if (screenX < offsetX || screenX > (offsetX + viewportWidth) ||
            screenY < offsetY || screenY > (offsetY + viewportHeight)) {
            return null // In black bar zone
        }

        val normX = (screenX - offsetX) / viewportWidth
        val normY = (screenY - offsetY) / viewportHeight
        return Pair(normX.coerceIn(0f, 1f), normY.coerceIn(0f, 1f))
    }

    private data class Quad(val width: Float, val height: Float, val offsetX: Float, val offsetY: Float)
}
