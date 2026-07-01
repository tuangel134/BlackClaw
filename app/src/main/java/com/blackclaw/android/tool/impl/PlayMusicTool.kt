package com.blackclaw.android.tool.impl

import android.app.SearchManager
import android.content.Intent
import android.provider.MediaStore
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import com.blackclaw.android.utils.KVUtils

/**
 * Play music in ANY player, not just Spotify. Uses Android's universal
 * "play from search" intent (MEDIA_PLAY_FROM_SEARCH), which most music apps
 * register for — Spotify, YouTube Music, Amazon Music, Deezer, SoundCloud,
 * the default/local player, etc. Optionally target a specific player by name.
 *
 * Resolution order:
 *   1. If [app] names a known player → fire PLAY_FROM_SEARCH pinned to it.
 *   2. If [app] given but the intent isn't handled → its deep-link search.
 *   3. No [app] → fire PLAY_FROM_SEARCH unpinned (system/default player picks it up).
 *   4. Fallbacks → Spotify deep link, then a web search.
 */
class PlayMusicTool : BaseTool() {

    companion object {
        const val KEY_PREF_PLAYER = "preferred_music_player"
    }

    override fun getName() = "play_music"
    override fun getDisplayName() = "Reproducir música"
    override fun getDescriptionEN() =
        "Play a song/artist/playlist in a music player using Android's universal play-from-search, " +
        "so it works in WHATEVER player the user has (not only Spotify). " +
        "Pass 'query' (what to play) and optional 'app' to force a player: " +
        "spotify, youtube_music, youtube, amazon_music, apple_music, deezer, soundcloud, tidal, " +
        "poweramp, default. Examples: play_music(query='Bad Bunny'); " +
        "play_music(query='lofi beats', app='youtube_music')."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "reproduce música en cualquier reproductor (no solo Spotify) vía el intent universal"
    override fun getParameters() = listOf(
        ToolParameter("query", "string",
            "Song, artist, album or playlist to play. Leave empty to just start/resume " +
            "playback in the user's player (for a plain 'play music' with no specifics).", false),
        ToolParameter("app", "string",
            "Optional player: spotify|youtube_music|youtube|amazon_music|apple_music|deezer|" +
            "soundcloud|tidal|poweramp|default.", false),
    )

    // Known music-player packages for PLAY_FROM_SEARCH targeting.
    private val PLAYERS = mapOf(
        "spotify" to "com.spotify.music",
        "youtube_music" to "com.google.android.apps.youtube.music",
        "youtube music" to "com.google.android.apps.youtube.music",
        "ytmusic" to "com.google.android.apps.youtube.music",
        "youtube" to "com.google.android.youtube",
        "amazon_music" to "com.amazon.mp3",
        "amazon music" to "com.amazon.mp3",
        "apple_music" to "com.apple.android.music",
        "apple music" to "com.apple.android.music",
        "deezer" to "deezer.android.app",
        "soundcloud" to "com.soundcloud.android",
        "tidal" to "com.aspiro.tidal",
        "poweramp" to "com.maxmpz.audioplayer",
        "musicolet" to "in.krosbits.musicolet",
        "sonata" to "com.sonata.player",
        "vlc" to "org.videolan.vlc",
    )

    // When no player is named, prefer the user's saved choice, then the first of
    // these that's installed (dedicated local/offline players first — that's the
    // "pon música" intent most of the time).
    private val DEFAULT_PRIORITY = listOf(
        "com.sonata.player",                           // Sonata (user's own player)
        "in.krosbits.musicolet",                       // Musicolet (offline)
        "com.maxmpz.audioplayer",                      // Poweramp
        "com.spotify.music",                           // Spotify
        "com.google.android.apps.youtube.music",       // YouTube Music
        "com.amazon.mp3", "deezer.android.app", "com.soundcloud.android",
    )

    private fun resolvePackage(ctx: android.content.Context, app: String): String? {
        if (app.isNotBlank()) return PLAYERS[app]
        // No app named → saved preference, else first installed by priority.
        val pref = KVUtils.getString(KEY_PREF_PLAYER, "")
        if (pref.isNotBlank()) return pref
        val pm = ctx.packageManager
        return DEFAULT_PRIORITY.firstOrNull { pkg ->
            runCatching { pm.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)
        }
    }

    /**
     * Handle a plain "play music" with no song/artist. Per the Android media
     * spec an EMPTY play-from-search query means "play some music", so we fire
     * that first (most players start/shuffle something). If that isn't handled,
     * we open the player and dispatch a MEDIA_PLAY key to resume the last track —
     * far better than searching literally for the word "música".
     */
    private fun playOrResume(
        ctx: android.content.Context,
        app: String,
        targetPkg: String?,
    ): ToolResult {
        val pm = ctx.packageManager
        val where = if (app.isNotBlank()) " en $app" else ""

        // 1) Empty play-from-search = "play any music" (spec-compliant).
        runCatching {
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(SearchManager.QUERY, "")
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (targetPkg != null) setPackage(targetPkg)
            }
            if (intent.resolveActivity(pm) != null) {
                ctx.startActivity(intent)
                return ToolResult.success("Reproduciendo música$where.")
            }
        }

