package com.blackclaw.android.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.blackclaw.android.utils.XLog

/**
 * Read-only bridge to the device's system calendar (Google Calendar, etc.).
 * Lets BlackClaw's own calendar view overlay the user's real events alongside
 * its native items — a one-way sync that needs only READ_CALENDAR.
 *
 * Events are returned as transient [AssistantItem]s (source = "system"); they
 * are NOT persisted into AssistantStore, just rendered.
 */
object SystemCalendar {

    private const val TAG = "SystemCalendar"

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * System events between [fromMs] and [toMs], as transient EVENT items.
     * Returns empty if permission is missing or on any error.
     */
    fun events(context: Context, fromMs: Long, toMs: Long): List<AssistantItem> {
        if (!hasPermission(context)) return emptyList()
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.ALL_DAY,
        )
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND " +
            "${CalendarContract.Events.DTSTART} <= ? AND ${CalendarContract.Events.DELETED} = 0"
        val args = arrayOf(fromMs.toString(), toMs.toString())
        val sort = "${CalendarContract.Events.DTSTART} ASC"
        return try {
            val out = ArrayList<AssistantItem>()
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI, projection, selection, args, sort
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(CalendarContract.Events._ID)
                val titleIdx = c.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
                val startIdx = c.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
                val locIdx = c.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION)
                while (c.moveToNext()) {
                    val start = c.getLong(startIdx)
                    val title = c.getString(titleIdx) ?: "(evento)"
                    val loc = runCatching { c.getString(locIdx) }.getOrNull().orEmpty()
                    out.add(
                        AssistantItem(
                            id = "sys_" + c.getLong(idIdx),
                            type = AssistantItemType.EVENT,
                            title = title,
                            body = if (loc.isNotBlank()) "📍 $loc" else "",
                            triggerAtMs = start,
                            source = "system",
                        )
                    )
                }
            }
            out
        } catch (e: Exception) {
            XLog.w(TAG, "System calendar read failed: ${e.message}")
            emptyList()
        }
    }
}
