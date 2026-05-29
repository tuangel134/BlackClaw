package com.blackclaw.android.tool.impl

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Send an SMS via the system messaging app (intent path) or directly with SmsManager
 * if SEND_SMS is granted.
 *
 * Default mode is 'compose' — opens the SMS app pre-filled and the user taps Send.
 * That keeps the agent honest: no silent SMS spending the user's plan.
 * 'direct' uses SmsManager.sendTextMessage and requires SEND_SMS permission.
 */
class SendSmsTool : BaseTool() {
    override fun getName() = "send_sms"
    override fun getDisplayName() = "Send SMS"
    override fun getDescriptionEN() =
        "Send an SMS. Default mode='compose' opens the SMS app pre-filled (user taps Send). " +
        "mode='direct' sends silently with SmsManager (requires SEND_SMS permission). " +
        "Use compose unless the user explicitly says 'send without confirmation'."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("phone", "string", "Recipient phone number (e.g. '+34600123456').", true),
        ToolParameter("message", "string", "Message body.", true),
        ToolParameter("mode", "string", "compose (default) | direct", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val phone = requireString(params, "phone").trim()
        val msg = requireString(params, "message")
        if (phone.isEmpty()) return ToolResult.error("phone cannot be empty")
        if (msg.isEmpty()) return ToolResult.error("message cannot be empty")
        val mode = optionalString(params, "mode", "compose").lowercase()

        val ctx = ClawApplication.instance
        return when (mode) {
            "compose", "" -> {
                try {
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("smsto:$phone")
                        putExtra("sms_body", msg)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(intent)
                    ToolResult.success("SMS draft to $phone opened. User must tap Send.")
                } catch (e: Exception) {
                    ToolResult.error("No SMS app available: ${e.message}")
                }
            }
            "direct" -> {
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS)
                    != PackageManager.PERMISSION_GRANTED) {
                    return ToolResult.error(
                        "SEND_SMS permission not granted. Use mode='compose' or grant SEND_SMS first."
                    )
                }
                try {
                    val sms = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        ctx.getSystemService(android.telephony.SmsManager::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        android.telephony.SmsManager.getDefault()
                    }
                    val parts = sms.divideMessage(msg)
                    if (parts.size <= 1) {
                        sms.sendTextMessage(phone, null, msg, null, null)
                    } else {
                        sms.sendMultipartTextMessage(phone, null, parts, null, null)
                    }
                    ToolResult.success("SMS sent to $phone (${msg.length} chars).")
                } catch (e: Exception) {
                    ToolResult.error("Failed to send SMS: ${e.message}")
                }
            }
            else -> ToolResult.error("mode must be 'compose' or 'direct'")
        }
    }
}
