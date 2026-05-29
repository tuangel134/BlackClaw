package com.blackclaw.android.tool.impl

import android.content.Intent
import android.provider.CalendarContract
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Creates a calendar event by launching the system calendar's INSERT intent.
 * The user confirms in the calendar UI — this is the safe path that never
 * silently writes events. Works without WRITE_CALENDAR.
 */
class CreateCalendarEventTool : BaseTool() {
    override fun getName() = "create_calendar_event"
    override fun getDisplayName() = "Create Event"
    override fun getDescriptionEN() =
        "Create a calendar event. Opens the system calendar app pre-filled with " +
        "the title, time, and optional location/description so the user can confirm. " +
        "Time format: 'in 30m', 'today 14:30', 'tomorrow 09:00', or '2026-12-31 23:59'."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("title", "string", "Event title", true),
        ToolParameter("start", "string", "When the event starts.", true),
        ToolParameter("duration_minutes", "integer", "Length in minutes. Default 60.", false),
        ToolParameter("location", "string", "Optional location string.", false),
        ToolParameter("description", "string", "Optional description / notes.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val title = requireString(params, "title").trim()
        if (title.isEmpty()) return ToolResult.error("title cannot be empty")
        val whenStr = requireString(params, "start").trim()
        val duration = optionalInt(params, "duration_minutes", 60).coerceAtLeast(5)
        val location = optionalString(params, "location", "")
        val description = optionalString(params, "description", "")

        val startMs = parseTime(whenStr)
            ?: return ToolResult.error("Could not parse 'start'. Use 'in 30m', 'today 14:30', 'tomorrow 09:00', or '2026-12-31 23:59'.")
        val endMs = startMs + duration.toLong() * 60_000L

        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
            putExtra(CalendarContract.Events.TITLE, title)
            if (location.isNotBlank()) putExtra(CalendarContract.Events.EVENT_LOCATION, location)
            if (description.isNotBlank()) putExtra(CalendarContract.Events.DESCRIPTION, description)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            ClawApplication.instance.startActivity(intent)
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            ToolResult.success(
                "Calendar opened with: \"$title\" on ${df.format(Date(startMs))} ($duration min). " +
                "User must tap Save."
            )
        } catch (e: Exception) {
            ToolResult.error("No calendar app available: ${e.message}")
        }
    }

    private fun parseTime(input: String): Long? {
        val s = input.trim().lowercase(Locale.ROOT)
        Regex("""^in\s+(\d+)\s*(m|min|mins|minutes|h|hr|hrs|hours|d|day|days)$""").matchEntire(s)?.let {
            val n = it.groupValues[1].toLong()
            val unit = it.groupValues[2]
            val ms = when {
                unit.startsWith("d") -> n * 24L * 60L * 60_000L
                unit.startsWith("h") -> n * 60L * 60_000L
                else -> n * 60_000L
            }
            return System.currentTimeMillis() + ms
        }
        Regex("""^(today|tomorrow)\s+(\d{1,2}):(\d{2})$""").matchEntire(s)?.let {
            val cal = Calendar.getInstance()
            if (it.groupValues[1] == "tomorrow") cal.add(Calendar.DAY_OF_YEAR, 1)
            cal.set(Calendar.HOUR_OF_DAY, it.groupValues[2].toInt())
            cal.set(Calendar.MINUTE, it.groupValues[3].toInt())
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
        for (pattern in listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm")) {
            try {
                return SimpleDateFormat(pattern, Locale.getDefault()).apply { isLenient = false }
                    .parse(input.trim())?.time
            } catch (_: Exception) {}
        }
        return null
    }
}
