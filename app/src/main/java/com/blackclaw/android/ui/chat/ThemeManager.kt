package com.blackclaw.android.ui.chat

import android.graphics.Color
import com.blackclaw.android.utils.KVUtils

/**
 * BlackClaw runtime theme color provider.
 *
 * Themes:
 *  - blackclaw_dark : Carbon black + electric cyan (default)
 *  - midnight_dark  : Deep navy + violet
 *  - aurora_dark    : Boreal green + violet gradient feel
 *  - cyberpunk_dark : Neon pink + electric cyan
 *  - ocean_dark     : Deep sea + turquoise
 *  - sunset_dark    : Charcoal + warm coral/pink
 *  - mono_dark      : Pure greyscale, elegant
 *  - arctic_light   : Clean white + cyan
 *  - slate_light    : Warm slate + indigo
 *  - cream_light    : Cream + magenta
 */
object ThemeManager {

    data class ChatColors(
        val bg: Int,
        val toolbarBg: Int,
        val userBubble: Int,
        val userText: Int,
        val aiBubble: Int,
        val aiBubbleBorder: Int,
        val aiText: Int,
        val avatarBg: Int,
        val inputBorder: Int,
        val sendColor: Int,
        val toolOk: Int,
        val toolDefault: Int,
        val divider: Int,
        // Nuevos para gradientes / acentos secundarios
        val accentSecondary: Int = sendColor,
        val gradientStart: Int = sendColor,
        val gradientEnd: Int = sendColor,
    )

