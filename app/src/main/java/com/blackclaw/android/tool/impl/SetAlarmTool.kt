package com.blackclaw.android.tool.impl

import android.content.Intent
import android.provider.AlarmClock
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.cards.AssistCard
import com.blackclaw.android.cards.AssistCardCodec
import com.blackclaw.android.cards.SummaryKind
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Sets a clock alarm or a quick countdown timer via the system AlarmClock intents.
 * Works without any permissions and is silent (no UI flash on most devices).
 */
class SetAlarmTool : BaseTool() {
    override fun getName() = "set_alarm"
    override fun getDisplayName() = "Set Alarm"
    override fun getDescriptionEN() =
        "Set a clock alarm or countdown timer. " +
        "mode='alarm' needs hour & minute (24h). mode='timer' needs duration_seconds. " +
        "Examples: alarm at 7:30 → mode=alarm, hour=7, minute=30. " +
        "10-minute timer → mode=timer, duration_seconds=600."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("mode", "string", "alarm | timer", true),
        ToolParameter("hour", "integer", "(alarm) hour 0-23", false),
        ToolParameter("minute", "integer", "(alarm) minute 0-59", false),
        ToolParameter("duration_seconds", "integer", "(timer) total seconds 1..86400", false),
        ToolParameter("label", "string", "Optional label for the alarm/timer", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val mode = requireString(params, "mode").lowercase().trim()
        val label = optionalString(params, "label", "")
        val ctx = ClawApplication.instance
        return try {
            when (mode) {
                "alarm" -> {
                    val hour = optionalInt(params, "hour", -1)
                    val minute = optionalInt(params, "minute", -1)
                    if (hour !in 0..23) return ToolResult.error("hour must be 0..23")
                    if (minute !in 0..59) return ToolResult.error("minute must be 0..59")
                    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        if (label.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(intent)
                    val labelStr = if (label.isNotEmpty()) " (\"$label\")" else ""
                    val result = "Alarm set for %02d:%02d%s".format(hour, minute, labelStr)
                    ToolResult.successWithCards(result, AssistCardCodec.encode(listOf(AssistCard.Summary(
                        SummaryKind.TIMER, "Alarma programada", "%02d:%02d".format(hour, minute),
                        label.ifBlank { "Se abrirá la alarma del sistema." },
                    ))))
                }
                "timer" -> {
                    val secs = optionalInt(params, "duration_seconds", 0)
                    if (secs !in 1..86_400) return ToolResult.error("duration_seconds must be 1..86400")
                    val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(AlarmClock.EXTRA_LENGTH, secs)
                        if (label.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(intent)
                    val minutes = secs / 60
                    val value = if (minutes > 0 && secs % 60 == 0) "$minutes min" else "$secs s"
                    ToolResult.successWithCards("Timer started for ${secs}s", AssistCardCodec.encode(listOf(AssistCard.Summary(
                        SummaryKind.TIMER, "Temporizador iniciado", value,
                        label.ifBlank { "Se ejecutará en la app de reloj." },
                    ))))
                }
                else -> ToolResult.error("mode must be 'alarm' or 'timer'")
            }
        } catch (e: Exception) {
            ToolResult.error("Failed to set ${mode}: ${e.message}")
        }
    }
}
