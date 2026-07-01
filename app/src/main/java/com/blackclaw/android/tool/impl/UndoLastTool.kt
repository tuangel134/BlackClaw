package com.blackclaw.android.tool.impl

import com.blackclaw.android.ClawApplication
import com.blackclaw.android.assistant.AssistantScheduler
import com.blackclaw.android.assistant.AssistantStore
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Undo the last thing BlackClaw created in the Assistant hub (alarm / reminder /
 * event / note / etc.). Confidence-building "oops, cancel that" for hands-free.
 */
class UndoLastTool : BaseTool() {
    override fun getName() = "undo_last"
    override fun getDisplayName() = "Deshacer"
    override fun getDescriptionEN() =
        "Undo the most recent item BlackClaw created (alarm, reminder, event, note, etc.): removes it " +
        "and cancels its notification. Use for 'deshaz eso', 'cancela lo que acabas de crear', " +
        "'quita esa alarma', 'undo that'."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "deshace lo último que creó el asistente (alarma/recordatorio/evento…)"
    override fun getParameters() = emptyList<ToolParameter>()

    override fun execute(params: Map<String, Any>): ToolResult {
        // Most recent AI-created item (fallback: most recent of any).
        val all = AssistantStore.all()
        val target = all.filter { it.source == "ai" }.maxByOrNull { it.createdAtMs }
            ?: all.maxByOrNull { it.createdAtMs }
            ?: return ToolResult.success("No hay nada reciente que deshacer, jefe.")
        runCatching { AssistantScheduler.cancel(ClawApplication.instance, target.id) }
        AssistantStore.delete(target.id)
        val kind = target.type.name.lowercase()
        return ToolResult.success("Deshecho: eliminé $kind '${target.title}'.")
    }
}
