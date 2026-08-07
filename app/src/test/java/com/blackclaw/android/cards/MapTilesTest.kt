package com.blackclaw.android.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The Web Mercator projection behind the mini map.
 *
 * This is tested rather than eyeballed because a projection error does not look like a
 * bug: it looks like a map of somewhere else.
 */
class MapTilesTest {

    private fun tileCount(zoom: Int) = 2.0.pow(zoom).toInt()

    // ── Known values ──────────────────────────────────────────────────────────

    @Test fun `zoom zero is a single tile with the origin at its centre`() {
        val t = MapTiles.tileFor(0.0, 0.0, zoom = 0)
        assertEquals(0, t.x)
        assertEquals(0, t.y)
        assertEquals(0.5f, t.offsetX, 0.001f)
        assertEquals(0.5f, t.offsetY, 0.001f)
    }

    @Test fun `at zoom one the origin sits on the corner where all four tiles meet`() {
        val t = MapTiles.tileFor(0.0, 0.0, zoom = 1)
        assertEquals(1, t.x)
        assertEquals(1, t.y)
        assertEquals(0f, t.offsetX, 0.001f)
        assertEquals(0f, t.offsetY, 0.001f)
    }

    @Test fun `the western edge is the first tile`() {
        val t = MapTiles.tileFor(0.0, -180.0, zoom = 4)
        assertEquals(0, t.x)
    }

    @Test fun `the eastern extreme is the last tile`() {
        val t = MapTiles.tileFor(0.0, 179.999, zoom = 4)
        assertEquals(tileCount(4) - 1, t.x)
    }

    @Test fun `north is up`() {
        val north = MapTiles.tileFor(60.0, 0.0, zoom = 6)
        val south = MapTiles.tileFor(-60.0, 0.0, zoom = 6)
        assertTrue("y debe crecer hacia el sur", north.y < south.y)
    }

    @Test fun `east is right`() {
        val west = MapTiles.tileFor(0.0, -100.0, zoom = 6)
        val east = MapTiles.tileFor(0.0, 100.0, zoom = 6)
        assertTrue("x debe crecer hacia el este", west.x < east.x)
    }

    // ── Properties that must hold everywhere ──────────────────────────────────

    @Test fun `tile indices always stay inside the grid`() {
        val zooms = listOf(0, 1, 5, 12, MapTiles.DEFAULT_ZOOM, MapTiles.MAX_ZOOM)
        val lats = listOf(-90.0, -85.0, -45.0, 0.0, 25.6866, 45.0, 85.0, 90.0)
        val lons = listOf(-180.0, -100.3161, -1.0, 0.0, 1.0, 100.0, 180.0)
        for (z in zooms) for (lat in lats) for (lon in lons) {
            val t = MapTiles.tileFor(lat, lon, z)
            val n = tileCount(z)
            assertTrue("x fuera de rango en z=$z lat=$lat lon=$lon: ${t.x}", t.x in 0 until n)
            assertTrue("y fuera de rango en z=$z lat=$lat lon=$lon: ${t.y}", t.y in 0 until n)
            assertTrue("offsetX fuera de 0..1: ${t.offsetX}", t.offsetX in 0f..1f)
            assertTrue("offsetY fuera de 0..1: ${t.offsetY}", t.offsetY in 0f..1f)
        }
    }

    @Test fun `the poles are clamped instead of overflowing`() {
        // Mercator runs to infinity at the poles; unclamped this produces a garbage index.
        listOf(90.0, -90.0, 1000.0, -1000.0).forEach { lat ->
            val t = MapTiles.tileFor(lat, 0.0, zoom = 10)
            val n = tileCount(10)
            assertTrue("lat=$lat produjo y=${t.y}", t.y in 0 until n)
        }
    }

    @Test fun `a non-finite coordinate does not produce a broken tile`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { bad ->
            val t = MapTiles.tileFor(bad, bad, zoom = 8)
            val n = tileCount(8)
            assertTrue(t.x in 0 until n)
            assertTrue(t.y in 0 until n)
        }
    }

    @Test fun `zoom is clamped to what the tile service actually serves`() {
        assertEquals(MapTiles.MAX_ZOOM, MapTiles.tileFor(0.0, 0.0, zoom = 30).zoom)
        assertEquals(0, MapTiles.tileFor(0.0, 0.0, zoom = -5).zoom)
    }

    @Test fun `moving east never moves the tile west`() {
        var previous = -1
        var lon = -180.0
        while (lon < 180.0) {
            val x = MapTiles.tileFor(0.0, lon, zoom = 8).x
            assertTrue("x retrocedió en lon=$lon", x >= previous)
            previous = x
            lon += 3.0
        }
    }

    // ── Longitude wrapping ────────────────────────────────────────────────────

    @Test fun `longitudes inside the range are untouched`() {
        assertEquals(-180.0, MapTiles.wrapLongitude(-180.0), 1e-9)
        assertEquals(0.0, MapTiles.wrapLongitude(0.0), 1e-9)
        assertEquals(179.9, MapTiles.wrapLongitude(179.9), 1e-9)
    }

    @Test fun `longitudes past the antimeridian wrap around rather than pile up`() {
        // Clamping would pin everything past 180 to the same edge, which is wrong for a
        // cylinder: 190 East is 170 West.
        assertEquals(-170.0, MapTiles.wrapLongitude(190.0), 1e-9)
        assertEquals(170.0, MapTiles.wrapLongitude(-190.0), 1e-9)
        assertEquals(0.0, MapTiles.wrapLongitude(360.0), 1e-9)
    }

    @Test fun `wrapping never returns positive one hundred and eighty`() {
        listOf(180.0, 540.0, -180.0, 900.0).forEach {
            assertTrue("wrap($it) = ${MapTiles.wrapLongitude(it)}", MapTiles.wrapLongitude(it) < 180.0)
        }
    }

    // ── URLs and deep links ───────────────────────────────────────────────────

    @Test fun `the tile url matches the standard slippy layout`() {
        val t = MapTiles.TileRef(zoom = 15, x = 7654, y = 12345, offsetX = 0f, offsetY = 0f)
        assertEquals("https://tile.openstreetmap.org/15/7654/12345.png", MapTiles.url(t))
    }

    @Test fun `the geo uri carries the coordinate and the label`() {
        val uri = MapTiles.geoUri(25.6866, -100.3161, "Casa")
        assertTrue(uri.startsWith("geo:25.6866,-100.3161?q="))
        assertTrue(uri.contains("Casa"))
    }

    @Test fun `a label with spaces and accents is encoded`() {
        val uri = MapTiles.geoUri(0.0, 0.0, "Café Central")
        assertTrue("no debe llevar espacios crudos", !uri.contains(" "))
    }

    @Test fun `the geo uri clamps an impossible latitude`() {
        assertTrue(MapTiles.geoUri(200.0, 0.0, "X").startsWith("geo:90.0,"))
    }
}
