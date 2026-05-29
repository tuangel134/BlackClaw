package com.blackclaw.android.tool.impl

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Date arithmetic tool — the LLM is bad at calendar maths and timezones.
 * Supports operations: add, diff, format, day_of_week.
 */
class DateMathTool : BaseTool() {
    override fun getName() = "date_math"
    override fun getDisplayName() = "Calendario"
    override fun getDescriptionEN() =
        "Date math. ops: 'add' (date + amount + unit), 'diff' (between two dates), " +
        "'day_of_week' (which weekday is a date), 'format' (reformat date)."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("op", "string", "add | diff | day_of_week | format", true),
        ToolParameter("date", "string", "Fecha base (yyyy-MM-dd o 'today').", false),
        ToolParameter("date2", "string", "Segunda fecha (para diff).", false),
        ToolParameter("amount", "integer", "(add) cuánto sumar.", false),
        ToolParameter("unit", "string", "(add) day | week | month | year. Default day.", false),
        ToolParameter("format", "string", "(format) patrón Java SimpleDateFormat.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val op = requireString(params, "op").lowercase()
        val date = parseDate(optionalString(params, "date", "today")) ?: return ToolResult.error("date inválida")
        return try {
            when (op) {
                "add" -> {
                    val amt = optionalInt(params, "amount", 0)
                    val unit = optionalString(params, "unit", "day").lowercase()
                    val cal = Calendar.getInstance().apply { time = date }
                    cal.add(when (unit) {
                        "day", "" -> Calendar.DAY_OF_YEAR
                        "week" -> Calendar.WEEK_OF_YEAR
                        "month" -> Calendar.MONTH
                        "year" -> Calendar.YEAR
                        "hour" -> Calendar.HOUR_OF_DAY
                        "minute" -> Calendar.MINUTE
                        else -> return ToolResult.error("unit desconocido")
                    }, amt)
                    ToolResult.success(SimpleDateFormat("yyyy-MM-dd EEE", Locale.getDefault()).format(cal.time))
                }
                "diff" -> {
                    val date2 = parseDate(optionalString(params, "date2", "today"))
                        ?: return ToolResult.error("date2 inválida")
                    val days = abs(date2.time - date.time) / 86_400_000L
                    val years = days / 365
                    val months = (days % 365) / 30
                    val rem = (days % 365) % 30
                    ToolResult.success("$days días (~${years}a ${months}m ${rem}d)")
                }
                "day_of_week" -> {
                    val dow = SimpleDateFormat("EEEE", Locale.getDefault()).format(date)
                    ToolResult.success(dow)
                }
                "format" -> {
                    val pattern = optionalString(params, "format", "yyyy-MM-dd")
                    ToolResult.success(SimpleDateFormat(pattern, Locale.getDefault()).format(date))
                }
                else -> ToolResult.error("op desconocido '$op'")
            }
        } catch (e: Exception) {
            ToolResult.error("date_math falló: ${e.message}")
        }
    }

    private fun parseDate(input: String): Date? {
        val s = input.trim().lowercase()
        if (s == "today" || s == "hoy" || s == "now") return Date()
        if (s == "yesterday" || s == "ayer") {
            val c = Calendar.getInstance(); c.add(Calendar.DAY_OF_YEAR, -1); return c.time
        }
        if (s == "tomorrow" || s == "mañana") {
            val c = Calendar.getInstance(); c.add(Calendar.DAY_OF_YEAR, 1); return c.time
        }
        for (p in listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd", "dd/MM/yyyy", "MM/dd/yyyy")) {
            try {
                return SimpleDateFormat(p, Locale.getDefault()).apply { isLenient = false }.parse(input.trim())
            } catch (_: Exception) {}
        }
        return null
    }
}
