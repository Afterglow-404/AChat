package com.example.wechatclone.ui.utils

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

@Composable
fun LiquidBottomTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    physicsEnabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val isLightTheme = !isSystemInDarkTheme()
    val accentColor = if (isLightTheme) Color(0xFF07C160) else Color(0xFF07C160)
    val containerColor = if (isLightTheme) Color(0xFFFAFAFA).copy(0.4f) else Color(0xFF121212).copy(0.4f)

    val tabsBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val density = LocalDensity.current
        val tabWidth = with(density) { (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / tabsCount }

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) { 4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction)) }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var currentIndex by remember(selectedTabIndex) { mutableIntStateOf(selectedTabIndex()) }

        // DampedDrag — only when physics enabled
        val dampedDragAnimation: DampedDragAnimation? = if (physicsEnabled) {
            remember(animationScope) {
                DampedDragAnimation(
                    animationScope = animationScope,
                    initialValue = selectedTabIndex().toFloat(),
                    valueRange = 0f..(tabsCount - 1).toFloat(),
                    visibilityThreshold = 0.001f,
                    initialScale = 1f,
                    pressedScale = 78f / 56f,
                    onDragStarted = {},
                    onDragStopped = {
                        val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                        currentIndex = targetIndex
                        animateToValue(targetIndex.toFloat())
                        animationScope.launch { offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f)) }
                    },
                    onDrag = { _, dragAmount ->
                        updateValue(
                            (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                                .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                        )
                        animationScope.launch { offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x) }
                    }
                )
            }
        } else null

        val dd = dampedDragAnimation
        val progress = dd?.pressProgress ?: 0f

        LaunchedEffect(selectedTabIndex) {
            snapshotFlow { selectedTabIndex() }.collectLatest { index -> currentIndex = index }
        }

        if (dd != null) {
            LaunchedEffect(dd) {
                snapshotFlow { currentIndex }.drop(1).collectLatest { index ->
                    dd.animateToValue(index.toFloat())
                    onTabSelected(index)
                }
            }
        }

        val interactiveHighlight: InteractiveHighlight? = if (physicsEnabled && dd != null) {
            remember(animationScope) {
                InteractiveHighlight(
                    animationScope = animationScope,
                    position = { size, offset ->
                        Offset(
                            if (isLtr) (dd.value + 0.5f) * tabWidth + panelOffset
                            else size.width - (dd.value + 0.5f) * tabWidth + panelOffset,
                            size.height / 2f
                        )
                    }
                )
            }
        } else null

        // Layer 1
        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = backdrop, shape = { Capsule() },
                    effects = { vibrancy(); blur(8f.dp.toPx()); lens(24f.dp.toPx(), 24f.dp.toPx()) },
                    layerBlock = if (physicsEnabled) { {
                        val s = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress); scaleX = s; scaleY = s
                    } } else null,
                    onDrawSurface = { drawRect(containerColor) }
                )
                .then(if (interactiveHighlight != null) interactiveHighlight.modifier else Modifier)
                .height(64f.dp).fillMaxWidth().padding(4f.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )

        // Layer 2
        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides { if (physicsEnabled) lerp(1f, 1.2f, progress) else 1f }
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}.alpha(0f).layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop, shape = { Capsule() },
                        effects = {
                            vibrancy(); blur(8f.dp.toPx())
                            lens(24f.dp.toPx() * progress, 24f.dp.toPx() * progress)
                        },
                        highlight = if (physicsEnabled) { { Highlight.Default.copy(alpha = progress) } } else null,
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(if (interactiveHighlight != null) interactiveHighlight.modifier else Modifier)
                    .height(56f.dp).fillMaxWidth().padding(horizontal = 4f.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        // Layer 3 — only with physics
        if (physicsEnabled && dd != null && interactiveHighlight != null) {
            Box(
                Modifier
                    .padding(horizontal = 4f.dp)
                    .graphicsLayer {
                        translationX = if (isLtr) dd.value * tabWidth + panelOffset
                        else size.width - (dd.value + 1f) * tabWidth + panelOffset
                    }
                    .then(interactiveHighlight.gestureModifier)
                    .then(dd.modifier)
                    .drawBackdrop(
                        backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                        shape = { Capsule() },
                        effects = {
                            lens(10f.dp.toPx() * progress, 14f.dp.toPx() * progress, chromaticAberration = true)
                        },
                        highlight = { Highlight.Default.copy(alpha = progress) },
                        shadow = { Shadow(alpha = progress) },
                        innerShadow = { InnerShadow(radius = 8f.dp * progress, alpha = progress) },
                        layerBlock = {
                            scaleX = dd.scaleX; scaleY = dd.scaleY
                            val v = dd.velocity / 10f
                            scaleX /= 1f - (v * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                            scaleY *= 1f - (v * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                        },
                        onDrawSurface = {
                            drawRect(if (isLightTheme) Color.Black.copy(0.1f) else Color.White.copy(0.1f), alpha = 1f - progress)
                            drawRect(Color.Black.copy(alpha = 0.03f * progress))
                        }
                    )
                    .height(56f.dp).fillMaxWidth(1f / tabsCount)
            )
        }
    }
}

internal val LocalLiquidBottomTabScale = androidx.compose.runtime.staticCompositionLocalOf { { 1f } }
