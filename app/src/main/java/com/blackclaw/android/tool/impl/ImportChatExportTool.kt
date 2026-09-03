package com.blackclaw.android.tool.impl

import android.net.Uri
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.autoreply.AutoReplyProfile
import com.blackclaw.android.autoreply.AutoReplyProfileStore
import com.blackclaw.android.autoreply.WhatsAppExportParser
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import java.io.File

/**
 * Imports a WhatsApp chat export (.txt or .zip) and either:
 *  - Returns the parsed conversation as text (for the agent to use in a reply)
 *  - Saves it as the conversationContext of an existing or new auto-reply profile
 */
class ImportChatExportTool : BaseTool() {
    override fun getName() = "import_chat_export"
    override fun getDisplayName() = "Importar chat"
    override fun getDescriptionEN() =
        "Parse a WhatsApp chat export (.txt or .zip) the user shared with BlackClaw. " +
        "Either returns the rendered transcript or saves it as conversationContext for " +
        "an auto-reply profile (specify contact_name and optional user_name)."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("path", "string",
            "Absolute path or content:// URI of the export file. Use the path of a file " +
            "the user shared into BlackClaw or one in Documents/BlackClaw/.", true),
        ToolParameter("contact_name", "string",
            "Contact name to associate. If a profile exists for this contact, its " +
            "conversation_context is updated. Otherwise a new profile is created.", false),
        ToolParameter("user_name", "string",
            "Optional: how the user appears in the export (e.g. 'Ángel'). Used to " +
            "label messages as 'Yo' instead of the raw name.", false),
        ToolParameter("save_to_profile", "boolean",
            "If true (default false) the parsed transcript is saved into a profile.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val path = requireString(params, "path").trim()
        if (path.isEmpty()) return ToolResult.error("path cannot be empty")
        val contactName = optionalString(params, "contact_name", "").trim()
        val userName = optionalString(params, "user_name", "").trim()
        val saveToProfile = optionalBoolean(params, "save_to_profile", false)

        val ctx = ClawApplication.instance
        val uri = if (path.startsWith("content://")) {
            Uri.parse(path)
        } else {
            val f = File(path)
            if (!f.exists()) return ToolResult.error("File not found: $path")
            Uri.fromFile(f)
        }

        val messages = WhatsAppExportParser.parse(ctx, uri)
        if (messages.isEmpty()) {
            return ToolResult.error(
                "No messages found in the export. Make sure it's a WhatsApp chat export " +
                "(.txt) or its .zip archive."
            )
        }

        val rendered = WhatsAppExportParser.renderForPrompt(messages, userName.ifBlank { null })
        val stats = WhatsAppExportParser.stats(messages)
        val sendersStr = stats.senders.joinToString(", ") { "${it.first} (${it.second})" }

        if (saveToProfile && contactName.isNotEmpty()) {
            val existing = AutoReplyProfileStore.all()
                .firstOrNull { it.contactName.equals(contactName, ignoreCase = true) }
            val updated = if (existing != null) {
                existing.copy(
                    conversationContext = rendered,
                    updatedAtMs = System.currentTimeMillis(),
                )
            } else {
                AutoReplyProfile.blank().copy(
                    contactName = contactName,
                    conversationContext = rendered,
                )
            }
            if (AutoReplyProfileStore.upsert(updated) == null) {
                return ToolResult.error("Could not store the imported conversation securely.")
            }
            return ToolResult.success(
                "Importadas ${stats.messageCount} mensajes (${sendersStr}). " +
                "Guardado en perfil de auto-respuesta para '$contactName'."
            )
        }

        return ToolResult.success(
            "Importadas ${stats.messageCount} mensajes. Remitentes: $sendersStr.\n\n" +
            rendered.take(2000) + if (rendered.length > 2000) "\n…" else ""
        )
    }
}
