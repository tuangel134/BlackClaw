package com.blackclaw.android.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.blackclaw.android.ui.chat.BlackClawColors

/**
 * Lightweight "liquid glass" primitives for BlackClaw.
 *
 * Compose does not expose a cheap, universal backdrop blur on every Android version
 * BlackClaw supports, so these surfaces intentionally build the glass impression from
 * translucent layered gradients, a luminous hairline and restrained elevation. This
 * keeps text sharp and scrolling inexpensive on API 28+ instead of blurring the card's
 * own content or relying on vendor-specific render effects.
 */
object ClawGlass {
    fun backdrop(colors: BlackClawColors): Brush = Brush.verticalGradient(
        listOf(
            colors.background,
            colors.background,
            mix(colors.background, colors.accent, 0.10f),
        ),
    )

    fun surface(colors: BlackClawColors, accent: Color = colors.accent): Brush =
        Brush.linearGradient(
            listOf(
                mix(colors.surface, Color.White, 0.035f).copy(alpha = 0.94f),
                mix(colors.aiBubble, accent, 0.055f).copy(alpha = 0.90f),
                colors.surface.copy(alpha = 0.86f),
            ),
        )

    fun border(colors: BlackClawColors, accent: Color = colors.accent): Color =
        mix(colors.aiBubbleBorder, accent, 0.30f).copy(alpha = 0.78f)

    private fun mix(a: Color, b: Color, amount: Float): Color =
        Color(
            red = a.red + (b.red - a.red) * amount,
            green = a.green + (b.green - a.green) * amount,
            blue = a.blue + (b.blue - a.blue) * amount,
            alpha = a.alpha + (b.alpha - a.alpha) * amount,
        )
}

@Composable
fun ClawGlassBackdrop(
    colors: BlackClawColors,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ClawGlass.backdrop(colors)),
        content = content,
    )
}

@Composable
fun ClawGlassCard(
    colors: BlackClawColors,
    modifier: Modifier = Modifier,
    accent: Color = colors.accent,
    radius: Dp = 22.dp,
    elevated: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    val interaction = rememberClawInteraction()
    val haptic = rememberHapticTick()
    val base = modifier
        .then(if (onClick != null) Modifier.clawPressScale(interaction) else Modifier)
        .then(if (elevated) Modifier.shadow(8.dp, shape, clip = false) else Modifier)
        .clip(shape)
        .background(ClawGlass.surface(colors, accent))
        .border(BorderStroke(0.75.dp, ClawGlass.border(colors, accent)), shape)
        .then(
            if (onClick != null) Modifier.clickable(
                interactionSource = interaction,
                indication = null,
            ) {
                haptic()
                onClick()
            } else Modifier,
        )
    Box(base, content = content)
}

@Composable
fun ClawGlassPill(
    colors: BlackClawColors,
    selected: Boolean,
    modifier: Modifier = Modifier,
    accent: Color = colors.accent,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val interaction = rememberClawInteraction()
    val haptic = rememberHapticTick()
    val fill = if (selected) {
        Brush.linearGradient(listOf(accent.copy(alpha = 0.24f), accent.copy(alpha = 0.10f)))
    } else {
        ClawGlass.surface(colors, accent.copy(alpha = 0.5f))
    }
    Box(
        modifier
            .clawPressScale(interaction)
            .clip(shape)
            .background(fill)
            .border(
                if (selected) 1.dp else 0.5.dp,
                if (selected) accent.copy(alpha = 0.78f) else colors.aiBubbleBorder.copy(alpha = 0.72f),
                shape,
            )
            .clickable(interactionSource = interaction, indication = null) {
                haptic()
                onClick()
            },
        content = content,
    )
}
