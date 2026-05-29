package com.blackclaw.android.tool.impl

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Set system screen brightness 0–100. Requires WRITE_SETTINGS permission.
 * If the permission is missing, opens the grant screen and returns an actionable error.
 */
class SetBrightnessTool : BaseTool() {
    override fun getName() = "set_brightness"
    override fun getDisplayName() = "Set Brightness"
    override fun getDescriptionEN() =
        "Set screen brightness 0-100. Also turns auto-brightness off. " +
        "First call may fail with a permission prompt (WRITE_SETTINGS) — settings page opens automatically."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("level", "integer", "Brightness 0-100", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val level = requireInt(params, "level").coerceIn(0, 100)
        val ctx = ClawApplication.instance
        if (!Settings.System.canWrite(ctx)) {
            // Open the Modify System Settings permission screen.
            try {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${ctx.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
            } catch (_: Exception) {}
            return ToolResult.error(
                "Brightness control needs the WRITE_SETTINGS permission. " +
                "Grant 'Modify system settings' to BlackClaw and call again."
            )
        }
        val target = (level * 255 / 100).coerceIn(0, 255)
        return try {
            // Disable auto-brightness so the manual level sticks
            Settings.System.putInt(
                ctx.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(
                ctx.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                target,
            )
            ToolResult.success("Brightness set to $level% ($target/255)")
        } catch (e: Exception) {
            ToolResult.error("Failed to set brightness: ${e.message}")
        }
    }
}
