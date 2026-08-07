package com.blackclaw.android.ui.cards

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blackclaw.android.cards.MapTiles
import com.blackclaw.android.ui.design.ClawAnimation
import com.blackclaw.android.ui.design.ClawMotion
import com.blackclaw.android.ui.design.ClawShimmer

/**
 * A real map tile with a pin on the point.
 *
 * ## Why the pin needs the tile offset
 *
 * Knowing which tile contains a coordinate is not enough to mark it: the point can sit
 * anywhere inside the image. [MapTiles.TileRef] carries the fractional position, so the
 * pin lands on the place instead of in the middle of the picture — which would quietly
 * be wrong by up to a couple of streets at this zoom.
 *
 * ## Attribution is a condition of use
 *
 * OpenStreetMap data is free to use and requires credit. The overlay is not decoration,
 * it is the licence being honoured, so it is drawn inside this composable where it cannot
 * be forgotten by a caller.
 *
 * ## Failure is stated, not hidden
 *
 * With no tile — offline, blocked, corrupt — the map area shows the coordinates and says
 * the map is unavailable. A blank grey box reads as a broken app; a coordinate readout
 * reads as an honest fallback that is still useful.
 */
@Composable
fun MiniMap(
    lat: Double,
    lon: Double,
    skin: AssistCardSkin,
    modifier: Modifier = Modifier,
    height: Dp = 132.dp,
    zoom: Int = MapTiles.DEFAULT_ZOOM,
) {
    val tile = remember(lat, lon, zoom) { MapTiles.tileFor(lat, lon, zoom) }
    val url = remember(tile) { MapTiles.url(tile) }
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        val loaded = TileImageLoader.load(url)
        image = loaded
        failed = loaded == null
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            .background(skin.surfaceRaised),
    ) {
        val bitmap = image
        when {
            bitmap != null -> {
                // Fades in rather than popping: the tile arrives after the rest of the
                // card, and a hard swap draws the eye back to something already read.
                val appear by animateFloatAsState(
                    targetValue = 1f,
                    animationSpec = ClawMotion.standardTween(),
                    label = "tileFade",
                )
                androidx.compose.foundation.Image(
                    bitmap = bitmap,
                    contentDescription = "Mapa de la ubicación",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(appear),
                )
                MapPin(tile.offsetX, tile.offsetY, skin.accent)
                Text(
                    "© OpenStreetMap",
                    fontSize = 8.5.sp,
                    color = Color(0xCCFFFFFF),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0x66000000))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }

            failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Mapa no disponible · %.4f, %.4f".format(lat, lon),
                    fontSize = 11.sp,
                    color = skin.textTertiary,
                )
            }

            else -> ClawShimmer(Modifier.fillMaxSize(), cornerRadius = 14.dp)
        }
    }
}

/**
 * The marker, drawn at a fraction of the tile rather than at its centre.
 *
 * A slow halo pulses around it so the eye finds the point on a busy map. It is purely
 * decorative, so it checks reduced motion.
 */
@Composable
private fun MapPin(offsetX: Float, offsetY: Float, accent: Color) {
    val reduce = ClawAnimation.reduceMotion()
    val pulse = if (reduce) 1f else {
        val transition = rememberInfiniteTransition(label = "pinPulse")
        val v by transition.animateFloat(
            initialValue = 1f,
            targetValue = 2.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(1800, easing = ClawMotion.EaseInOut),
                repeatMode = androidx.compose.animation.core.RepeatMode.Restart,
            ),
            label = "pinPulseValue",
        )
        v
    }

    Canvas(Modifier.fillMaxSize()) {
        val center = Offset(size.width * offsetX, size.height * offsetY)
        val base = 7f
        if (!reduce) {
            // Fades as it grows so it reads as a ripple, not as a growing blob.
            drawCircle(
                color = accent.copy(alpha = (0.45f * (2.2f - pulse) / 1.2f).coerceIn(0f, 0.45f)),
                radius = base * pulse * 1.6f,
                center = center,
                style = Stroke(width = 2.5f),
            )
        }
        drawCircle(color = Color(0x55000000), radius = base + 3f, center = center)
        drawCircle(color = accent, radius = base, center = center)
        drawCircle(color = Color.White, radius = base * 0.38f, center = center)
    }
}

/** Small "open in maps" affordance shared by the cards that have coordinates. */
@Composable
fun OpenInMapsRow(skin: AssistCardSkin, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(skin.accent.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.LocationOn,
            contentDescription = null,
            tint = skin.accent,
            modifier = Modifier.height(13.dp),
        )
        androidx.compose.foundation.layout.Spacer(Modifier.padding(horizontal = 2.dp))
        Text("Ver en el mapa", fontSize = 11.5.sp, color = skin.accent)
    }
}
