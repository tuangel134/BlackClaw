package com.blackclaw.android.ui.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/**
 * One place for every duration, easing and spring in the app.
 *
 * ## Why centralise motion
 *
 * Animation values scattered across screens drift: one card eases over 150 ms,
 * the next over 400, and the app stops feeling like one product. Worse, ad-hoc
 * numbers tend to land on the two values that feel wrong — fast enough to look
 * like a glitch, or slow enough to feel like lag.
 *
 * The scale below is deliberate. Anything under [Quick] reads as instant and is
 * wasted work; anything over [Deliberate] makes the user wait on the UI. Entrances
 * use springs because a physical settle communicates "this arrived" better than a
 * linear ramp, while exits use short tweens because nobody wants to watch something
 * leave.
 *
 * ## Accessibility
 *
 * Callers that animate purely for delight should check
 * [android.provider.Settings.Global.ANIMATOR_DURATION_SCALE] via
 * `LocalAccessibilityManager` or simply respect the platform's reduced-motion
 * setting. [ClawAnimation.reduceMotion] wraps that check.
 */
object ClawMotion {

    // ── Durations (ms) ────────────────────────────────────────────────────────

    /** State flips that must feel instant: ripples, checkbox fills, colour swaps. */
    const val Instant = 90

    /** Default for most property animations. */
    const val Quick = 180

    /** Content changing in place: crossfades, expanding rows. */
    const val Standard = 260

    /** Entrances and larger layout shifts. */
    const val Deliberate = 420

    /** Hero/first-run reveals only. Long enough to notice, short enough to forgive. */
    const val Showcase = 620

    // ── Easing ────────────────────────────────────────────────────────────────

    /** Material's emphasized-decelerate. For things entering the screen. */
    val EaseIn: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Emphasized-accelerate. For things leaving. */
    val EaseOut: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Symmetric, for in-place changes that neither arrive nor depart. */
    val EaseInOut: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    // ── Springs ───────────────────────────────────────────────────────────────

    /** Default settle: a hint of overshoot, no visible bounce. */
    fun <T> gentleSpring(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.85f,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** For press feedback and small scale changes — snappier, still soft. */
    fun <T> snappySpring(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.75f,
        stiffness = Spring.StiffnessMedium,
    )

    /** Visible bounce. Reserve for celebratory moments, not routine UI. */
    fun <T> bouncySpring(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.55f,
        stiffness = Spring.StiffnessMedium,
    )

    /** Layout/offset animations, where overshoot would clip against neighbours. */
    fun offsetSpring(): FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = 0.9f,
        stiffness = Spring.StiffnessMediumLow,
    )

    // ── Tweens ────────────────────────────────────────────────────────────────

    fun <T> quickTween(): FiniteAnimationSpec<T> = tween(Quick, easing = EaseInOut)
    fun <T> standardTween(): FiniteAnimationSpec<T> = tween(Standard, easing = EaseInOut)
    fun <T> enterTween(delayMs: Int = 0): FiniteAnimationSpec<T> =
        tween(Deliberate, delayMillis = delayMs, easing = EaseIn)
    fun <T> exitTween(): FiniteAnimationSpec<T> = tween(Quick, easing = EaseOut)

    // ── Staggering ────────────────────────────────────────────────────────────

    /**
     * Delay for the nth item in a staggered list reveal.
     *
     * Capped on purpose: an uncapped `index * step` means item 30 waits a second
     * and a half, which reads as the app being broken rather than as polish. Past
     * the cap everything lands together, which is what a fast scroll needs anyway.
     */
    fun staggerDelay(index: Int, stepMs: Int = 45, maxMs: Int = 320): Int =
        (index * stepMs).coerceAtMost(maxMs)
}
