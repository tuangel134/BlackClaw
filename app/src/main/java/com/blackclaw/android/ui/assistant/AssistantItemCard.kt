package com.blackclaw.android.ui.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blackclaw.android.assistant.AssistantItem
import com.blackclaw.android.assistant.AssistantItemType
import com.blackclaw.android.ui.assistant.AssistantCardModel.Urgency
import com.blackclaw.android.ui.design.ClawAnimation
import com.blackclaw.android.ui.design.ClawCard
import com.blackclaw.android.ui.design.ClawMotion
import com.blackclaw.android.ui.design.ClawPalette
import com.blackclaw.android.ui.design.rememberHapticConfirm

/**
 * A single assistant item, rendered as a rich card.
 *
 * Layout intent: leading affordance (checkbox or category badge), then title and
 * body, then a metadata row of small chips, with the destructive action pushed to
 * the trailing edge where it cannot be hit by accident while scanning.
 *
 * Overdue items pulse. That is the only animation here that runs indefinitely, and
 * it exists because an overdue reminder is the one thing in this list that needs to
 * survive a glance — a static red tint gets lost among six other coloured
 * categories. It respects reduced motion.
 */
@Composable
fun AssistantItemCard(
    item: AssistantItem,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val data = remember(item) { AssistantCardModel.of(item) }
    val accent = remember(item.type) {
        ClawPalette.forCategory(AssistantCardModel.accentName(item.type))
    }
    val reduceMotion = ClawAnimation.reduceMotion()
    val confirmHaptic = rememberHapticConfirm()

    // Overdue emphasis: a slow breath on the border, not on the whole card, so text
    // never changes contrast while the user is reading it.
    val pulseAlpha = if (data.urgency == Urgency.OVERDUE && !reduceMotion) {
        val transition = rememberInfiniteTransition(label = "overduePulse")
        val v by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.9f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = ClawMotion.EaseInOut),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulseAlpha",
        )
        v
    } else if (data.urgency == Urgency.OVERDUE) 0.7f else 0f

    val borderColor by animateColorAsState(
        targetValue = when {
            data.urgency == Urgency.OVERDUE -> ClawPalette.Danger.base.copy(alpha = pulseAlpha)
            data.isSuggestion -> ClawPalette.Signature.outline
            data.urgency == Urgency.DONE -> ClawPalette.Elevation.Outline.copy(alpha = 0.4f)
            else -> accent.base.copy(alpha = 0.22f)
        },
        animationSpec = ClawMotion.standardTween(),
        label = "cardBorder",
    )

    // Completed items recede rather than disappear, so undoing stays discoverable.
    val contentAlpha by animateFloatAsState(
        targetValue = if (data.urgency == Urgency.DONE) 0.45f else 1f,
        animationSpec = ClawMotion.standardTween(),
        label = "doneAlpha",
    )

    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (data.isSuggestion) ClawPalette.Signature.wash
                else ClawPalette.Elevation.Level1
            )
            .border(1.dp, borderColor, shape),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistantLeading(
                data = data,
                type = item.type,
                accent = accent,
                onToggle = { confirmHaptic(); onToggle() },
            )
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f).alpha(contentAlpha)) {
                Text(
                    data.title,
                    fontSize = 15.sp,
                    color = Color(0xFFE6EAF7),
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 20.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (data.urgency == Urgency.DONE) TextDecoration.LineThrough
                        else null,
                )
                if (data.body.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        data.body,
                        fontSize = 12.sp,
                        color = Color(0xFF8A90AE),
                        lineHeight = 17.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                AssistantMetaRow(data, accent, item.type)
            }
            if (data.amountLabel.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                AmountBadge(data)
            }
            // Drafts exist to be pasted somewhere else, so copying is their primary
            // action and it stays alongside delete.
            if (data.isDraft) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                IconButton(
                    onClick = {
                        confirmHaptic()
                        runCatching {
                            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(
                                android.content.ClipData.newPlainText("draft", item.body)
                            )
                            android.widget.Toast.makeText(
                                ctx, "Borrador copiado", android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        "Copiar borrador",
                        tint = ClawPalette.Signature.base,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            IconButton(onClick = { confirmHaptic(); onDelete() }, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete,
                    "Eliminar ${data.title}",
                    tint = Color(0xFF565C7A),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Checkbox for actionable types, category badge otherwise. */
@Composable
private fun AssistantLeading(
    data: AssistantCardModel.CardData,
    type: AssistantItemType,
    accent: com.blackclaw.android.ui.design.ClawAccent,
    onToggle: () -> Unit,
) {
    if (!data.checkable) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(accent.base.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Text(AssistantCardModel.emoji(type), fontSize = 20.sp) }
        return
    }
    val checked = data.urgency == Urgency.DONE
    // Springy fill on check: the one place a visible bounce is warranted, because
    // completing something is the small reward this screen exists to give.
    val fill by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = ClawMotion.bouncySpring(),
        label = "checkFill",
    )
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(accent.base.copy(alpha = fill))
            .border(2.dp, accent.base.copy(alpha = if (checked) 1f else 0.55f), CircleShape)
            .clickable(onClickLabel = if (checked) "Marcar pendiente" else "Marcar hecho") {
                onToggle()
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(visible = checked, enter = fadeIn(), exit = fadeOut()) {
            Icon(
                Icons.Default.Check, null,
                tint = accent.onAccent,
                modifier = Modifier.size(17.dp).scale(fill.coerceAtLeast(0.6f)),
            )
        }
    }
}

/** Small chips carrying time, repeat, and capability markers. */
@Composable
private fun AssistantMetaRow(
    data: AssistantCardModel.CardData,
    accent: com.blackclaw.android.ui.design.ClawAccent,
    type: AssistantItemType,
) {
    val hasAny = data.relativeTime.isNotEmpty() || data.repeats || data.hasGeofence ||
        data.hasChallenge || data.ringsLoudly || data.fromAi || data.isDraft
    if (!hasAny) return

    Spacer(Modifier.height(8.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (data.relativeTime.isNotEmpty()) {
            val timeTint = when (data.urgency) {
                Urgency.OVERDUE -> ClawPalette.Danger.base
                Urgency.IMMINENT -> ClawPalette.Alarm.base
                else -> accent.base
            }
            MetaChip(
                text = data.relativeTime,
                tint = timeTint,
                // Overdue is stated in words too, not only in colour: colour alone
                // is invisible to a red-green colourblind user and to a screen reader.
                prefix = if (data.urgency == Urgency.OVERDUE) "Vencido · " else null,
            )
        }
        if (data.repeats) MetaChip(data.repeatLabel, accent.base, icon = Icons.Default.Refresh)
        if (data.hasGeofence) MetaChip("Al llegar", ClawPalette.Note.base, icon = Icons.Default.LocationOn)
        if (data.hasChallenge) MetaChip("Reto", ClawPalette.Guard.base, icon = Icons.Default.Lock)
        if (data.ringsLoudly) {
            MetaChip("Suena", ClawPalette.Alarm.base, icon = Icons.Default.NotificationsActive)
        }
        if (data.isDraft) MetaChip("Borrador", Color(0xFF8A90AE))
        if (data.fromAi && !data.isSuggestion) MetaChip("IA", ClawPalette.Signature.base)
    }
}

@Composable
private fun MetaChip(
    text: String,
    tint: Color,
    icon: ImageVector? = null,
    prefix: String? = null,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(3.dp))
        }
        Text(
            (prefix ?: "") + text,
            fontSize = 10.5.sp,
            color = tint,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Signed money badge for finance rows. */
@Composable
private fun AmountBadge(data: AssistantCardModel.CardData) {
    val accent = if (data.isIncome) ClawPalette.Finance else ClawPalette.Danger
    Column(horizontalAlignment = Alignment.End) {
        Text(
            (if (data.isIncome) "+" else "−") + data.amountLabel,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = accent.base,
        )
        Text(
            if (data.isIncome) "ingreso" else "gasto",
            fontSize = 9.5.sp,
            color = Color(0xFF6A7090),
        )
    }
}
