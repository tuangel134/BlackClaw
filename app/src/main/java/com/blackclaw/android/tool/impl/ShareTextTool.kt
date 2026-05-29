package com.blackclaw.android.tool.impl

import android.content.Intent
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Pop the system share sheet with the given text. The user picks the destination app.
 * Useful for "share this to Twitter / WhatsApp / Notes" flows where we don't know
 * the target app up front.
 */
class ShareTextTool : BaseTool() {
    override fun getName() = "share_text"
    override fun getDisplayName() = "Share Text"
    override fun getDescriptionEN() =
        "Open the Android share sheet with the given text so the user can pick " +
        "where to send it (Notes, Twitter, WhatsApp, email, etc). " +
        "Returns immediately — the share dialog is async."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("text", "string", "Text to share.", true),
        ToolParameter("subject", "string", "Optional subject (used by some apps like email).", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val text = requireString(params, "text")
        val subject = optionalString(params, "subject", "")
        return try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                if (subject.isNotEmpty()) putExtra(Intent.EXTRA_SUBJECT, subject)
            }
            val chooser = Intent.createChooser(send, "Share").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ClawApplication.instance.startActivity(chooser)
            ToolResult.success("Share sheet opened with ${text.length} chars.")
        } catch (e: Exception) {
            ToolResult.error("Failed to open share sheet: ${e.message}")
        }
    }
}
