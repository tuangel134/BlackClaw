package com.blackclaw.android.tool.impl

import com.blackclaw.android.memory.UserMemoryStore
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

class ForgetFactTool : BaseTool() {
    override fun getName() = "forget_fact"
    override fun getDisplayName() = "Forget"
    override fun getDescriptionEN() =
        "Delete a remembered fact by key (or id). Pass key='all' to wipe every saved fact."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("key", "string", "Fact key (or id), or 'all' to delete every fact.", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val k = requireString(params, "key").trim()
        if (k.equals("all", ignoreCase = true)) {
            val n = UserMemoryStore.forgetAll()
            return ToolResult.success("Forgot $n fact(s).")
        }
        val ok = UserMemoryStore.forget(k)
        return if (ok) ToolResult.success("Forgot '$k'")
        else ToolResult.error("No fact with key/id '$k'")
    }
}
