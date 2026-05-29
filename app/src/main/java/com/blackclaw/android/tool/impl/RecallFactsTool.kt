package com.blackclaw.android.tool.impl

import com.blackclaw.android.memory.UserMemoryStore
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

class RecallFactsTool : BaseTool() {
    override fun getName() = "recall_facts"
    override fun getDisplayName() = "Recall"
    override fun getDescriptionEN() =
        "Recall facts the user has told you to remember. Optional 'query' filters by " +
        "substring match against either key or value. Returns all facts if query omitted."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("query", "string", "Optional substring filter for key or value.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val query = optionalString(params, "query", "").trim()
        val facts = if (query.isEmpty()) UserMemoryStore.all() else UserMemoryStore.search(query)
        if (facts.isEmpty()) return ToolResult.success("No matching facts.")
        val body = facts.joinToString("\n") { "- ${it.key}: ${it.value}" }
        return ToolResult.success("${facts.size} fact(s):\n$body")
    }
}
