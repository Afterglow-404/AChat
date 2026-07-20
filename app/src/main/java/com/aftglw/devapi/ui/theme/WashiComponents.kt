package com.aftglw.devapi.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import kotlin.random.Random

/**
 * Modifier for handcrafted Japanese paper (Washi) background.
 * Draws subtle fiber strands and watery ink bleeds.
 */
fun Modifier.washiBackground(color: Color): Modifier = this.then(
    Modifier.drawWithCache {
        // Precompute all random positions and brushes in the cache block so
        // they are regenerated only when size changes, not on every draw frame.
        val random = Random(123)

        // 1. Organic fibers — capture (start, end) so the Random sequence is
        // consumed once here, identical to the previous per-frame behaviour.
        val fibers = ArrayList<Pair<Offset, Offset>>(300)
        repeat(300) {
            val startX = random.nextFloat() * size.width
            val startY = random.nextFloat() * size.height
            val length = random.nextFloat() * 15f + 5f
            val angle = random.nextFloat() * Math.PI.toFloat() * 2f
            val endX = startX + Math.cos(angle.toDouble()).toFloat() * length
            val endY = startY + Math.sin(angle.toDouble()).toFloat() * length
            fibers.add(Offset(startX, startY) to Offset(endX, endY))
        }

        // 2. Soft watery stains — precompute center, radius and the
        // radialGradient Brush (Brush allocation is also moved out of the
        // per-frame draw path).
        val stainCenters = ArrayList<Offset>(4)
        val stainRadii = ArrayList<Float>(4)
        val stainBrushes = ArrayList<Brush>(4)
        repeat(4) {
            val center = Offset(random.nextFloat() * size.width, random.nextFloat() * size.height)
            val radius = random.nextFloat() * 150f + 50f
            stainCenters.add(center)
            stainRadii.add(radius)
            stainBrushes.add(
                Brush.radialGradient(
                    0f to Color.Black.copy(alpha = 0.02f),
                    1f to Color.Transparent,
                    center = center,
                    radius = radius
                )
            )
        }

        onDrawBehind {
            drawRect(color)

            for ((start, end) in fibers) {
                drawLine(
                    color = Color.Black.copy(alpha = 0.04f),
                    start = start,
                    end = end,
                    strokeWidth = 0.5f,
                    cap = StrokeCap.Round
                )
            }

            for (i in stainCenters.indices) {
                drawCircle(
                    brush = stainBrushes[i],
                    radius = stainRadii[i],
                    center = stainCenters[i]
                )
            }
        }
    }
)

/**
 * Modifier for Sumi-e (ink wash) style border.
 * Features irregular thickness and soft, bleeding edges.
 */
fun Modifier.sumiBorder(color: Color, seed: Int = 0): Modifier = this.then(
    Modifier.drawWithCache {
        // Build the irregular ink-wash Path once in the cache block so it
        // is regenerated only when size changes, not on every draw frame.
        val random = Random(seed)
        val path = Path().apply {
            val seg = 12f
            moveTo(0f, 0f)

            // Draw four edges with varying jitter
            var curr = 0f
            while (curr < size.width) {
                curr += seg
                lineTo(curr.coerceAtMost(size.width), random.nextFloat() * 2f - 1f)
            }
            curr = 0f
            while (curr < size.height) {
                curr += seg
                lineTo(size.width + random.nextFloat() * 2f - 1f, curr.coerceAtMost(size.height))
            }
            curr = size.width
            while (curr > 0f) {
                curr -= seg
                lineTo(curr.coerceAtMost(size.width), size.height + random.nextFloat() * 2f - 1f)
            }
            curr = size.height
            while (curr > 0f) {
                curr -= seg
                lineTo(random.nextFloat() * 2f - 1f, curr.coerceAtMost(size.height))
            }
            close()
        }

        onDrawBehind {
            // Layer 1: The "bleed" (wide, very faint)
            drawPath(
                path = path,
                color = color.copy(alpha = 0.15f),
                style = Stroke(width = 4.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round)
            )
            // Layer 2: The "ink" (inner, sharper but still irregular)
            drawPath(
                path = path,
                color = color.copy(alpha = 0.6f),
                style = Stroke(width = 1.2.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round)
            )
        }
    }
)

/**
 * A Hanko (Seal) stamp decorator.
 */
@Composable
fun Modifier.hankoStamp(
    text: String,
    color: Color = Color(0xFFC62828),
    seed: Int = 0
): Modifier {
    val textMeasurer = rememberTextMeasurer()
    val stampFont = AchatTheme.typography.title // Uses Zhi Mang Xing
    
    return this.drawBehind {
        val random = Random(seed)
        val stampSize = 24.dp.toPx()
        val padding = 8.dp.toPx()
        
        // Randomly place near a corner (usually bottom right for seals)
        val topLeft = Offset(size.width - stampSize - padding, size.height - stampSize - padding)
        
        // 1. Draw the "carved" red square (slightly irregular)
        val rectPath = Path().apply {
            addRect(androidx.compose.ui.geometry.Rect(topLeft, Size(stampSize, stampSize)))
        }
        drawPath(
            path = rectPath,
            color = color.copy(alpha = 0.8f),
            style = Fill
        )
        
        // 2. Draw the white text (carved out look)
        val textResult = textMeasurer.measure(
            text = text,
            style = TextStyle(
                color = Color.White,
                fontSize = 14.sp,
                fontFamily = stampFont,
                fontWeight = FontWeight.Bold
            )
        )
        
        drawText(
            textLayoutResult = textResult,
            topLeft = topLeft + Offset(
                (stampSize - textResult.size.width) / 2,
                (stampSize - textResult.size.height) / 2
            )
        )
        
        // 3. Add some "wear and tear" to the stamp (white specks)
        repeat(15) {
            drawCircle(
                color = Color.White.copy(alpha = 0.3f),
                radius = 0.8f,
                center = topLeft + Offset(random.nextFloat() * stampSize, random.nextFloat() * stampSize)
            )
        }
    }
}
