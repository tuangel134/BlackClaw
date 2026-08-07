package com.blackclaw.android.ui.design

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext

/**
 * Press feedback and reduced-motion support.
 *
 * ## Why press scaling instead of just a ripple
 *
 * A ripple tells the user *where* they touched. It does not tell them the surface is
 * interactive before they touch it, and on a dark theme a low-contrast ripple is
 * easy to miss entirely. A small scale-down reads as physical depression and is
 * legible at any contrast, so the two together cover both jobs.
 *
 * Kept deliberately small (see [ClawAnimation.PRESS_SCALE]): anything more than a
 * few percent makes text visibly reflow, which looks like a rendering bug rather
 * than a button.
 */
object ClawAnimation {

    /** How far a pressed surface shrinks. Beyond ~0.97 the text reflow is visible. */
    const val PRESS_SCALE = 0.965f

    /**
     * Whether the user asked the system to minimise animation.
     *
     * Respecting this is not optional: for users with vestibular disorders, motion
     * they did not consent to causes real symptoms. Decorative animation must check
     * this; state-change animation (a checkbox filling) may ignore it because
     * removing it would lose information.
     */
    @Composable
    fun reduceMotion(): Boolean {
        val context = LocalContext.current
        return remember(context) {
            val scale = runCatching {
                android.provider.Settings.Global.getFloat(
                    context.contentResolver,
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f,
                )
            }.getOrDefault(1f)
            scale == 0f
        }
    }
}

/**
 * Scale the receiver down while [interactionSource] reports a press.
 *
 * Attach to the same element that owns the clickable so the visual and the gesture
 * cannot disagree about what is being pressed.
 */
@Composable
fun Modifier.clawPressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = ClawAnimation.PRESS_SCALE,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = ClawMotion.snappySpring(),
        label = "pressScale",
    )
    return this.scale(scale)
}

/** Remembers an interaction source, for callers that only need press scaling. */
@Composable
fun rememberClawInteraction(): MutableInteractionSource =
    remember { MutableInteractionSource() }

/**
 * Fire a short haptic tick.
 *
 * Routed through the View rather than `LocalHapticFeedback` so it honours the user's
 * system haptics setting: `HapticFeedbackConstants` respects
 * "touch vibration" in Settings, which is what a user who turned haptics off
 * expects. Wrapped because some OEM builds throw on unusual constants.
 */
@Composable
fun rememberHapticTick(): () -> Unit {
    val view = LocalView.current
    return remember(view) {
        {
            runCatching {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            Unit
        }
    }
}

/** Stronger haptic for a completed or destructive action. */
@Composable
fun rememberHapticConfirm(): () -> Unit {
    val view = LocalView.current
    return remember(view) {
        {
            runCatching {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
            Unit
        }
    }
}
