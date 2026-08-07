package com.blackclaw.android.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Accent identities and the gradient/elevation recipes built from them.
 *
 * ## Why accents are named by meaning, not by colour
 *
 * The screens each hard-coded their own hex values, so "the purple one" meant four
 * different purples depending on the file. Naming by role ([Reminder], [Alarm], …)
 * means a screen asks for the identity it needs and the palette decides the pixels,
 * so a future theme change lands in one file instead of forty call sites.
 *
 * Every accent ships a matching [ClawAccent.gradient] and [ClawAccent.glow] so a
 * card, its badge and its progress bar are automatically consistent — that
 * relationship was previously re-derived by hand with `copy(alpha = …)` at each use
 * and drifted constantly.
 *
 * ## Contrast
 *
 * [ClawAccent.onAccent] is the foreground colour to use on top of the solid accent.
 * It is picked per accent rather than always white because several of these
 * (amber, teal, lime) fail contrast against white text at body sizes. Do not
 * substitute `Color.White` at the call site.
 */
data class ClawAccent(
    val name: String,
    /** Solid, full-strength. Badges, selected pills, progress fills. */
    val base: Color,
    /** Lighter end of the gradient. */
    val light: Color,
    /** Darker end of the gradient. */
    val deep: Color,
    /** Foreground that meets contrast on [base]. */
    val onAccent: Color,
) {
    /** Diagonal fill for hero surfaces. */
    val gradient: Brush
        get() = Brush.linearGradient(listOf(light, base, deep))

    /** Horizontal fill for thin accent bars and underlines. */
    val bar: Brush
        get() = Brush.horizontalGradient(listOf(base, base.copy(alpha = 0.15f)))

    /** Very low-alpha wash for a card background that hints at the accent. */
    val wash: Color
        get() = base.copy(alpha = 0.10f)

    /** Border tint for an accent-owned card. */
    val outline: Color
        get() = base.copy(alpha = 0.35f)

    /** Colour for a soft shadow under an accent surface. */
    val glow: Color
        get() = deep.copy(alpha = 0.45f)

    /** Blend toward a background, for disabled/settled states. */
    fun muted(fraction: Float = 0.6f, background: Color = Color(0xFF0A0A0F)): Color =
        lerp(base, background, fraction.coerceIn(0f, 1f))
}

object ClawPalette {

    // ── Semantic accents ──────────────────────────────────────────────────────

    val Reminder = ClawAccent(
        name = "reminder",
        base = Color(0xFF8B5CF6), light = Color(0xFFA78BFA), deep = Color(0xFF6D28D9),
        onAccent = Color(0xFFFFFFFF),
    )

    val Alarm = ClawAccent(
        name = "alarm",
        base = Color(0xFFF59E0B), light = Color(0xFFFBBF24), deep = Color(0xFFB45309),
        // Amber against white fails contrast at body sizes; near-black reads cleanly.
        onAccent = Color(0xFF231400),
    )

    val Note = ClawAccent(
        name = "note",
        base = Color(0xFF38BDF8), light = Color(0xFF7DD3FC), deep = Color(0xFF0369A1),
        onAccent = Color(0xFF04202E),
    )

    val Event = ClawAccent(
        name = "event",
        base = Color(0xFFEC4899), light = Color(0xFFF9A8D4), deep = Color(0xFFBE185D),
        onAccent = Color(0xFFFFFFFF),
    )

    val Alert = ClawAccent(
        name = "alert",
        base = Color(0xFFEF4444), light = Color(0xFFFCA5A5), deep = Color(0xFFB91C1C),
        onAccent = Color(0xFFFFFFFF),
    )

    val Finance = ClawAccent(
        name = "finance",
        base = Color(0xFF22C55E), light = Color(0xFF86EFAC), deep = Color(0xFF15803D),
        onAccent = Color(0xFF04220F),
    )

    val Shopping = ClawAccent(
        name = "shopping",
        base = Color(0xFF14B8A6), light = Color(0xFF5EEAD4), deep = Color(0xFF0F766E),
        onAccent = Color(0xFF03211E),
    )

    /** The product's own identity colour. Primary actions, the claw orb, branding. */
    val Signature = ClawAccent(
        name = "signature",
        base = Color(0xFF00D4FF), light = Color(0xFF7DE9FF), deep = Color(0xFF0077A3),
        onAccent = Color(0xFF001A22),
    )

    /** Security/consent surfaces: pairing codes, privileged toggles. */
    val Guard = ClawAccent(
        name = "guard",
        base = Color(0xFFFBBF24), light = Color(0xFFFDE68A), deep = Color(0xFF92400E),
        onAccent = Color(0xFF231400),
    )

    /** Danger: destructive confirmations, emergency mode. */
    val Danger = ClawAccent(
        name = "danger",
        base = Color(0xFFF43F5E), light = Color(0xFFFDA4AF), deep = Color(0xFF9F1239),
        onAccent = Color(0xFFFFFFFF),
    )

    // ── Neutral surfaces ──────────────────────────────────────────────────────

    /**
     * Elevation by tint rather than by shadow.
     *
     * On a near-black background a drop shadow is invisible, so depth has to come
     * from the surface getting lighter. Levels are spaced far enough apart to be
     * distinguishable on cheap panels, which a 2-3% step is not.
     */
    object Elevation {
        val Level0 = Color(0xFF0A0A0F) // app background
        val Level1 = Color(0xFF12121C) // cards
        val Level2 = Color(0xFF1A1A28) // raised cards, sheets
        val Level3 = Color(0xFF232336) // dialogs, menus
        val Outline = Color(0xFF2A2A40) // hairline borders
    }

    /**
     * Sheen used for the shimmer of a loading placeholder.
     *
     * Animated by [ClawShimmer]; kept here so the loading state matches the
     * elevation scale instead of being an unrelated grey.
     */
    val shimmerSheen = listOf(
        Elevation.Level1,
        Elevation.Level3,
        Elevation.Level1,
    )

    /**
     * Backdrop for full-screen surfaces: a deep vertical fade with a hint of the
     * signature colour, so the app does not read as flat black.
     */
    @Composable
    fun backdrop(accent: ClawAccent = Signature): Brush = remember(accent.name) {
        Brush.verticalGradient(
            0f to Color(0xFF07070C),
            0.55f to Elevation.Level0,
            1f to lerp(Elevation.Level0, accent.deep, 0.18f),
        )
    }

    /** Accent for an assistant category, so callers never re-pick hex values. */
    fun forCategory(name: String): ClawAccent = when (name.lowercase()) {
        "reminder" -> Reminder
        "alarm" -> Alarm
        "note" -> Note
        "event" -> Event
        "alert" -> Alert
        "finance" -> Finance
        "shopping" -> Shopping
        else -> Signature
    }
}
