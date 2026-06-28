package com.blackclaw.android.tool.impl

import com.blackclaw.android.ClawApplication
import com.blackclaw.android.assistant.AssistantItemType
import com.blackclaw.android.assistant.AssistantScheduler
import com.blackclaw.android.assistant.AssistantStore
import com.blackclaw.android.assistant.AssistantTime
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import com.blackclaw.android.tool.ToolRegistry
import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog

/**
 * Focus Mode / Deep Work / Pomodoro tool.
 *
 * When activated:
 * - Enables DND (Do Not Disturb)
 * - Sets a timer for the focus duration
 * - Optionally auto-replies to messages
 * - When timer ends: disables DND, notifies user, shows summary of what was missed
 *
 * Also supports Pomodoro technique (25 min work + 5 min break cycles).
 */
class FocusModeTool : BaseTool() {
    override fun getName() = "focus_mode"
    override fun getDisplayName() = "Modo Focus"
    override fun getDescriptionEN() =
        "Activate focus/deep work mode. Silences the phone (DND), sets a timer, and optionally " +
        "auto-replies. When done, disables DND and notifies. " +
        "mode: focus (custom duration) | pomodoro (25+5 cycles). " +
        "Use for: 'necesito concentrarme', 'modo focus 1 hora', 'pomodoro'."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "activa modo focus/pomodoro (DND + timer + auto-reply opcional)"
    override fun getParameters() = listOf(
        ToolParameter("mode", "string", "focus | pomodoro. Default focus.", false),
        ToolParameter("duration_minutes", "integer", "Duration in minutes. Default 25 for pomodoro, 60 for focus.", false),
        ToolParameter("cycles", "integer", "For pomodoro: number of cycles. Default 4.", false),
        ToolParameter("auto_reply", "string", "Optional message to auto-reply with (e.g. 'Estoy concentrado, te contesto luego').", false),
        ToolParameter("label", "string", "What you're focusing on (e.g. 'estudiar', 'proyecto').", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val mode = optionalString(params, "mode", "focus").lowercase()
        val label = optionalString(params, "label", "Focus")
        val autoReply = optionalString(params, "auto_reply", "")
        val registry = ToolRegistry.getInstance()

        return when (mode) {
            "pomodoro" -> {
                val cycles = optionalInt(params, "cycles", 4).coerceIn(1, 12)
                val workMin = optionalInt(params, "duration_minutes", 25).coerceIn(5, 120)
                val breakMin = (workMin / 5).coerceIn(3, 15)
                startPomodoro(workMin, breakMin, cycles, label, autoReply, registry)
            }
            else -> {
                val duration = optionalInt(params, "duration_minutes", 60).coerceIn(5, 480)
                startFocus(duration, label, autoReply, registry)
            }
        }
    }

    private fun startFocus(minutes: Int, label: String, autoReply: String, registry: ToolRegistry): ToolResult {
        // Enable DND
        registry.executeTool("toggle_setting", mapOf("setting" to "dnd", "state" to "on"))

        // Set end reminder
        val endTime = System.currentTimeMillis() + minutes * 60_000L
        val item = AssistantStore.create(
            type = AssistantItemType.REMINDER,
            title = "🎯 Fin del modo focus: $label",
            body = "Has completado $minutes minutos de concentración. ¡Buen trabajo!",
            triggerAtMs = endTime, source = "ai",
        )
        AssistantScheduler.arm(ClawApplication.instance, item)

        // Save focus state
        KVUtils.putString("focus_mode_active", "true")
        KVUtils.putLong("focus_mode_end", endTime)
        KVUtils.putString("focus_mode_label", label)
        KVUtils.sync()

        // Auto-reply config
        if (autoReply.isNotBlank()) {
            KVUtils.putString("focus_mode_auto_reply", autoReply)
            KVUtils.sync()
        }

        val endStr = AssistantTime.format(endTime)
        return ToolResult.success(
            "🎯 Modo Focus activado: $label\n" +
            "⏱ Duración: $minutes min (hasta $endStr)\n" +
            "🔕 DND activado\n" +
            if (autoReply.isNotBlank()) "💬 Auto-reply: '$autoReply'" else "Sin auto-reply"
        )
    }

    private fun startPomodoro(workMin: Int, breakMin: Int, cycles: Int, label: String, autoReply: String, registry: ToolRegistry): ToolResult {
        // Enable DND for first work block
        registry.executeTool("toggle_setting", mapOf("setting" to "dnd", "state" to "on"))

        var offsetMs = 0L
        val now = System.currentTimeMillis()

        for (i in 1..cycles) {
            // Work block end
            offsetMs += workMin * 60_000L
            val workEnd = AssistantStore.create(
                type = AssistantItemType.REMINDER,
                title = if (i < cycles) "⏸ Descanso — ciclo $i/$cycles" else "🎯 ¡Pomodoro completado! ($cycles ciclos)",
                body = if (i < cycles) "Tómate $breakMin min de descanso. Lo estás haciendo bien." else "Has completado $cycles pomodoros de $workMin min. ¡Excelente!",
                triggerAtMs = now + offsetMs, source = "ai",
            )
            AssistantScheduler.arm(ClawApplication.instance, workEnd)

            // Break (except after last cycle)
            if (i < cycles) {
                offsetMs += breakMin * 60_000L
                val breakEnd = AssistantStore.create(
                    type = AssistantItemType.REMINDER,
                    title = "▶️ Vuelta al trabajo — ciclo ${i + 1}/$cycles",
                    body = "Descanso terminado. ¡A por el siguiente pomodoro!",
                    triggerAtMs = now + offsetMs, source = "ai",
                )
                AssistantScheduler.arm(ClawApplication.instance, breakEnd)
            }
        }

        // Save state
        val totalMin = cycles * workMin + (cycles - 1) * breakMin
        KVUtils.putString("focus_mode_active", "true")
        KVUtils.putLong("focus_mode_end", now + offsetMs)
        KVUtils.putString("focus_mode_label", "Pomodoro: $label")
        if (autoReply.isNotBlank()) KVUtils.putString("focus_mode_auto_reply", autoReply)
        KVUtils.sync()

        return ToolResult.success(
            "🍅 Pomodoro iniciado: $label\n" +
            "📋 $cycles ciclos × $workMin min trabajo + $breakMin min descanso\n" +
            "⏱ Total: $totalMin min\n" +
            "🔕 DND activado\n" +
            "Te avisaré en cada transición."
        )
    }
}

class FocusStopTool : BaseTool() {
    override fun getName() = "focus_stop"
    override fun getDisplayName() = "Parar Focus"
    override fun getDescriptionEN() = "Stop focus/pomodoro mode early. Disables DND and clears auto-reply."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "detiene el modo focus/pomodoro antes de tiempo"
    override fun getParameters() = emptyList<ToolParameter>()
    override fun execute(params: Map<String, Any>): ToolResult {
        val wasActive = KVUtils.getString("focus_mode_active", "") == "true"
        if (!wasActive) return ToolResult.success("No hay modo focus activo.")

        // Disable DND
        ToolRegistry.getInstance().executeTool("toggle_setting", mapOf("setting" to "dnd", "state" to "off"))

        // Clear state
        val label = KVUtils.getString("focus_mode_label", "Focus")
        val startedAt = KVUtils.getLong("focus_mode_end", 0) - 60 * 60_000L // approximate
        KVUtils.putString("focus_mode_active", "")
        KVUtils.putString("focus_mode_auto_reply", "")
        KVUtils.putLong("focus_mode_end", 0)
        KVUtils.sync()

        return ToolResult.success("🛑 Modo focus '$label' detenido. DND desactivado.")
    }
}
