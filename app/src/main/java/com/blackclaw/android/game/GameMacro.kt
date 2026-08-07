package com.blackclaw.android.game

sealed class GameGesture {
    abstract val delayBeforeMs: Long

    data class Tap(
        val x: Int,
        val y: Int,
        override val delayBeforeMs: Long,
    ) : GameGesture()

    data class Swipe(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        val durationMs: Long,
        override val delayBeforeMs: Long,
    ) : GameGesture()
}

data class GameMacro(
    val id: String,
    val name: String,
    val packageName: String,
    val createdAtMs: Long,
    val gestures: List<GameGesture>,
) {
    fun estimatedDurationMs(): Long = gestures.sumOf { gesture ->
        gesture.delayBeforeMs + if (gesture is GameGesture.Swipe) gesture.durationMs else 60L
    }
}
