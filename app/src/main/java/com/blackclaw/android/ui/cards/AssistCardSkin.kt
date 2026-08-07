package com.blackclaw.android.ui.cards

import androidx.compose.ui.graphics.Color

/**
 * Colours a card draws with.
 *
 * ## Why the cards do not just use the design system
 *
 * These cards appear on two surfaces with unrelated palettes. The power-button panel has
 * its own obsidian-and-gold identity, hardcoded and independent of the ten user themes; the chat is
 * driven entirely by the theme the user picked. A card that reached for `ClawPalette`
 * would look pasted in from a different app on the panel, and a card that reached for the
 * chat theme would be wrong on the panel.
 *
 * Passing the palette in keeps one implementation of each card serving both, and makes
 * the surface — not the card — responsible for looking like itself.
 */
data class AssistCardSkin(
    val surface: Color,
    val surfaceRaised: Color,
    val outline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val price: Color,
) {
    companion object {
        /** The power-button panel: obsidian and gold, over its own quiet glow. */
        val AssistPanel = AssistCardSkin(
            surface = Color(0xFF15130F),
            surfaceRaised = Color(0xFF201C14),
            outline = Color(0xFF403623),
            textPrimary = Color(0xFFFFF8E7),
            textSecondary = Color(0xFFC0B59D),
            textTertiary = Color(0xFF827864),
            accent = Color(0xFFD7AC4A),
            onAccent = Color(0xFF050504),
            price = Color(0xFFFFD67A),
        )
    }
}
