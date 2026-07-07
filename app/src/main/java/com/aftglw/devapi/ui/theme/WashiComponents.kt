package com.aftglw.devapi.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
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
    Modifier.drawBehind {
        drawRect(color)
        val random = Random(123)
        
        // 1. Draw organic fibers (short, thin, curved-ish lines)
        repeat(300) {
            val startX = random.nextFloat() * size.width
            val startY = random.nextFloat() * size.height
            val length = random.nextFloat() * 15f + 5f
            val angle = random.nextFloat() * Math.PI.toFloat() * 2f
            
            drawLine(
                color = Color.Black.copy(alpha = 0.04f),
                start = Offset(startX, startY),
                end = Offset(
                    startX + Math.cos(angle.toDouble()).toFloat() * length,
                    startY + Math.sin(angle.toDouble()).toFloat() * length
                ),
                strokeWidth = 0.5f,
                cap = StrokeCap.Round
            )
        }
        
        // 2. Draw soft watery stains
        repeat(4) {
            val center = Offset(random.nextFloat() * size.width, random.nextFloat() * size.height)
            val radius = random.nextFloat() * 150f + 50f
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color.Black.copy(alpha = 0.02f),
                    1f to Color.Transparent,
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
        }
    }
)

/**
 * Modifier for Sumi-e (ink wash) style border.
 * Features irregular thickness and soft, bleeding edges.
 */
fun Modifier.sumiBorder(color: Color, seed: Int = 0): Modifier = this.then(
    Modifier.drawBehind {
        val random = Random(seed)
        val path = Path().apply {
            val seg = 12f
            moveTo(0f, 0f)
            
            // Draw four edges with varying jitter
            var curr = 0f
            while(curr < size.width) {
                curr += seg
                lineTo(curr.coerceAtMost(size.width), random.nextFloat() * 2f - 1f)
            }
            curr = 0f
            while(curr < size.height) {
                curr += seg
                lineTo(size.width + random.nextFloat() * 2f - 1f, curr.coerceAtMost(size.height))
            }
            curr = size.width
            while(curr > 0f) {
                curr -= seg
                lineTo(curr.coerceAtMost(size.width), size.height + random.nextFloat() * 2f - 1f)
            }
            curr = size.height
            while(curr > 0f) {
                curr -= seg
                lineTo(random.nextFloat() * 2f - 1f, curr.coerceAtMost(size.height))
            }
            close()
        }

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
