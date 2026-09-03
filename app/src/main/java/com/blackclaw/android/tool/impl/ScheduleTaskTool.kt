package com.blackclaw.android.tool.impl

import com.blackclaw.android.ClawApplication
import com.blackclaw.android.scheduler.ScheduledTaskManager
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Schedule a task or chat message to run at a specific time, optionally repeating.
 *
 * Time formats accepted:
 *   - "in 30m", "in 2h", "in 1d"   → relative offset
 *   - "today 14:30", "tomorrow 09:00" → relative day with HH:mm
 *   - "2026-12-31 23:59"           → absolute ISO datetime
 *
 * Recurrence: once | hourly | daily | weekly | interval (with interval_minutes).
 */
class ScheduleTaskTool : BaseTool() {

    override fun getName() = "schedule_task"
    override fun getDisplayName() = "Schedule Task"
    override fun getDescriptionEN() =
        "Schedule a task or chat to run later or on a recurring basis. " +
        "Use this when the user asks to remind, repeat, or run something automatically. " +
        "Examples: 'in 30m', 'tomorrow 09:00', '2026-12-31 23:59'. " +
        "Recurrence: once, hourly, daily, weekly, interval (with interval_minutes)."

    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("text", "string",
            "The task or chat message to send when the schedule fires (e.g. 'send hi to Mom on WhatsApp')", true),
        ToolParameter("when", "string",
            "When to run. 'in 30m', 'in 2h', 'today 14:30', 'tomorrow 09:00', or '2026-12-31 23:59'.", true),
        ToolParameter("mode", "string",
            "Either 'task' (run as agent task) or 'chat' (send as chat message). Default: task.", false),
        ToolParameter("recurrence", "string",
            "How often to repeat: once | hourly | daily | weekly | interval. Default: once.", false),
        ToolParameter("interval_minutes", "integer",
            "Required when recurrence='interval'. Minimum 1.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val text = requireString(params, "text").trim()
        if (text.isEmpty()) return ToolResult.error("text cannot be empty")
        val whenStr = requireString(params, "when").trim()

        val triggerMs = parseWhen(whenStr)
            ?: return ToolResult.error(
                "Could not parse 'when'. Use 'in 30m', 'today 14:30', 'tomorrow 09:00', or '2026-12-31 23:59'."
            )

        val now = System.currentTimeMillis()
        if (triggerMs <= now + 1_000L) {
            return ToolResult.error("Schedule time must be in the future. Got: ${formatTime(triggerMs)}")
        }

        val modeStr = optionalString(params, "mode", "task").lowercase()
        val mode = when (modeStr) {
            "task" -> ScheduledTaskManager.Mode.TASK
            "chat" -> ScheduledTaskManager.Mode.CHAT
            else -> return ToolResult.error("mode must be 'task' or 'chat'")
        }

        val recurStr = optionalString(params, "recurrence", "once").lowercase()
        val recurrence = when (recurStr) {
            "once" -> ScheduledTaskManager.Recurrence.ONCE
            "hourly", "cada_hora", "cada hora" -> ScheduledTaskManager.Recurrence.HOURLY
            "daily", "diario", "diaria" -> ScheduledTaskManager.Recurrence.DAILY
            "weekly", "semanal" -> ScheduledTaskManager.Recurrence.WEEKLY
            "interval", "intervalo" -> ScheduledTaskManager.Recurrence.INTERVAL
            else -> return ToolResult.error("recurrence must be once|hourly|daily|weekly|interval")
        }

        val intervalMinutes = optionalInt(params, "interval_minutes", 0)
        if (recurrence == ScheduledTaskManager.Recurrence.INTERVAL && intervalMinutes <= 0) {
            return ToolResult.error("interval_minutes is required when recurrence='interval'")
        }

        val task = ScheduledTaskManager.schedule(
            context = ClawApplication.instance,
            mode = mode,
            text = text,
            triggerAtMs = triggerMs,
            recurrence = recurrence,
            intervalMs = intervalMinutes.toLong() * 60_000L,
        ) ?: return ToolResult.error("Could not store the schedule securely. Try again after unlocking the device.")

        return ToolResult.success(
            "Scheduled $modeStr [${task.id}] for ${formatTime(triggerMs)} ($recurStr): \"$text\""
        )
    }

    /** Parse natural-ish time strings into an absolute epoch ms. */
    internal fun parseWhen(input: String, nowMs: Long = System.currentTimeMillis()): Long? {
        val s = input.trim().lowercase(Locale.ROOT)
            .replace('á', 'a').replace('é', 'e').replace('í', 'i').replace('ó', 'o').replace('ú', 'u')
            .replace('ñ', 'n')

        // Relative: "in 30m" / "in 2h" / "in 1d"
        val rel = Regex("""^(?:in|en)\s+(\d+)\s*(m|min|mins|minutes|minuto|minutos|h|hr|hrs|hours|hora|horas|d|day|days|dia|dias)$""").matchEntire(s)
        if (rel != null) {
            val n = rel.groupValues[1].toLong()
            val unit = rel.groupValues[2]
            val ms = when {
                unit.startsWith("d") -> n * 24L * 60L * 60_000L
                unit.startsWith("h") -> n * 60L * 60_000L
                else -> n * 60_000L
            }
            return nowMs + ms
        }

        // Today/tomorrow with HH:mm
        val rel2 = Regex("""^(today|tomorrow|hoy|manana)(?:\s+a\s+las)?\s+(\d{1,2}):(\d{2})$""").matchEntire(s)
        if (rel2 != null) {
            val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
            if (rel2.groupValues[1] in setOf("tomorrow", "manana")) cal.add(Calendar.DAY_OF_YEAR, 1)
            cal.set(Calendar.HOUR_OF_DAY, rel2.groupValues[2].toInt())
            cal.set(Calendar.MINUTE, rel2.groupValues[3].toInt())
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        // Absolute ISO-ish: yyyy-MM-dd HH:mm
        val patterns = listOf(
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy/MM/dd HH:mm",
        )
        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.getDefault())
                sdf.isLenient = false
                return sdf.parse(input.trim())?.time
            } catch (_: Exception) {}
        }

        // Plain HH:mm → next occurrence today or tomorrow if past
        val timeOnly = Regex("""^(?:a\s+las\s+)?(\d{1,2}):(\d{2})$""").matchEntire(s)
        if (timeOnly != null) {
            val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
            cal.set(Calendar.HOUR_OF_DAY, timeOnly.groupValues[1].toInt())
            cal.set(Calendar.MINUTE, timeOnly.groupValues[2].toInt())
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (cal.timeInMillis <= nowMs) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }

        return null
    }

    private fun formatTime(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
}
