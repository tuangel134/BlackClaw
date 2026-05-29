package com.blackclaw.android.tool.impl

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reads upcoming events from the device calendar via the Calendar Provider.
 *
 * Requires READ_CALENDAR — the runtime permission must be granted by the user once.
 * Without the permission this tool returns an actionable error so the agent can ask
 * the user (or open Settings via toggle_setting equivalent) to grant it.
 */
class GetCalendarEventsTool : BaseTool() {
    override fun getName() = "get_calendar_events"
    override fun getDisplayName() = "Calendar Events"
    override fun getDescriptionEN() =
        "List upcoming calendar events within the next N hours (default 24). " +
        "Returns title, start time, end time, location, and calendar name. " +
        "Use this for 'what's on my calendar', 'next meeting', 'am I free at 3pm'."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("hours_ahead", "integer",
            "Look ahead N hours from now. Default 24, max 720 (30 days).", false),
        ToolParameter("limit", "integer",
            "Max number of events to return. Default 20, max 50.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val ctx = ClawApplication.instance
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED) {
            return ToolResult.error(
                "READ_CALENDAR permission not granted. Grant it in Settings > Apps > BlackClaw > Permissions."
            )
        }

        val hoursAhead = optionalInt(params, "hours_ahead", 24).coerceIn(1, 720)
        val limit = optionalInt(params, "limit", 20).coerceIn(1, 50)
        val now = System.currentTimeMillis()
        val end = now + hoursAhead * 60L * 60_000L

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME,
            CalendarContract.Events.ALL_DAY,
        )

        val selection =
            "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ? " +
            "AND ${CalendarContract.Events.DELETED} = 0"
        val args = arrayOf(now.toString(), end.toString())
        val sortOrder = "${CalendarContract.Events.DTSTART} ASC"

        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val results = mutableListOf<String>()

        return try {
            ctx.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection, selection, args, sortOrder
            )?.use { cursor ->
                while (cursor.moveToNext() && results.size < limit) {
                    val title = cursor.getString(1) ?: "(no title)"
                    val dtStart = cursor.getLong(2)
                    val dtEnd = cursor.getLong(3)
                    val location = cursor.getString(4)?.takeIf { it.isNotBlank() }
                    val calName = cursor.getString(5)?.takeIf { it.isNotBlank() }
                    val allDay = cursor.getInt(6) == 1
                    val whenStr = if (allDay) df.format(Date(dtStart)).take(10) + " (all day)"
                                  else "${df.format(Date(dtStart))} → ${df.format(Date(dtEnd))}"
                    val parts = mutableListOf("[$whenStr] $title")
                    location?.let { parts.add("at $it") }
                    calName?.let { parts.add("(${it})") }
                    results.add(parts.joinToString(" "))
                }
            }
            if (results.isEmpty()) {
                ToolResult.success("No events in the next $hoursAhead hours.")
            } else {
                ToolResult.success("${results.size} event(s):\n" + results.joinToString("\n"))
            }
        } catch (e: SecurityException) {
            ToolResult.error("Calendar access denied: ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("Failed to read calendar: ${e.message}")
        }
    }
}
