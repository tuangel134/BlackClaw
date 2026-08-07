package com.blackclaw.android.game

import kotlin.math.roundToInt

/** Pure policy/math used by the game tools and covered by JVM tests. */
object GameControlPolicy {
    const val NORMALIZED_MAX = 1000
    const val OBSERVATION_TTL_MS = 30_000L
    const val MAX_ACTIONS_PER_OBSERVATION = 12

    private val knownGames = mapOf(
        "com.supercell.clashofclans" to "Clash of Clans",
        "com.supercell.clashroyale" to "Clash Royale",
        "com.supercell.brawlstars" to "Brawl Stars",
        "com.chess" to "Chess.com",
        "org.lichess.mobileapp" to "Lichess",
    )

    private val confirmationWords = listOf(
        "attack", "battle", "ranked", "match", "purchase", "buy", "spend", "upgrade",
        "atacar", "batalla", "partida", "comprar", "gastar", "mejorar", "gema", "gem",
    )

    fun knownGameName(packageName: String): String? = knownGames[packageName]

    fun toPixel(normalized: Int, dimension: Int): Int {
        require(normalized in 0..NORMALIZED_MAX) { "Coordinate must be between 0 and 1000" }
        require(dimension > 0) { "Dimension must be positive" }
        return (normalized / NORMALIZED_MAX.toDouble() * (dimension - 1)).roundToInt()
    }

    fun requiresConfirmation(actionLabel: String): Boolean {
        val lower = actionLabel.lowercase()
        return confirmationWords.any { it in lower }
    }

    fun perceptualHash(samples: IntArray): Long {
        require(samples.size == 64) { "Exactly 64 luminance samples are required" }
        val average = samples.average()
        var hash = 0L
        samples.forEachIndexed { index, value ->
            if (value >= average) hash = hash or (1L shl index)
        }
        return hash
    }

    fun changedPercent(before: Long, after: Long): Int =
        (java.lang.Long.bitCount(before xor after) * 100.0 / 64.0).roundToInt()
}
