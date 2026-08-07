package com.blackclaw.android.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's buttons.
 *
 * Every one gets press scaling, a haptic tick and a disabled state that is visibly
 * different rather than merely dimmer. Text is centred and the touch target is at
 * least 48 dp tall, which several of the hand-rolled buttons this replaces were not
 * — a 36 dp button fails the platform's minimum target size and is genuinely hard to
 * hit one-handed.
 */

/** Filled, gradient-backed. One per screen: the thing the user came to do. */
@Composable
fun ClawPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: ClawAccent = ClawPalette.Signature,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val interaction = rememberClawInteraction()
    val haptic = rememberHapticTick()
    val active = enabled && !loading
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clawPressScale(interaction)
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (active) Modifier.background(accent.gradient)
                // Disabled reads as a flat outline, not a faded fill: a dimmed
                // gradient still looks tappable on an OLED panel.
                else Modifier
                    .background(ClawPalette.Elevation.Level2)
                    .border(1.dp, ClawPalette.Elevation.Outline, RoundedCornerShape(16.dp))
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = active,
            ) { haptic(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = accent.onAccent,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(10.dp))
            } else if (icon != null) {
                Icon(icon, null, tint = if (active) accent.onAccent else ClawPalette.Elevation.Outline,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text,
                color = if (active) accent.onAccent else Color(0xFF6A6A85),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Outlined. Secondary actions that sit beside a primary. */
@Composable
fun ClawSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: ClawAccent = ClawPalette.Signature,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val interaction = rememberClawInteraction()
    val haptic = rememberHapticTick()
    val borderColor by animateColorAsState(
        if (enabled) accent.outline else ClawPalette.Elevation.Outline,
        ClawMotion.quickTween(), label = "secondaryBorder",
    )
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clawPressScale(interaction)
            .clip(RoundedCornerShape(14.dp))
            .background(accent.base.copy(alpha = if (enabled) 0.10f else 0.04f))
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
            ) { haptic(); onClick() }
            .alpha(if (enabled) 1f else 0.5f),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = accent.base, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
            }
            Text(text, color = accent.base, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Compact pill, for filters and inline toggles. */
@Composable
fun ClawChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: ClawAccent = ClawPalette.Signature,
    leading: String? = null,
    badgeCount: Int = 0,
) {
    val interaction = rememberClawInteraction()
    val haptic = rememberHapticTick()
    val bg by animateColorAsState(
        if (selected) accent.base else ClawPalette.Elevation.Level1,
        ClawMotion.quickTween(), label = "chipBg",
    )
    val fg by animateColorAsState(
        if (selected) accent.onAccent else Color(0xFF8A90AE),
        ClawMotion.quickTween(), label = "chipFg",
    )
    Box(
        modifier = modifier
            .clawPressScale(interaction)
            .clip(RoundedCornerShape(22.dp))
            .background(bg)
            .then(
                if (selected) Modifier
                else Modifier.border(0.5.dp, ClawPalette.Elevation.Outline, RoundedCornerShape(22.dp))
            )
            .clickable(interactionSource = interaction, indication = null) { haptic(); onClick() },
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Text(leading, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
            }
            Text(text, fontSize = 13.sp, color = fg,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            if (badgeCount > 0) {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) accent.onAccent.copy(alpha = 0.25f)
                            else accent.base.copy(alpha = 0.20f)
                        )
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        "$badgeCount", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = if (selected) accent.onAccent else accent.base,
                    )
                }
            }
        }
    }
}
