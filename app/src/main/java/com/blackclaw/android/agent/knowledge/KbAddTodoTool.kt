package com.blackclaw.android.agent.knowledge

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

class KbAddTodoTool : BaseTool() {

    override fun getName() = "kb_add_todo"

    override fun getDescriptionEN() =
        "Add a todo item to today's todo list. Creates the file automatically if it does not exist."

    override fun getDescriptionCN() =
        "新增待辦事項到今日的 todo 清單。若文件不存在則自動創建。"

    override fun getParameters() = listOf(
        ToolParameter(
            "text", "string",
            "The todo item text, e.g. '下星期一見醫生'",
            true
        ),
        ToolParameter(
            "due", "string",
            "Optional due date in YYYY-MM-DD format, e.g. '2026-04-14'",
            false
        ),
        ToolParameter(
            "priority", "string",
            "Optional priority: high | medium | low",
            false
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        return try {
            val text = requireString(params, "text")
            val due = params["due"]?.toString()?.takeIf { it.isNotBlank() }
            val priority = params["priority"]?.toString()?.takeIf { it.isNotBlank() }
            val result = KBManager.addTodo(text, due, priority)
            result.fold(
                onSuccess = { ToolResult.success(it) },
                onFailure = { ToolResult.error(it.message ?: "kb_add_todo failed") }
            )
        } catch (e: IllegalArgumentException) {
            ToolResult.error("kb_add_todo: missing required param — ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("kb_add_todo error: ${e.message}")
        }
    }
}
