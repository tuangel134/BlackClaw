package com.blackclaw.android.agent.knowledge

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

class KbSearchTool : BaseTool() {

    override fun getName() = "kb_search"

    override fun getDescriptionEN() =
        "Full-text search across all notes in the knowledge base vault. " +
        "Use this to find past notes, todos, or any previously saved content."

    override fun getDescriptionCN() =
        "在知識庫所有筆記中進行全文搜索。用於查找過去的筆記、待辦事項或任何已保存的內容。"

    override fun getParameters() = listOf(
        ToolParameter(
            "query", "string",
            "Search query. Case-insensitive, searches all .md files in the vault.",
            true
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        return try {
            val query = requireString(params, "query")
            val result = KBManager.search(query)
            result.fold(
                onSuccess = { results ->
                    if (results.isEmpty()) {
                        ToolResult.success("No results found for: \"$query\"")
                    } else {
                        val sb = StringBuilder("Found ${results.size} result(s) for \"$query\":\n\n")
                        results.forEach { r ->
                            sb.appendLine("📄 ${r.path}")
                            sb.appendLine(r.snippet)
                            sb.appendLine()
                        }
                        ToolResult.success(sb.toString().trimEnd())
                    }
                },
                onFailure = { ToolResult.error(it.message ?: "kb_search failed") }
            )
        } catch (e: IllegalArgumentException) {
            ToolResult.error("kb_search: missing required param — ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("kb_search error: ${e.message}")
        }
    }
}
