package com.blackclaw.android.tool.impl

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reads recent SMS messages from the system inbox.
 * Requires READ_SMS — without it the user is asked to grant it.
 */
class GetSmsTool : BaseTool() {
    override fun getName() = "get_sms"
    override fun getDisplayName() = "Read SMS"
    override fun getDescriptionEN() =
        "List recent SMS messages from the inbox. Use for 'check my texts', 'did Mom text me'. " +
        "Returns sender, time, body. Optional 'from' filters by sender (substring match)."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("limit", "integer", "Max messages to return. Default 10, max 30.", false),
        ToolParameter("from", "string",
            "Optional substring filter on sender (matches address or contact name).", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val ctx = ClawApplication.instance
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            return ToolResult.error(
                "READ_SMS permission not granted. Grant it in Settings > Apps > BlackClaw > Permissions."
            )
        }

        val limit = optionalInt(params, "limit", 10).coerceIn(1, 30)
        val fromFilter = optionalString(params, "from", "").trim().lowercase()

        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.READ,
        )

        val df = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
        val results = mutableListOf<String>()

        return try {
            ctx.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                projection, null, null,
                "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext() && results.size < limit) {
                    val address = cursor.getString(1) ?: ""
                    val body = cursor.getString(2)?.replace("\n", " ") ?: ""
                    val date = cursor.getLong(3)
                    val read = cursor.getInt(4) == 1

                    if (fromFilter.isNotEmpty() && !address.lowercase().contains(fromFilter)) continue
                    val unreadMark = if (read) "" else " [unread]"
                    val truncated = if (body.length > 200) body.take(200) + "…" else body
                    results.add("[${df.format(Date(date))}] $address$unreadMark: $truncated")
                }
            }
            if (results.isEmpty()) ToolResult.success("No matching SMS.")
            else ToolResult.success("${results.size} message(s):\n" + results.joinToString("\n"))
        } catch (e: SecurityException) {
            ToolResult.error("SMS access denied: ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("Failed to read SMS: ${e.message}")
        }
    }
}
