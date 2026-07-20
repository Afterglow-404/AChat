package com.aftglw.devapi.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/**
 * A modifier that adds a paper-like texture with grain, stains, and halftone dots.
 * Flattened for the "Classic Print" look.
 */
fun Modifier.newspaperBackground(color: Color): Modifier = this.then(
    Modifier.drawWithCache {
        // Precompute all random positions in the cache block so they are
        // regenerated only when size changes, not on every draw frame.
        val random = Random(42)
        val dotSpacing = 16f

        // Halftone dots — deterministic positions based on size
        val halftoneDots = ArrayList<Offset>()
        for (x in 0..(size.width / dotSpacing).toInt()) {
            for (y in 0..(size.height / dotSpacing).toInt()) {
                halftoneDots.add(Offset(x * dotSpacing, y * dotSpacing))
            }
        }

        // Noise/grain — capture (x, y, radius) so the Random sequence is
        // consumed once here, identical to the previous per-frame behaviour.
        val noise = ArrayList<Triple<Float, Float, Float>>(800)
        repeat(800) {
            val x = random.nextFloat() * size.width
            val y = random.nextFloat() * size.height
            val r = random.nextFloat() * 1.5f
            noise.add(Triple(x, y, r))
        }

        // Ink stains — capture (x, y, radius)
        val stains = ArrayList<Triple<Float, Float, Float>>(3)
        repeat(3) {
            val x = random.nextFloat() * size.width
            val y = random.nextFloat() * size.height
            val r = random.nextFloat() * 80f + 40f
            stains.add(Triple(x, y, r))
        }

        onDrawBehind {
            drawRect(color)

            for (center in halftoneDots) {
                drawCircle(
                    color = Color.Black.copy(alpha = 0.015f),
                    radius = 1.0f,
                    center = center
                )
            }

            for ((x, y, r) in noise) {
                drawCircle(
                    color = Color.Black.copy(alpha = 0.04f),
                    radius = r,
                    center = Offset(x, y)
                )
            }

            for ((x, y, r) in stains) {
                drawCircle(
                    color = Color.Black.copy(alpha = 0.02f),
                    radius = r,
                    center = Offset(x, y)
                )
            }
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
