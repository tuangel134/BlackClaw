package com.blackclaw.android.tool.impl

import android.Manifest
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reads the recent call log via the Call Log Provider.
 * Requires READ_CALL_LOG.
 */
class GetCallLogTool : BaseTool() {
    override fun getName() = "get_call_log"
    override fun getDisplayName() = "Call Log"
    override fun getDescriptionEN() =
        "List recent calls (incoming/outgoing/missed). Use for 'who called me', 'did I call mom'. " +
        "Returns name, number, type, time, duration."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("limit", "integer", "Max entries to return. Default 10, max 30.", false),
        ToolParameter("type", "string",
            "Optional filter: 'incoming', 'outgoing', 'missed', or 'all' (default).", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val ctx = ClawApplication.instance
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED) {
            return ToolResult.error(
                "READ_CALL_LOG permission not granted. Grant it in Settings > Apps > BlackClaw > Permissions."
            )
        }

        val limit = optionalInt(params, "limit", 10).coerceIn(1, 30)
        val typeFilter = optionalString(params, "type", "all").lowercase()
        val typeCode = when (typeFilter) {
            "incoming", "in" -> CallLog.Calls.INCOMING_TYPE
            "outgoing", "out" -> CallLog.Calls.OUTGOING_TYPE
            "missed" -> CallLog.Calls.MISSED_TYPE
            else -> -1
        }

        val projection = arrayOf(
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
        )

        val selection: String?
        val args: Array<String>?
        if (typeCode >= 0) {
            selection = "${CallLog.Calls.TYPE} = ?"
            args = arrayOf(typeCode.toString())
        } else {
            selection = null
            args = null
        }

        val df = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
        val results = mutableListOf<String>()

        return try {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection, selection, args,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext() && results.size < limit) {
                    val name = cursor.getString(0)?.takeIf { it.isNotBlank() }
                    val number = cursor.getString(1) ?: "(unknown)"
                    val type = cursor.getInt(2)
                    val date = cursor.getLong(3)
                    val durationSec = cursor.getLong(4)

                    val typeLabel = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> "in"
                        CallLog.Calls.OUTGOING_TYPE -> "out"
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        CallLog.Calls.REJECTED_TYPE -> "rejected"
                        CallLog.Calls.BLOCKED_TYPE -> "blocked"
                        else -> "?"
                    }
                    val durStr = if (durationSec > 0) " (${formatDuration(durationSec)})" else ""
                    val who = name ?: number
                    results.add("[${df.format(Date(date))}] $typeLabel · $who$durStr")
                }
            }
            if (results.isEmpty()) ToolResult.success("No matching calls.")
            else ToolResult.success("${results.size} call(s):\n" + results.joinToString("\n"))
        } catch (e: SecurityException) {
            ToolResult.error("Call log access denied: ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("Failed to read call log: ${e.message}")
        }
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds < 60) return "${seconds}s"
        val m = seconds / 60
        val s = seconds % 60
        return if (s == 0L) "${m}m" else "${m}m${s}s"
    }
}
