package com.blackclaw.android.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Card containers and the entrance animation shared by every list in the app.
 */

/**
 * Reveals its content once, sliding up and fading in.
 *
 * ## Why this is a wrapper and not a modifier
 *
 * The reveal has to run exactly once per item, keyed to that item's identity. Doing
 * it with a modifier means the caller owns the `remember`, and the pattern was
 * consistently got wrong: re-running on every recomposition (so cards re-animate
 * while you scroll) or keyed to the index (so deleting an item re-animates every
 * card below it). Owning the state here makes the correct behaviour the default.
 *
 * @param index position in the list, used for the stagger. Pass the stable index.
 * @param enabled set false to skip the animation entirely — used when
 *   [ClawAnimation.reduceMotion] is on, so the content still appears instantly.
 */
@Composable
fun ClawReveal(
    index: Int = 0,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val delay = ClawMotion.staggerDelay(index)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(ClawMotion.enterTween(delay)) +
            slideInVertically(ClawMotion.enterTween(delay)) { it / 5 } +
            scaleIn(ClawMotion.enterTween(delay), initialScale = 0.94f),
    ) {
        content()
    }
}

/**
 * Standard card surface: tinted elevation, hairline border, optional accent edge.
 *
 * @param accentEdge draws a 3 dp vertical accent bar down the leading edge. Cheaper
 *   to scan than a coloured icon when a list mixes categories.
 */
@Composable
fun ClawCard(
    modifier: Modifier = Modifier,
    accent: ClawAccent? = null,
    accentEdge: Boolean = false,
    elevated: Boolean = false,
    onClick: (() -> Unit)? = null,
    cornerRadius: Dp = 18.dp,
    content: @Composable () -> Unit,
) {
    val interaction = rememberClawInteraction()
    val haptic = rememberHapticTick()
    val shape = RoundedCornerShape(cornerRadius)
    val surface = if (elevated) ClawPalette.Elevation.Level2 else ClawPalette.Elevation.Level1
    val border = accent?.outline ?: ClawPalette.Elevation.Outline

    Box(
        modifier
            .then(if (onClick != null) Modifier.clawPressScale(interaction) else Modifier)
            .clip(shape)
            .background(if (accent != null && elevated) accent.wash else surface)
            .border(if (accent != null) 1.dp else 0.5.dp, border, shape)
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = interaction,
                    indication = null,
                ) { haptic(); onClick() } else Modifier
            ),
    ) {
        if (accentEdge && accent != null) {
            Box(
                Modifier
                    .fillMaxWidth(0.012f)
                    .height(1000.dp) // clipped by the parent; simpler than measuring
                    .background(accent.gradient),
            )
        }
        content()
    }
}

/**
 * Hero surface: full-bleed accent gradient with a slow moving sheen.
 *
 * The sheen is the one piece of purely decorative motion in the design system, so it
 * is the one piece that checks [ClawAnimation.reduceMotion]. It travels slowly on
 * purpose — a fast highlight sweep on a large surface is the classic "cheap app"
 * tell, and it draws the eye away from the text it sits behind.
 */
@Composable
fun ClawHeroCard(
    accent: ClawAccent,
    modifier: Modifier = Modifier,
    animateSheen: Boolean = true,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val reduce = ClawAnimation.reduceMotion()
    val sheenOn = animateSheen && !reduce

    BoxWithConstraints(
        modifier
            .clip(shape)
            .background(accent.gradient),
    ) {
        if (sheenOn) {
            val transition = rememberInfiniteTransition(label = "heroSheen")
            val progress by transition.animateFloat(
                initialValue = -0.4f,
                targetValue = 1.4f,
                animationSpec = infiniteRepeatable(
                    // Deliberately long: this should read as ambient light, not as a
                    // loading indicator.
                    animation = tween(5200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "sheenProgress",
            )
            val width = maxWidth
            // matchParentSize, never height(maxHeight).
            //
            // A Column measures its first child with all of the remaining height, so
            // `maxHeight` here resolves to the entire screen. Asking the sheen for that
            // height made this Box size itself to the screen: the card swallowed the
            // whole viewport and every sibling below it was then measured with zero
            // height left and disappeared. On the assistant screen that silently removed
            // the category chips and the item list.
            //
            // matchParentSize fills the card without taking part in measuring it, so the
            // card is still sized by [content].
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier
                        .offset(x = width * progress)
                        .fillMaxWidth(0.35f)
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.10f),
                                    Color.Transparent,
                                )
                            )
                        ),
                )
            }
        }
        content()
    }
}

/**
 * Shimmering placeholder for content that has not loaded.
 *
 * Preferred over a spinner for list content because it preserves layout: the page
 * does not jump when real data replaces it, and the user can see the shape of what
 * is coming. Falls back to a static block under reduced motion.
 */
@Composable
fun ClawShimmer(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
) {
    val shape = RoundedCornerShape(cornerRadius)
    if (ClawAnimation.reduceMotion()) {
        Box(modifier.clip(shape).background(ClawPalette.Elevation.Level2))
        return
    }
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerShift",
    )
    // The caller must give this a height: it stands in for content that has not loaded,
    // so only the layout it replaces knows how tall it should be. [ClawShimmerList] does.
    // `fillMaxSize` rather than `height(maxHeight)` keeps it from being the same trap as
    // the hero sheen above if someone ever drops it into a Column without a height.
    BoxWithConstraints(modifier.clip(shape)) {
        val w = maxWidth.value
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = ClawPalette.shimmerSheen,
                        start = androidx.compose.ui.geometry.Offset(
                            x = (shift - 0.5f) * w * 2f, y = 0f,
                        ),
                        end = androidx.compose.ui.geometry.Offset(
                            x = (shift + 0.5f) * w * 2f, y = 0f,
                        ),
                    )
                ),
        )
    }
}

/** Vertical stack of shimmer rows, for a list that is still loading. */
@Composable
fun ClawShimmerList(
    rows: Int = 4,
    rowHeight: Dp = 76.dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        repeat(rows) {
            ClawShimmer(
                Modifier
                    .fillMaxWidth()
                    .height(rowHeight),
                cornerRadius = 18.dp,
            )
            Box(Modifier.height(10.dp))
        }
    }
}
