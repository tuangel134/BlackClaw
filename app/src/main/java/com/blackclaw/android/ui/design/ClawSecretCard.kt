package com.blackclaw.android.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Displays a pairing/access code the user must copy somewhere else.
 *
 * ## Why this is one shared component
 *
 * Three separate security features added in this pass all rely on the same idea: a
 * secret is generated on the device and shown on screen, and possession of it proves
 * the holder had physical access. Channel pairing, the LAN config server access code
 * and the automation token all need the identical affordance — big legible code, copy
 * button, regenerate, and a plain statement of what it is for.
 *
 * Building it once matters beyond DRY: if one of them rendered the code in a
 * proportional font, or omitted the regenerate action, that feature would be the one
 * users get wrong. A monospace font is not decoration here — it is what makes `0`/`O`
 * and `1`/`l` distinguishable while retyping into a chat app.
 *
 * @param code already formatted for display (grouped with dashes). Pass empty to
 *   render the [emptyHint] state instead.
 * @param onRegenerate null hides the regenerate action, for codes that must not be
 *   rotated from this screen.
 */
@Composable
fun ClawSecretCard(
    title: String,
    explanation: String,
    code: String,
    modifier: Modifier = Modifier,
    accent: ClawAccent = ClawPalette.Guard,
    emptyHint: String = "No hay código activo.",
    copyLabel: String = "Código copiado",
    onRegenerate: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val haptic = rememberHapticConfirm()
    var justCopied by remember { mutableStateOf(false) }

    // Confirmation collapses on its own. A Toast would be covered by the keyboard on
    // this screen, and a permanent "copied" label would go stale and start lying.
    LaunchedEffect(justCopied) {
        if (justCopied) {
            delay(1800)
            justCopied = false
        }
    }

    ClawCard(modifier = modifier.fillMaxWidth(), accent = accent, elevated = true) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title.uppercase(),
                fontSize = 11.sp,
                letterSpacing = 1.3.sp,
                fontWeight = FontWeight.Bold,
                color = accent.base,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                explanation,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = ClawTextColors.Secondary,
            )
            Spacer(Modifier.height(14.dp))

            if (code.isBlank()) {
                Text(emptyHint, fontSize = 13.sp, color = ClawTextColors.Tertiary)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(accent.base.copy(alpha = 0.13f))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Text(
                            code,
                            // Monospace so the user can tell 0 from O while retyping.
                            fontFamily = FontFamily.Monospace,
                            fontSize = 19.sp,
                            letterSpacing = 2.sp,
                            fontWeight = FontWeight.Bold,
                            color = ClawTextColors.Primary,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    ClawIconAction(
                        icon = Icons.Default.ContentCopy,
                        contentDescription = "Copiar código",
                        accent = accent,
                    ) {
                        haptic()
                        runCatching {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(
                                android.content.ClipData.newPlainText("blackclaw_code", code)
                            )
                        }
                        justCopied = true
                    }
                    if (onRegenerate != null) {
                        Spacer(Modifier.width(8.dp))
                        ClawIconAction(
                            icon = Icons.Default.Refresh,
                            contentDescription = "Generar código nuevo",
                            accent = accent,
                        ) { haptic(); onRegenerate() }
                    }
                }
            }

            AnimatedVisibility(visible = justCopied, enter = fadeIn(), exit = fadeOut()) {
                Column {
                    Spacer(Modifier.height(9.dp))
                    Text(copyLabel, fontSize = 12.sp, color = ClawPalette.Finance.base,
                        fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** Square tappable icon with press feedback, sized to the 44 dp minimum target. */
@Composable
fun ClawIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    accent: ClawAccent = ClawPalette.Signature,
    onClick: () -> Unit,
) {
    val interaction = rememberClawInteraction()
    Box(
        Modifier
            .size(44.dp)
            .clawPressScale(interaction)
            .clip(RoundedCornerShape(12.dp))
            .background(accent.base.copy(alpha = 0.15f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = accent.base, modifier = Modifier.size(19.dp))
    }
}

/**
 * Shared text colours.
 *
 * The screens each defined their own near-identical greys, so body text drifted
 * between four different values. Named by role so a contrast fix lands once.
 */
object ClawTextColors {
    val Primary = androidx.compose.ui.graphics.Color(0xFFE6EAF7)
    val Secondary = androidx.compose.ui.graphics.Color(0xFF8A90AE)
    val Tertiary = androidx.compose.ui.graphics.Color(0xFF6A7090)
}
