package com.xcloud.godmode.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * MinimalQrCode: A simple SVG-like grid-based QR generator.
 * Refined with a center branding box and improved matrix generation logic.
 */
@Composable
fun MinimalQrCode(
    text: String,
    modifier: Modifier = Modifier,
    darkColor: Color = Color.White,
    lightColor: Color = Color.Transparent
) {
    val size = 21
    val matrix = generateMinimalMatrix(text, size)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val moduleSize = this.size.width / size
            
            for (y in 0 until size) {
                for (x in 0 until size) {
                    if (matrix[y][x]) {
                        drawRect(
                            color = darkColor,
                            topLeft = Offset(x * moduleSize, y * moduleSize),
                            size = Size(moduleSize, moduleSize)
                        )
                    }
                }
            }
        }
        
        // Center branding box (Console style)
        Surface(
            modifier = Modifier.size(42.dp),
            color = darkColor,
            shape = RoundedCornerShape(4.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.SportsEsports,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

/**
 * Genera una matriz determinística basada en el texto para simular un QR funcional en el applet.
 */
private fun generateMinimalMatrix(text: String, size: Int): Array<BooleanArray> {
    val matrix = Array(size) { BooleanArray(size) }
    
    // 1. Patrones de posición (Esquinas)
    drawFinderPattern(matrix, 0, 0)
    drawFinderPattern(matrix, size - 7, 0)
    drawFinderPattern(matrix, 0, size - 7)
    
    // 2. Líneas de sincronía
    for (i in 8 until size - 8) {
        matrix[6][i] = i % 2 == 0
        matrix[i][6] = i % 2 == 0
    }
    
    // 3. Datos determinísticos basados en el hash del texto
    val hash = text.hashCode()
    val random = java.util.Random(hash.toLong())
    
    for (y in 0 until size) {
        for (x in 0 until size) {
            // No sobrescribir patrones fijos ni el centro
            if (isReserved(x, y, size)) continue
            matrix[y][x] = random.nextBoolean()
        }
    }
    
    return matrix
}

private fun drawFinderPattern(matrix: Array<BooleanArray>, x: Int, y: Int) {
    for (i in 0 until 7) {
        for (j in 0 until 7) {
            val isBorder = i == 0 || i == 6 || j == 0 || j == 6
            val isInner = i in 2..4 && j in 2..4
            if (isBorder || isInner) {
                matrix[y + i][x + j] = true
            }
        }
    }
}

private fun isReserved(x: Int, y: Int, size: Int): Boolean {
    // Esquinas de posición
    if (x < 8 && y < 8) return true
    if (x > size - 9 && y < 8) return true
    if (x < 8 && y > size - 9) return true
    // Líneas de sincronía
    if (x == 6 || y == 6) return true
    // Centro (Espacio para el logo)
    if (x in 8..12 && y in 8..12) return true
    return false
}
