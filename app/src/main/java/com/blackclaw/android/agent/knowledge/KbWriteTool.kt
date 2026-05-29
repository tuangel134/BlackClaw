package com.blackclaw.android.agent.knowledge

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class KbWriteTool : BaseTool() {

    override fun getName() = "kb_write"

    override fun getDescriptionEN() =
        "Write or create a note in the knowledge base vault. Overwrites if the file already exists. " +
        "Use for new notes, meeting summaries, calendar entries, and any content you want to persist."

    override fun getDescriptionCN() =
        "在知識庫寫入或創建筆記。若文件已存在則覆蓋。適用於新筆記、會議記錄、行程等需要持久化的內容。"

    override fun getParameters() = listOf(
        ToolParameter(
            "path", "string",
            "File path relative to vault root, e.g. 'notes/meeting-2026-04-07.md' or 'calendar/2026-04/2026-04-15.md'",
            true
        ),
        ToolParameter(
            "content", "string",
            "Markdown content to write (do not include frontmatter — it is added automatically)",
            true
        ),
        ToolParameter(
            "type", "string",
            "Note type: note | todo | calendar | journal | research (default: note)",
            false
        ),
        ToolParameter(
            "date", "string",
            "Date in YYYY-MM-DD format (default: today)",
            false
        ),
        ToolParameter(
            "tags", "string",
            "Comma-separated tags, e.g. 'work,meeting,q2'",
            false
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        return try {
            val path = requireString(params, "path")
            val content = requireString(params, "content")
            val type = optionalString(params, "type", "note")
            val date = optionalString(params, "date", today())
            val tags = optionalString(params, "tags", "")

            val frontmatter = mutableMapOf<String, Any>(
                "type" to type,
                "date" to date
            )
            if (tags.isNotBlank()) {
                val tagList = tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                frontmatter["tags"] = "[${tagList.joinToString(", ")}]"
            }

            val result = KBManager.write(path, frontmatter, content)
            result.fold(
                onSuccess = { ToolResult.success(it) },
                onFailure = { ToolResult.error(it.message ?: "kb_write failed") }
            )
        } catch (e: IllegalArgumentException) {
            ToolResult.error("kb_write: missing required param — ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("kb_write error: ${e.message}")
        }
    }

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
