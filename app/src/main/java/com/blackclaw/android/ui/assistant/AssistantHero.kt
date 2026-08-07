package com.blackclaw.android.ui.assistant

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blackclaw.android.ui.design.ClawAccent
import com.blackclaw.android.ui.design.ClawHeroCard
import com.blackclaw.android.ui.design.ClawMotion

/**
 * Hero summary at the top of the assistant screen.
 *
 * The number and subtitle animate on change rather than snapping, because the value
 * changes as a direct result of the user checking something off — seeing it count
 * down is the feedback that the tap worked, and it removes the need for a toast.
 *
 * @param progress 0..1 completion for the active category, drives the bar. Pass a
 *   negative value to hide the bar (categories where "done" is meaningless).
 */
@Composable
fun AssistantHero(
    label: String,
    emoji: String,
    headline: String,
    subtitle: String,
    accent: ClawAccent,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    ClawHeroCard(
        accent = accent,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center,
                ) { Text(emoji, fontSize = 27.sp) }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accent.onAccent.copy(alpha = 0.75f),
                        letterSpacing = 1.2.sp,
                    )
                    Spacer(Modifier.height(2.dp))
                    // Slide the headline vertically on change: it reads as a counter
                    // ticking, which is exactly what it is.
                    AnimatedContent(
                        targetState = headline,
                        transitionSpec = {
                            (slideInVertically(ClawMotion.enterTween()) { -it / 2 } +
                                fadeIn(ClawMotion.enterTween())) togetherWith
                                (slideOutVertically(ClawMotion.exitTween()) { it / 2 } +
                                    fadeOut(ClawMotion.exitTween()))
                        },
                        label = "heroHeadline",
                    ) { value ->
                        Text(
                            value,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = accent.onAccent,
                        )
                    }
                }
            }
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                AnimatedContent(
                    targetState = subtitle,
                    transitionSpec = {
                        fadeIn(ClawMotion.standardTween()) togetherWith
                            fadeOut(ClawMotion.exitTween())
                    },
                    label = "heroSubtitle",
                ) { value ->
                    Text(
                        value,
                        fontSize = 13.sp,
                        color = accent.onAccent.copy(alpha = 0.85f),
                        lineHeight = 18.sp,
                    )
                }
            }
            if (progress >= 0f) {
                Spacer(Modifier.height(14.dp))
                HeroProgress(progress, accent)
            }
        }
    }
}

/**
 * Completion bar.
 *
 * Animated width rather than an indeterminate indicator: the value is known, and an
 * indeterminate spinner would falsely imply the app is busy.
 */
@Composable
private fun HeroProgress(progress: Float, accent: ClawAccent) {
    val target = progress.coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(ClawMotion.Deliberate, easing = ClawMotion.EaseIn),
        label = "heroProgress",
    )
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(accent.onAccent.copy(alpha = 0.20f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(animated)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(accent.onAccent.copy(alpha = 0.9f)),
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            "${(target * 100).toInt()}% completado",
            fontSize = 10.5.sp,
            color = accent.onAccent.copy(alpha = 0.7f),
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Empty state with a soft breathing badge, used when a category has no items. */
@Composable
fun AssistantEmptyState(
    emoji: String,
    title: String,
    hint: String,
    accent: ClawAccent,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(accent.base.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center,
            ) { Text(emoji, fontSize = 40.sp) }
            Spacer(Modifier.height(16.dp))
            Text(
                title,
                fontSize = 16.sp,
                color = Color(0xFFE6EAF7),
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                hint,
                fontSize = 12.5.sp,
                color = Color(0xFF6A7090),
                lineHeight = 18.sp,
            )
        }
    }
}