    private val themes = mapOf(

        "blackclaw_dark" to ChatColors(
            bg = c("#0A0A0F"), toolbarBg = c("#0F0F1A"),
            userBubble = c("#0066CC"), userText = c("#F0F8FF"),
            aiBubble = c("#141420"), aiBubbleBorder = c("#1E1E30"),
            aiText = c("#C8D0E8"), avatarBg = c("#0055AA"),
            inputBorder = c("#1E1E30"), sendColor = c("#00D4FF"),
            toolOk = c("#00D4FF"), toolDefault = c("#3A3A55"),
            divider = c("#0F0F1A"),
            accentSecondary = c("#5DE9FF"),
            gradientStart = c("#00D4FF"), gradientEnd = c("#0066CC"),
        ),

        "midnight_dark" to ChatColors(
            bg = c("#080B14"), toolbarBg = c("#0D1120"),
            userBubble = c("#5B21B6"), userText = c("#F5F0FF"),
            aiBubble = c("#111828"), aiBubbleBorder = c("#1C2540"),
            aiText = c("#C4C8E0"), avatarBg = c("#4C1D95"),
            inputBorder = c("#1C2540"), sendColor = c("#A78BFA"),
            toolOk = c("#A78BFA"), toolDefault = c("#2E3555"),
            divider = c("#0D1120"),
            accentSecondary = c("#C4B5FD"),
            gradientStart = c("#A78BFA"), gradientEnd = c("#5B21B6"),
        ),

        "aurora_dark" to ChatColors(
            bg = c("#070C12"), toolbarBg = c("#0B141C"),
            userBubble = c("#10B981"), userText = c("#ECFDF5"),
            aiBubble = c("#0E1B22"), aiBubbleBorder = c("#1A2932"),
            aiText = c("#C8E0D6"), avatarBg = c("#059669"),
            inputBorder = c("#1A2932"), sendColor = c("#34D399"),
            toolOk = c("#34D399"), toolDefault = c("#2A4540"),
            divider = c("#0B141C"),
            accentSecondary = c("#A78BFA"),
            gradientStart = c("#34D399"), gradientEnd = c("#A78BFA"),
        ),

        "cyberpunk_dark" to ChatColors(
            bg = c("#0A0014"), toolbarBg = c("#10001F"),
            userBubble = c("#EC4899"), userText = c("#FFFFFF"),
            aiBubble = c("#170029"), aiBubbleBorder = c("#260040"),
            aiText = c("#F0D8FF"), avatarBg = c("#BE185D"),
            inputBorder = c("#260040"), sendColor = c("#22D3EE"),
            toolOk = c("#22D3EE"), toolDefault = c("#4C1D70"),
            divider = c("#10001F"),
            accentSecondary = c("#EC4899"),
            gradientStart = c("#EC4899"), gradientEnd = c("#22D3EE"),
        ),

        "ocean_dark" to ChatColors(
            bg = c("#04141C"), toolbarBg = c("#08222E"),
            userBubble = c("#0891B2"), userText = c("#ECFEFF"),
            aiBubble = c("#0B2A38"), aiBubbleBorder = c("#143E50"),
            aiText = c("#C8E8F0"), avatarBg = c("#0E7490"),
            inputBorder = c("#143E50"), sendColor = c("#22D3EE"),
            toolOk = c("#22D3EE"), toolDefault = c("#1E5468"),
            divider = c("#08222E"),
            accentSecondary = c("#67E8F9"),
            gradientStart = c("#22D3EE"), gradientEnd = c("#0891B2"),
        ),

        "sunset_dark" to ChatColors(
            bg = c("#150B0E"), toolbarBg = c("#1F1218"),
            userBubble = c("#F43F5E"), userText = c("#FFF1F2"),
            aiBubble = c("#241620"), aiBubbleBorder = c("#3A1F2E"),
            aiText = c("#F0D8DC"), avatarBg = c("#BE123C"),
            inputBorder = c("#3A1F2E"), sendColor = c("#FB7185"),
            toolOk = c("#FB7185"), toolDefault = c("#5E2A3D"),
            divider = c("#1F1218"),
            accentSecondary = c("#FBBF24"),
            gradientStart = c("#FB7185"), gradientEnd = c("#FBBF24"),
        ),

        "mono_dark" to ChatColors(
            bg = c("#0E0E0E"), toolbarBg = c("#161616"),
            userBubble = c("#FFFFFF"), userText = c("#0E0E0E"),
            aiBubble = c("#1C1C1C"), aiBubbleBorder = c("#2A2A2A"),
            aiText = c("#E0E0E0"), avatarBg = c("#FFFFFF"),
            inputBorder = c("#2A2A2A"), sendColor = c("#FFFFFF"),
            toolOk = c("#FFFFFF"), toolDefault = c("#3D3D3D"),
            divider = c("#161616"),
            accentSecondary = c("#A3A3A3"),
            gradientStart = c("#FFFFFF"), gradientEnd = c("#737373"),
        ),

        "arctic_light" to ChatColors(
            bg = c("#F0F8FF"), toolbarBg = c("#E4F2FC"),
            userBubble = c("#0066CC"), userText = c("#FFFFFF"),
            aiBubble = c("#E0EEF8"), aiBubbleBorder = c("#B8D4EC"),
            aiText = c("#1A3050"), avatarBg = c("#0066CC"),
            inputBorder = c("#B8D4EC"), sendColor = c("#0066CC"),
            toolOk = c("#0099BB"), toolDefault = c("#7A9AB8"),
            divider = c("#CCE0F0"),
            accentSecondary = c("#22D3EE"),
            gradientStart = c("#22D3EE"), gradientEnd = c("#0066CC"),
        ),

        "slate_light" to ChatColors(
            bg = c("#F1F2F6"), toolbarBg = c("#E8EAF0"),
            userBubble = c("#4338CA"), userText = c("#FFFFFF"),
            aiBubble = c("#E4E6EE"), aiBubbleBorder = c("#C8CCE0"),
            aiText = c("#1E2040"), avatarBg = c("#4338CA"),
            inputBorder = c("#C8CCE0"), sendColor = c("#4338CA"),
            toolOk = c("#4338CA"), toolDefault = c("#8890B0"),
            divider = c("#D4D8E8"),
            accentSecondary = c("#A78BFA"),
            gradientStart = c("#A78BFA"), gradientEnd = c("#4338CA"),
        ),

        "cream_light" to ChatColors(
            bg = c("#FFFBF5"), toolbarBg = c("#FFF5E8"),
            userBubble = c("#BE185D"), userText = c("#FFFFFF"),
            aiBubble = c("#FFEAD6"), aiBubbleBorder = c("#F0D5B8"),
            aiText = c("#3A1B0E"), avatarBg = c("#BE185D"),
            inputBorder = c("#F0D5B8"), sendColor = c("#BE185D"),
            toolOk = c("#BE185D"), toolDefault = c("#A8907A"),
            divider = c("#F0E0CC"),
            accentSecondary = c("#FB7185"),
            gradientStart = c("#FB7185"), gradientEnd = c("#BE185D"),
        ),
    )

