package com.blackclaw.android.tool.impl

import android.content.Context
import android.media.AudioManager
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Set the device's media or ringer volume to an absolute level (0–100 %).
 * No notification access required for media volume; ringer / alarm streams may
 * fail silently on devices that gate them behind DND access.
 */
class SetVolumeTool : BaseTool() {
    override fun getName() = "set_volume"
    override fun getDisplayName() = "Set Volume"
    override fun getDescriptionEN() =
        "Set device volume directly. stream: 'media' (default), 'ring', 'alarm', 'notification', 'call'. " +
        "level: 0-100 (percent). Mute with level=0."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("level", "integer", "Volume level 0-100", true),
        ToolParameter("stream", "string",
            "Audio stream: media (default), ring, alarm, notification, call", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val level = requireInt(params, "level").coerceIn(0, 100)
        val streamName = optionalString(params, "stream", "media").lowercase()
        val streamId = when (streamName) {
            "media", "music" -> AudioManager.STREAM_MUSIC
            "ring", "ringer", "ringtone" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "call", "voice" -> AudioManager.STREAM_VOICE_CALL
            else -> return ToolResult.error("Unknown stream '$streamName'. Use media|ring|alarm|notification|call")
        }
        val ctx = ClawApplication.instance
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(streamId)
        val target = (level * max / 100).coerceIn(0, max)
        return try {
            am.setStreamVolume(streamId, target, AudioManager.FLAG_SHOW_UI)
            ToolResult.success("Volume ($streamName) set to $level% ($target/$max)")
        } catch (e: SecurityException) {
            // STREAM_RING / STREAM_ALARM can be blocked by Do Not Disturb policy
            ToolResult.error(
                "Cannot set $streamName volume: Do Not Disturb policy blocks it. " +
                "Grant DND access in Settings > Apps > BlackClaw > Notification access."
            )
        }
    }
}
