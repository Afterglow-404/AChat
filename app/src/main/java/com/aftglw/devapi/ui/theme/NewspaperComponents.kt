package com.aftglw.devapi.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/**
 * A modifier that adds a paper-like texture with grain, stains, and halftone dots.
 * Flattened for the "Classic Print" look.
 */
fun Modifier.newspaperBackground(color: Color): Modifier = this.then(
    Modifier.drawBehind {
        drawRect(color)
        
        val random = Random(42)
        
        // Draw halftone pattern (very subtle)
        val dotSpacing = 16f
        for (x in 0..(size.width / dotSpacing).toInt()) {
            for (y in 0..(size.height / dotSpacing).toInt()) {
                drawCircle(
                    color = Color.Black.copy(alpha = 0.015f),
                    radius = 1.0f,
                    center = Offset(x * dotSpacing, y * dotSpacing)
                )
            }
        }

        // Draw noise/grain
        repeat(800) {
            val x = random.nextFloat() * size.width
            val y = random.nextFloat() * size.height
            drawCircle(
                color = Color.Black.copy(alpha = 0.04f),
                radius = random.nextFloat() * 1.5f,
                center = Offset(x, y)
            )
        }
        
        // Draw very subtle "ink stains"
        repeat(3) {
            val x = random.nextFloat() * size.width
            val y = random.nextFloat() * size.height
            drawCircle(
                color = Color.Black.copy(alpha = 0.02f),
                radius = random.nextFloat() * 80f + 40f,
                center = Offset(x, y)
            )
        }
    }
)

/**
 * Adds a "Print Rule" (thin straight line) to the element.
 * Classic newspapers use these to separate columns and sections.
 */
fun Modifier.printRule(
    color: Color = Color(0xFF1A1A1A),
    thickness: Float = 0.8f,
    double: Boolean = false,
    top: Boolean = false,
    bottom: Boolean = false,
    left: Boolean = false,
    right: Boolean = false,
    all: Boolean = false
): Modifier = this.then(
    Modifier.drawBehind {
        val strokeWidth = thickness.dp.toPx()
        val spacing = 2.dp.toPx() // Spacing for double lines
        
        fun drawRule(start: Offset, end: Offset) {
            if (double) {
                // Determine direction to shift for the second line
                val isHorizontal = start.y == end.y
                val shift = if (isHorizontal) Offset(0f, spacing) else Offset(spacing, 0f)
                
                drawLine(color, start, end, strokeWidth)
                drawLine(color, start + shift, end + shift, strokeWidth)
            } else {
                drawLine(color, start, end, strokeWidth)
            }
        }

        if (all || top) drawRule(Offset(0f, 0f), Offset(size.width, 0f))
        if (all || bottom) drawRule(Offset(0f, size.height), Offset(size.width, size.height))
        if (all || left) drawRule(Offset(0f, 0f), Offset(0f, size.height))
        if (all || right) drawRule(Offset(size.width, 0f), Offset(size.width, size.height))
    }
)

/**
 * Draws a decorative double rule header divider.
 */
fun Modifier.headerDoubleRule(color: Color = Color(0xFF1A1A1A)): Modifier = this.then(
    Modifier.drawBehind {
        val topY = 2.dp.toPx()
        val bottomY = 6.dp.toPx()
        drawLine(color, Offset(0f, topY), Offset(size.width, topY), strokeWidth = 1.2.dp.toPx())
        drawLine(color, Offset(0f, bottomY), Offset(size.width, bottomY), strokeWidth = 0.6.dp.toPx())
    }
)
