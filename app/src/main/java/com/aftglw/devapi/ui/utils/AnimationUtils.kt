package com.aftglw.devapi.ui.utils

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

object AnimationUtils {
    /**
     * Standard horizontal transition for navigating between screens (forward and backward).
     */
    fun slideHorizontal(forward: Boolean = true): ContentTransform {
        return if (forward) {
            (slideInHorizontally(initialOffsetX = { it }) + fadeIn()) togetherWith
                    (slideOutHorizontally(targetOffsetX = { -it / 2 }) + fadeOut())
        } else {
            (slideInHorizontally(initialOffsetX = { -it / 2 }) + fadeIn()) togetherWith
                    (slideOutHorizontally(targetOffsetX = { it }) + fadeOut())
        }
    }

    /**
     * Standard fade transition for tab switching.
     */
    fun fadeThrough(): ContentTransform {
        return fadeIn(animationSpec = tween(220, delayMillis = 90)) togetherWith
                fadeOut(animationSpec = tween(90))
    }

    /**
     * Subtle scale and fade for title transitions.
     */
    fun titleTransition(): ContentTransform {
        return (fadeIn() + scaleIn(initialScale = 0.92f)) togetherWith fadeOut()
    }
}

/**
 * A wrapper that applies a staggered entrance animation to its content.
 * Ideal for list items.
 */
@Composable
fun StaggeredEntrance(
    index: Int,
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val animProgress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "staggered_entrance_$index"
    )

    Box(
        modifier = modifier.graphicsLayer {
            alpha = animProgress
            translationY = (1f - animProgress) * 50f
            scaleX = 0.98f + (0.02f * animProgress)
            scaleY = 0.98f + (0.02f * animProgress)
        }
    ) {
        content()
    }
}
