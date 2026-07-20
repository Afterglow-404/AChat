package com.aftglw.devapi.ui.utils

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
 *
 * The "has already animated" flag is tracked with [rememberSaveable] so that
 * when a LazyColumn item scrolls out of the viewport and later re-enters (with
 * a stable key), the entrance animation is NOT re-triggered. Only the first
 * appearance of each item animates; subsequent recompositions or re-entries
 * render at the final state directly.
 */
@Composable
fun StaggeredEntrance(
    index: Int,
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // rememberSaveable: survives configuration changes and (for LazyColumn
    // items with a stable key) the composable being disposed and re-created
    // when scrolling back into the viewport.
    var hasAnimated by rememberSaveable { mutableStateOf(false) }

    // When the item has already animated, pin the target to 1f so that
    // animateFloatAsState initialises (and stays) at 1f on re-composition or
    // re-entry, skipping the spring animation entirely. Otherwise, follow the
    // caller's `visible` flag (false -> 0f, true -> 1f) so the first entrance
    // still plays the spring from 0f to 1f.
    val target = if (hasAnimated) 1f else if (visible) 1f else 0f

    LaunchedEffect(visible) {
        if (visible && !hasAnimated) {
            hasAnimated = true
        }
    }

    val animProgress by animateFloatAsState(
        targetValue = target,
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