        // 2) Open the player, then resume playback via a MEDIA_PLAY key event.
        if (targetPkg != null) {
            runCatching {
                pm.getLaunchIntentForPackage(targetPkg)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(it)
                }
            }
            dispatchPlayKey(ctx)
            return ToolResult.success("Abrí tu reproductor y reanudé la música$where.")
        }

        // 3) No known player installed → just try to resume whatever media session exists.
        dispatchPlayKey(ctx)
        return ToolResult.success("Reanudé la reproducción de música.")
    }

    /** Send a global MEDIA_PLAY key so the active/last media session resumes. */
    private fun dispatchPlayKey(ctx: android.content.Context) {
        runCatching {
            val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            val code = android.view.KeyEvent.KEYCODE_MEDIA_PLAY
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        }
    }

    override fun execute(params: Map<String, Any>): ToolResult {
        val query = optionalString(params, "query", "").trim()
        val app = optionalString(params, "app", "").lowercase().trim()
        val ctx = ClawApplication.instance
        val pm = ctx.packageManager

        val targetPkg = resolvePackage(ctx, app)

        // Plain "play music" with no song/artist → start or resume playback in the
        // user's player instead of literally searching for the word "música".
        if (query.isEmpty()) return playOrResume(ctx, app, targetPkg)

        // 1 & 3: universal play-from-search (pinned to a player if given).
        runCatching {
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (targetPkg != null) setPackage(targetPkg)
            }
            if (intent.resolveActivity(pm) != null) {
                ctx.startActivity(intent)
                val where = if (app.isNotBlank()) " en $app" else ""
                return ToolResult.success("Reproduciendo '$query'$where.")
            }
        }

        // If a player was requested but didn't handle the universal intent, try
        // its deep-link search (e.g. Spotify, YouTube).
        if (app.isNotBlank()) {
            val entryKey = when {
                app.contains("spotify") -> "spotify"
                app.contains("youtube") -> "youtube"
                else -> null
            }
            if (entryKey != null) {
                val r = OpenAppActionTool().execute(mapOf("app" to entryKey, "query" to query))
                if (r.isSuccess) return r
            }
        }

        // 4: fallbacks — try the unpinned universal intent once more (no package),
        // then Spotify deep link, then a web search.
        runCatching {
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(pm) != null) {
                ctx.startActivity(intent)
                return ToolResult.success("Reproduciendo '$query' en tu reproductor de música.")
            }
        }
        val spotify = OpenAppActionTool().execute(mapOf("app" to "spotify", "query" to query))
        if (spotify.isSuccess) return spotify
        return OpenAppActionTool().execute(mapOf("app" to "youtube", "query" to query))
    }
}

/**
 * Set (or clear) the user's preferred music player, used by [PlayMusicTool] when
 * the user says "pon música" without naming an app. Persists across sessions.
 */
class SetMusicPlayerTool : BaseTool() {
    override fun getName() = "set_music_player"
    override fun getDisplayName() = "Reproductor preferido"
    override fun getDescriptionEN() =
        "Set the user's default/preferred music player so future 'play music' commands use it. " +
        "app: spotify|youtube_music|youtube|amazon_music|apple_music|deezer|soundcloud|tidal|" +
        "poweramp|musicolet|vlc, or 'default' to clear. Use when the user says 'usa X para la música' " +
        "or 'mi reproductor es X'."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "fija el reproductor de música preferido del usuario"
    override fun getParameters() = listOf(
        ToolParameter("app", "string", "Player key, or 'default' to clear the preference.", true),
    )

    private val PLAYERS = mapOf(
        "spotify" to "com.spotify.music",
        "youtube_music" to "com.google.android.apps.youtube.music",
        "youtube music" to "com.google.android.apps.youtube.music",
        "youtube" to "com.google.android.youtube",
        "amazon_music" to "com.amazon.mp3",
        "amazon music" to "com.amazon.mp3",
        "apple_music" to "com.apple.android.music",
        "apple music" to "com.apple.android.music",
        "deezer" to "deezer.android.app",
        "soundcloud" to "com.soundcloud.android",
        "tidal" to "com.aspiro.tidal",
        "poweramp" to "com.maxmpz.audioplayer",
        "musicolet" to "in.krosbits.musicolet",
        "sonata" to "com.sonata.player",
        "vlc" to "org.videolan.vlc",
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val app = requireString(params, "app").lowercase().trim()
        if (app == "default" || app == "ninguno" || app.isBlank()) {
            KVUtils.putString(PlayMusicTool.KEY_PREF_PLAYER, ""); KVUtils.sync()
            return ToolResult.success("Listo: usaré el reproductor predeterminado del sistema.")
        }
        val pkg = PLAYERS[app]
            ?: return ToolResult.error("No conozco el reproductor '$app'. Opciones: ${PLAYERS.keys.joinToString()}.")
        KVUtils.putString(PlayMusicTool.KEY_PREF_PLAYER, pkg); KVUtils.sync()
        return ToolResult.success("Hecho, jefe. Usaré $app para la música a partir de ahora.")
    }
}
