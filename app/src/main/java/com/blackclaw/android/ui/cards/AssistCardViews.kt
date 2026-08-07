package com.blackclaw.android.ui.cards

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blackclaw.android.cards.AssistCard
import com.blackclaw.android.cards.MapTiles
import com.blackclaw.android.ui.design.ClawAnimation
import com.blackclaw.android.ui.design.ClawMotion
import com.blackclaw.android.ui.design.ClawReveal
import com.blackclaw.android.utils.XLog
import kotlin.math.roundToInt

/** Cards never grow past this, so a long title cannot stretch the transcript. */
private val CARD_MAX_WIDTH = 360.dp

/**
 * Draws a run of cards, staggered.
 *
 * The stagger is what makes several results read as one answer arriving rather than as a
 * list appearing fully formed.
 */
@Composable
fun AssistCardList(
    cards: List<AssistCard>,
    skin: AssistCardSkin = AssistCardSkin.AssistPanel,
    modifier: Modifier = Modifier,
) {
    if (cards.isEmpty()) return
    val reduce = ClawAnimation.reduceMotion()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cards.forEachIndexed { index, card ->
            ClawReveal(index = index, enabled = !reduce) {
                when (card) {
                    is AssistCard.Weather -> WeatherCard(card, skin)
                    is AssistCard.Place -> PlaceCard(card, skin)
                    is AssistCard.Offer -> OfferCard(card, skin)
                    is AssistCard.Link -> LinkCard(card, skin)
                    is AssistCard.Summary -> SummaryCard(card, skin)
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(card: AssistCard.Summary, skin: AssistCardSkin) {
    val icon = when (card.kind) {
        com.blackclaw.android.cards.SummaryKind.BATTERY -> Icons.Default.BatteryFull
        com.blackclaw.android.cards.SummaryKind.MUSIC -> Icons.Default.MusicNote
        com.blackclaw.android.cards.SummaryKind.TIMER -> Icons.Default.Alarm
        com.blackclaw.android.cards.SummaryKind.SONG -> Icons.Default.MusicNote
    }
    val tint = skin.accent
    Column(
        Modifier
            .widthIn(max = CARD_MAX_WIDTH)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.18f), skin.surface)))
            .border(1.dp, tint.copy(alpha = 0.30f), RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(card.label, color = skin.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(card.value, color = skin.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (card.detail.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(card.detail, color = skin.textSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ── Weather ───────────────────────────────────────────────────────────────────

/**
 * Current conditions, built from the values the weather API returned.
 *
 * The temperature counts up on appear. It is the one number the user asked for, and
 * animating it is what separates a card from a paragraph — it also gives the eye somewhere
 * to land while the rest of the card settles.
 *
 * The icon comes from the WMO code, never from matching words in [AssistCard.Weather
 * .condition]: the wording is already localised by the tool that produced it, and a second
 * interpretation here would drift from the first.
 */
@Composable
private fun WeatherCard(card: AssistCard.Weather, skin: AssistCardSkin) {
    val context = LocalContext.current
    val reduce = ClawAnimation.reduceMotion()
    var shown by remember(card) { mutableStateOf(reduce) }
    LaunchedEffect(card) { shown = true }
    val temp by animateFloatAsState(
        targetValue = if (shown) card.tempC.toFloat() else 0f,
        animationSpec = ClawMotion.enterTween(),
        label = "tempCount",
    )

    val (icon, tint) = weatherVisual(card.conditionCode, card.isDay)
    val hasMap = card.lat != null && card.lon != null

    Column(
        Modifier
            .widthIn(max = CARD_MAX_WIDTH)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    listOf(tint.copy(alpha = 0.22f), skin.surface),
                )
            )
            .border(1.dp, tint.copy(alpha = 0.30f), RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(tint.copy(alpha = 0.20f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = tint, modifier = Modifier.size(25.dp)) }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    card.place,
                    fontSize = 13.sp,
                    color = skin.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    card.condition.ifBlank { "—" },
                    fontSize = 14.5.sp,
                    color = skin.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "${temp.roundToInt()}°",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = skin.textPrimary,
            )
        }

        val chips = buildList {
            card.feelsLikeC?.let { add(Triple("Sensación ${it.roundToInt()}°", null, skin.textSecondary)) }
            card.humidityPct?.let { add(Triple("$it%", Icons.Default.WaterDrop, skin.textSecondary)) }
            card.windKph?.let { add(Triple("${it.roundToInt()} km/h", Icons.Default.Air, skin.textSecondary)) }
            card.rainChancePct?.takeIf { it > 0 }?.let {
                add(Triple("Lluvia $it%", Icons.Default.Grain, tint))
            }
        }
        if (chips.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { (text, chipIcon, chipTint) ->
                    CardChip(text, chipTint, chipIcon)
                }
            }
        }

        if (hasMap) {
            Spacer(Modifier.height(12.dp))
            // Stated as a control instead of making the whole card secretly tappable:
            // a card that navigates away on any touch is a card the user stops touching.
            Box(Modifier.clickable { openMap(context, card.lat!!, card.lon!!, card.place) }) {
                OpenInMapsRow(skin)
            }
        }
    }
}

/**
 * WMO condition code to icon and tint.
 *
 * Grouped by what the sky is doing, because the code list distinguishes cases that look
 * identical at this size — light and moderate drizzle do not need separate icons.
 */
private fun weatherVisual(code: Int, isDay: Boolean): Pair<ImageVector, Color> = when (code) {
    0, 1 -> (if (isDay) Icons.Default.WbSunny else Icons.Default.NightsStay) to
        (if (isDay) Color(0xFFFFD67A) else Color(0xFFC0B59D))
    2, 3 -> Icons.Default.Cloud to Color(0xFFC0B59D)
    45, 48 -> Icons.Default.Cloud to Color(0xFFA89A80)
    in 51..67, in 80..82 -> Icons.Default.Grain to Color(0xFFD3B16A)
    in 71..77, 85, 86 -> Icons.Default.AcUnit to Color(0xFFE7D9B5)
    95, 96, 99 -> Icons.Default.Thunderstorm to Color(0xFFFFD67A)
    else -> Icons.Default.Cloud to Color(0xFFC0B59D)
}

// ── Place ─────────────────────────────────────────────────────────────────────

/** A point on the map. The whole card opens the map app, because that is its only action. */
@Composable
private fun PlaceCard(card: AssistCard.Place, skin: AssistCardSkin) {
    val context = LocalContext.current
    Column(
        Modifier
            .widthIn(max = CARD_MAX_WIDTH)
            .clip(RoundedCornerShape(20.dp))
            .background(skin.surface)
            .border(1.dp, skin.outline, RoundedCornerShape(20.dp))
            .clickable { openMap(context, card.lat, card.lon, card.name) }
            .padding(12.dp),
    ) {
        MiniMap(lat = card.lat, lon = card.lon, skin = skin)
        Spacer(Modifier.height(11.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.LocationOn, null,
                tint = skin.accent, modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    card.name,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = skin.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (card.detail.isNotBlank()) {
                    Text(
                        card.detail,
                        fontSize = 11.5.sp,
                        color = skin.textTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                Icons.Outlined.OpenInNew, "Abrir en el mapa",
                tint = skin.textTertiary, modifier = Modifier.size(15.dp),
            )
        }
    }
}

// ── Offer ─────────────────────────────────────────────────────────────────────

/**
 * A search result that carries a price.
 *
 * The price is the largest thing on the card because it is why this variant exists, and it
 * is printed exactly as the merchant wrote it — see [AssistCard.Offer.priceLabel] for why
 * reformatting it would be a bug rather than a polish.
 */
@Composable
private fun OfferCard(card: AssistCard.Offer, skin: AssistCardSkin) {
    val context = LocalContext.current
    Column(
        Modifier
            .widthIn(max = CARD_MAX_WIDTH)
            .clip(RoundedCornerShape(18.dp))
            .background(skin.surface)
            .border(1.dp, skin.price.copy(alpha = 0.28f), RoundedCornerShape(18.dp))
            .clickable { openUrl(context, card.url) }
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    card.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = skin.textPrimary,
                    lineHeight = 19.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    card.priceLabel,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = skin.price,
                )
            }
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.Outlined.OpenInNew, "Abrir la oferta",
                tint = skin.textTertiary, modifier = Modifier.size(16.dp),
            )
        }
        if (card.snippet.isNotBlank()) {
            Spacer(Modifier.height(7.dp))
            Text(
                card.snippet,
                fontSize = 11.5.sp,
                color = skin.textSecondary,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val merchant = card.merchant.ifBlank { hostOf(card.url) }
        if (merchant.isNotBlank()) {
            Spacer(Modifier.height(9.dp))
            CardChip(merchant, skin.textTertiary, Icons.Outlined.Storefront)
        }
    }
}

// ── Link ──────────────────────────────────────────────────────────────────────

/** A search result with no price: the plain sibling of the offer card. */
@Composable
private fun LinkCard(card: AssistCard.Link, skin: AssistCardSkin) {
    val context = LocalContext.current
    Column(
        Modifier
            .widthIn(max = CARD_MAX_WIDTH)
            .clip(RoundedCornerShape(16.dp))
            .background(skin.surface)
            .border(1.dp, skin.outline, RoundedCornerShape(16.dp))
            .clickable { openUrl(context, card.url) }
            .padding(13.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                card.title,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = skin.textPrimary,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(9.dp))
            Icon(
                Icons.Outlined.OpenInNew, "Abrir el enlace",
                tint = skin.textTertiary, modifier = Modifier.size(15.dp),
            )
        }
        if (card.snippet.isNotBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(
                card.snippet,
                fontSize = 11.5.sp,
                color = skin.textSecondary,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val host = hostOf(card.url)
        if (host.isNotBlank()) {
            Spacer(Modifier.height(7.dp))
            Text(host, fontSize = 11.sp, color = skin.accent)
        }
    }
}

// ── Shared pieces ─────────────────────────────────────────────────────────────

@Composable
private fun CardChip(text: String, tint: Color, icon: ImageVector?) {
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
        Text(text, fontSize = 10.5.sp, color = tint, fontWeight = FontWeight.SemiBold)
    }
}

/** Host without the `www.`, which is the part of a URL worth showing. */
private fun hostOf(url: String): String = runCatching {
    Uri.parse(url).host?.removePrefix("www.").orEmpty()
}.getOrDefault("")

/**
 * Opens a URL, swallowing the failure.
 *
 * A card that cannot find a browser should do nothing visible rather than crash the
 * assistant panel — the answer text is still on screen either way.
 */
private fun openUrl(context: android.content.Context, url: String) {
    if (url.isBlank()) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { XLog.d("AssistCards", "openUrl failed: ${it.javaClass.simpleName}") }
}

/** Hands the coordinate to whatever map app is installed. */
private fun openMap(context: android.content.Context, lat: Double, lon: Double, label: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(MapTiles.geoUri(lat, lon, label)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { XLog.d("AssistCards", "openMap failed: ${it.javaClass.simpleName}") }
}
