package com.blackclaw.android.tool.impl

import android.content.Intent
import android.net.Uri
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Open a URL or deep link in the default handler. Supports http(s), tel:, mailto:, geo:, sms:, etc.
 * Avoids opening Settings panels (use ToggleSettingTool for those).
 */
class OpenUrlTool : BaseTool() {
    override fun getName() = "open_url"
    override fun getDisplayName() = "Open URL"
    override fun getDescriptionEN() =
        "Open a URL or deep link in the default app. Examples: " +
        "'https://example.com', 'tel:+1234567890', 'mailto:foo@bar.com', " +
        "'geo:0,0?q=Madrid', 'sms:+1234567890?body=hi'."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("url", "string", "Full URL or deep link", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val url = requireString(params, "url").trim()
        if (url.isEmpty()) return ToolResult.error("url cannot be empty")
        val ctx = ClawApplication.instance
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            ToolResult.success("Opened: $url")
        } catch (e: Exception) {
            ToolResult.error("Failed to open URL: ${e.message}")
        }
    }
}
