package com.blackclaw.android.tool.impl

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Control playback of whatever app currently owns the media session
 * (Spotify, YouTube Music, Pocket Casts, etc.) by dispatching media key events
 * through AudioManager.
 *
 * No special permissions needed: dispatchMediaKeyEvent works with normal app caller.
 */
class MediaControlTool : BaseTool() {
    override fun getName() = "media_control"
    override fun getDisplayName() = "Control multimedia"
    override fun getDescriptionEN() =
        "Control whatever is playing media on the device. " +
        "Actions: play, pause, toggle, next, previous, stop, fast_forward, rewind. " +
        "Works with Spotify, YouTube Music, podcasts, etc."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("action", "string",
            "play | pause | toggle | next | previous | stop | fast_forward | rewind", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val action = requireString(params, "action").lowercase().trim()
        val keyCode = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "toggle", "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            "fast_forward", "ff" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            "rewind", "rew" -> KeyEvent.KEYCODE_MEDIA_REWIND
            else -> return ToolResult.error(
                "Unknown action '$action'. Use play|pause|toggle|next|previous|stop|fast_forward|rewind"
            )
        }
        val ctx = ClawApplication.instance
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            val now = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
            ToolResult.success("Media: $action sent")
        } catch (e: Exception) {
            ToolResult.error("Media key dispatch failed: ${e.message}")
        }
    }
}
