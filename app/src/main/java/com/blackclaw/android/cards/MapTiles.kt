package com.blackclaw.android.cards

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.tan

/**
 * Web Mercator tile arithmetic for the mini map.
 *
 * ## Why tiles instead of a map SDK
 *
 * The project has no map SDK and stays deliberately free of Play Services, and it has no
 * Compose-native image loader either. A single Web Mercator tile is a real, plain PNG at
 * a plain URL, so one HTTP GET and a bitmap decode produce an actual map with no new
 * dependency and nothing to initialise.
 *
 * The alternative — drawing a stylised "map" on a Canvas — was rejected on purpose. A
 * marker floating on an invented grid looks like cartography without being cartography,
 * and a map that cannot be trusted is worse than a coordinate readout.
 *
 * ## Why the offset matters
 *
 * Knowing which tile contains a point is not enough to draw the pin: the point can sit
 * anywhere inside it. [TileRef.offsetX] and [TileRef.offsetY] give the fractional
 * position, so the pin lands on the place rather than in the middle of the image.
 *
 * Pure maths, no Android, so the projection is unit-testable — which is the whole reason
 * it lives away from the Composable that draws it.
 */
object MapTiles {

    /** Public OSM tiles stop at 19; asking beyond that returns errors, not detail. */
    const val MAX_ZOOM = 19

    /**
     * Mercator cannot represent the poles: latitude is clamped to this. Past it the
     * projection runs to infinity and the tile index overflows.
     */
    const val MAX_LATITUDE = 85.05112878

    /** Close enough to read a street, wide enough to place it in a neighbourhood. */
    const val DEFAULT_ZOOM = 15

    data class TileRef(
        val zoom: Int,
        val x: Int,
        val y: Int,
        /** Where the point sits inside the tile, 0..1 from the left edge. */
        val offsetX: Float,
        /** Where the point sits inside the tile, 0..1 from the top edge. */
        val offsetY: Float,
    )

    fun tileFor(lat: Double, lon: Double, zoom: Int = DEFAULT_ZOOM): TileRef {
        val z = zoom.coerceIn(0, MAX_ZOOM)
        val n = 2.0.pow(z)
        val safeLat = lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val safeLon = wrapLongitude(lon)

        val xf = (safeLon + 180.0) / 360.0 * n
        // asinh(tan(lat)) is the Mercator y, and is the numerically stable way to write
        // the usual ln(tan + sec) form — the latter loses precision near the equator.
        val yf = (1.0 - asinh(tan(Math.toRadians(safeLat))) / PI) / 2.0 * n

        val maxIndex = (n - 1).toInt()
        val x = floor(xf).toInt().coerceIn(0, maxIndex)
        val y = floor(yf).toInt().coerceIn(0, maxIndex)
        return TileRef(
            zoom = z,
            x = x,
            y = y,
            offsetX = (xf - x).toFloat().coerceIn(0f, 1f),
            offsetY = (yf - y).toFloat().coerceIn(0f, 1f),
        )
    }

    /**
     * Brings any longitude into [-180, 180).
     *
     * Coordinates arriving from a geocoder or an accumulated pan can land outside the
     * range; wrapping is correct for a cylinder, whereas clamping would pin everything
     * past the antimeridian to the same edge.
     */
    fun wrapLongitude(lon: Double): Double {
        if (!lon.isFinite()) return 0.0
        if (lon >= -180.0 && lon < 180.0) return lon
        val wrapped = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        // The modulo can land exactly on +180 for inputs that are multiples of 360.
        return if (abs(wrapped - 180.0) < 1e-9) -180.0 else wrapped
    }

    /**
     * URL of one tile image.
     *
     * OpenStreetMap's public tile service requires an identifying User-Agent and only
     * tolerates light, non-bulk use — which a card drawn when the user asks about one
     * place is. Anything that pre-fetches or scans tiles needs its own tile source.
     */
    fun url(tile: TileRef): String =
        "https://tile.openstreetmap.org/${tile.zoom}/${tile.x}/${tile.y}.png"

    /** Deep link the map card opens on tap. Handled by any installed map app. */
    fun geoUri(lat: Double, lon: Double, label: String): String {
        val safeLat = lat.coerceIn(-90.0, 90.0)
        val safeLon = wrapLongitude(lon)
        val query = "$safeLat,$safeLon"
        // The coordinate is repeated as ?q= so apps that ignore the path still get it,
        // and the label rides along for the ones that show it.
        val encoded = java.net.URLEncoder.encode("$query($label)", "UTF-8")
        return "geo:$query?q=$encoded"
    }
}
