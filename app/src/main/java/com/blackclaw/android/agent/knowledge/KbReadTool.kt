package com.blackclaw.android.agent.knowledge

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

class KbReadTool : BaseTool() {

    override fun getName() = "kb_read"

    override fun getDescriptionEN() =
        "Read the full content of a note from the knowledge base vault by its path."

    override fun getDescriptionCN() =
        "根據路徑讀取知識庫中筆記的完整內容。"

    override fun getParameters() = listOf(
        ToolParameter(
            "path", "string",
            "File path relative to vault root, e.g. 'todos/2026-04-07.md'",
            true
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        return try {
            val path = requireString(params, "path")
            val result = KBManager.read(path)
            result.fold(
                onSuccess = { ToolResult.success(it) },
                onFailure = { ToolResult.error(it.message ?: "kb_read failed") }
            )
        } catch (e: IllegalArgumentException) {
            ToolResult.error("kb_read: missing required param — ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("kb_read error: ${e.message}")
        }
    }
}
