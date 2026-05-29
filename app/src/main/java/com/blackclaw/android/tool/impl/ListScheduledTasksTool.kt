package com.blackclaw.android.tool.impl

import com.blackclaw.android.scheduler.ScheduledTaskManager
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

class ListScheduledTasksTool : BaseTool() {
    override fun getName() = "list_scheduled_tasks"
    override fun getDisplayName() = "List Scheduled"
    override fun getDescriptionEN() =
        "List all scheduled tasks/chats with their id, time, recurrence, and text. " +
        "Use this when the user asks 'what reminders do I have' or 'show my scheduled tasks'."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getParameters() = emptyList<ToolParameter>()

    override fun execute(params: Map<String, Any>): ToolResult {
        val all = ScheduledTaskManager.listAll()
        if (all.isEmpty()) return ToolResult.success("No scheduled tasks.")
        return ToolResult.success(
            "${all.size} scheduled item(s):\n" + all.joinToString("\n") { "- " + it.describe() }
        )
    }
}