    val allThemes: List<Triple<String, String, String>> = listOf(
        Triple("blackclaw_dark", "BlackClaw",  "Cyan eléctrico sobre carbón"),
        Triple("midnight_dark",  "Midnight",   "Violeta sobre azul profundo"),
        Triple("aurora_dark",    "Aurora",     "Verde boreal y violeta"),
        Triple("cyberpunk_dark", "Cyberpunk",  "Rosa neón y cyan"),
        Triple("ocean_dark",     "Ocean",      "Turquesa sobre azul abisal"),
        Triple("sunset_dark",    "Sunset",     "Coral y ámbar al anochecer"),
        Triple("mono_dark",      "Mono",       "Blanco y negro elegante"),
        Triple("arctic_light",   "Arctic",     "Blanco con cyan limpio"),
        Triple("slate_light",    "Slate",      "Pizarra con índigo cálido"),
        Triple("cream_light",    "Cream",      "Crema con magenta vintage"),
    )

    fun getColors(): ChatColors {
        val id = KVUtils.getString("THEME_ID", "blackclaw_dark")
        return themes[id] ?: themes["blackclaw_dark"]!!
    }

    fun isDark(): Boolean {
        val id = KVUtils.getString("THEME_ID", "blackclaw_dark")
        return id.endsWith("_dark") || id == "mono_dark"
    }

    fun ChatColors.toComposeColors(): BlackClawColors {
        val dark = isDark()
        return BlackClawColors(
            background    = androidx.compose.ui.graphics.Color(bg),
            surface       = androidx.compose.ui.graphics.Color(toolbarBg),
            userBubble    = androidx.compose.ui.graphics.Color(userBubble),
            userText      = androidx.compose.ui.graphics.Color(userText),
            aiBubble      = androidx.compose.ui.graphics.Color(aiBubble),
            aiBubbleBorder= androidx.compose.ui.graphics.Color(aiBubbleBorder),
            aiText        = androidx.compose.ui.graphics.Color(aiText),
            avatar        = androidx.compose.ui.graphics.Color(avatarBg),
            accent        = androidx.compose.ui.graphics.Color(sendColor),
            textPrimary   = if (dark) androidx.compose.ui.graphics.Color(0xFFE6E8F0.toInt())
                            else      androidx.compose.ui.graphics.Color(0xFF1A2040.toInt()),
            textSecondary = if (dark) androidx.compose.ui.graphics.Color(0xFF8890A8.toInt())
                            else      androidx.compose.ui.graphics.Color(0xFF5A6080.toInt()),
            textTertiary  = if (dark) androidx.compose.ui.graphics.Color(0xFF4A4A60.toInt())
                            else      androidx.compose.ui.graphics.Color(0xFF9098B8.toInt()),
            divider       = androidx.compose.ui.graphics.Color(divider),
            inputBorder   = androidx.compose.ui.graphics.Color(inputBorder),
        )
    }

    private fun c(hex: String) = Color.parseColor(hex)
}
