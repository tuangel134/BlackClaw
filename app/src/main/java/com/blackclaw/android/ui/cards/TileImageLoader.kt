package com.blackclaw.android.ui.cards

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.blackclaw.android.utils.XLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches and caches single map tiles for the mini map.
 *
 * ## Why hand-rolled instead of an image library
 *
 * The project ships Glide, which is a View-based loader — using it from Compose means
 * bridging through an `AndroidView` and an `ImageView`, and Coil (the Compose-native
 * option) is not a dependency. For one 256 px PNG per card, an OkHttp GET and a bitmap
 * decode are less machinery than either, and add no dependency at all.
 *
 * ## The User-Agent is not optional
 *
 * OpenStreetMap's public tile service requires a request to identify the application and
 * only tolerates light use. A generic or absent User-Agent gets blocked, so anything that
 * would fetch tiles in bulk — panning, prefetching, a tile grid — needs its own tile
 * source rather than this.
 *
 * ## Failure returns null on purpose
 *
 * No connection, a blocked request or a corrupt body all produce null, and the card then
 * shows coordinates and a button instead of a broken image. A map that cannot load should
 * say so, not leave a grey rectangle that looks like a rendering fault.
 */
object TileImageLoader {

    private const val TAG = "TileImageLoader"

    /**
     * Cached tiles. A tile is a 256 px PNG, a few tens of kB decoded, so this bounds the
     * cache at roughly a couple of MB — cheap next to never re-fetching the tile for a
     * place the user asks about twice.
     */
    private const val CACHE_ENTRIES = 32

    private val cache = LruCache<String, ImageBitmap>(CACHE_ENTRIES)

    /** Negative results are remembered briefly so an offline card stops retrying on every recomposition. */
    private val failures = LruCache<String, Long>(CACHE_ENTRIES)
    private const val FAILURE_TTL_MS = 30_000L

    private val client by lazy {
        OkHttpClient.Builder()
            // Short: this is decoration on a screen the user is already reading. If the
            // tile is slow, the card should settle into its fallback rather than hold a
            // loading state open.
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    suspend fun load(url: String): ImageBitmap? {
        cache.get(url)?.let { return it }
        failures.get(url)?.let { failedAt ->
            if (System.currentTimeMillis() - failedAt < FAILURE_TTL_MS) return null
            failures.remove(url)
        }
        return withContext(Dispatchers.IO) {
            val bitmap = runCatching { fetch(url) }.getOrElse {
                XLog.d(TAG, "tile fetch failed: ${it.javaClass.simpleName}")
                null
            }
            if (bitmap == null) {
                failures.put(url, System.currentTimeMillis())
                null
            } else {
                cache.put(url, bitmap)
                bitmap
            }
        }
    }

    private fun fetch(url: String): ImageBitmap? {
        val request = Request.Builder()
            .url(url)
            // Identifies the app, as the tile policy requires.
            .header("User-Agent", "BlackClaw/1.0 (Android; offline assistant)")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                XLog.d(TAG, "tile HTTP ${response.code}")
                return null
            }
            val bytes = response.body?.bytes() ?: return null
            if (bytes.size > MAX_TILE_BYTES) return null
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            return decoded.asImageBitmap()
        }
    }

    /** A 256 px tile is far below this; anything larger is not a tile we asked for. */
    private const val MAX_TILE_BYTES = 512 * 1024
}
