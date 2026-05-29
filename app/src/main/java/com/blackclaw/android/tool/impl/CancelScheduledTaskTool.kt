package com.blackclaw.android.tool.impl

import com.blackclaw.android.ClawApplication
import com.blackclaw.android.scheduler.ScheduledTaskManager
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

class CancelScheduledTaskTool : BaseTool() {
    override fun getName() = "cancel_scheduled_task"
    override fun getDisplayName() = "Cancel Scheduled"
    override fun getDescriptionEN() =
        "Cancel a scheduled task by id. Use list_scheduled_tasks to find ids first. " +
        "Pass id='all' to cancel every scheduled item."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("id", "string",
            "Schedule id from list_scheduled_tasks, or 'all' to cancel every entry.", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val id = requireString(params, "id").trim()
        val ctx = ClawApplication.instance
        if (id.equals("all", ignoreCase = true)) {
            val all = ScheduledTaskManager.listAll()
            all.forEach { ScheduledTaskManager.cancel(ctx, it.id) }
            return ToolResult.success("Cancelled ${all.size} scheduled task(s).")
        }
        val ok = ScheduledTaskManager.cancel(ctx, id)
        return if (ok) ToolResult.success("Cancelled scheduled task $id")
        else ToolResult.error("No scheduled task with id $id")
    }
}
