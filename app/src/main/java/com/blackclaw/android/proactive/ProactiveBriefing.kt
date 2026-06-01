package com.blackclaw.android.proactive

import com.blackclaw.android.agent.llm.LlmSessionManager
import com.blackclaw.android.assistant.AssistantItemType
import com.blackclaw.android.assistant.AssistantReceiver
import com.blackclaw.android.assistant.AssistantStore
import com.blackclaw.android.assistant.AssistantTime
import com.blackclaw.android.assistant.Speaker
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.ToolRegistry
import com.blackclaw.android.utils.XLog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Generates morning and night briefings — the feature that makes BlackClaw feel
 * like a real assistant that "manages your day" rather than just reacting.
 *
 *  - Morning: what's ahead today (events, reminders, alarms), weather if
 *    available, unread highlights — plus proactive suggestions ("you have a
 *    meeting at 9, want me to set an alarm?").
 *  - Night: tomorrow preview + gentle prep ("meeting at 9 tomorrow; I can wake
 *    you at 7:30").
 *
 * Each briefing is ONE cheap LLM call over a gathered state snapshot, then
 * surfaced as a native push notification and logged in the assistant hub as an
 * ALERT so the user can revisit it.
 */
object ProactiveBriefing {

    private const val TAG = "ProactiveBriefing"

    enum class Kind { MORNING, NIGHT }

    fun run(kind: Kind) {
        if (!ProactiveConfig.enabled) return
        try {
            val state = gatherState(kind)
            val text = summarize(kind, state) ?: fallback(kind, state)
            val title = if (kind == Kind.MORNING) "☀️ Buenos días" else "🌙 Resumen de la noche"
            // Log to hub as an alert and push it.
            AssistantStore.create(
                type = AssistantItemType.ALERT, title = title, body = text, source = "ai",
            )
            AssistantReceiver.postNotification(ClawApplication.instance, title, text, highPriority = false)
            if (ProactiveConfig.speakBriefings) {
                Speaker.speak("$title. $text")
            }
            XLog.i(TAG, "$kind briefing delivered")
        } catch (e: Throwable) {
            XLog.w(TAG, "Briefing failed: ${e.message}")
        }
    }

    /** Collect a compact snapshot of what the assistant knows for the window. */
    private fun gatherState(kind: Kind): String {
        val sb = StringBuilder()
        val now = Calendar.getInstance()
        val df = SimpleDateFormat("EEEE dd MMM HH:mm", Locale.getDefault())
        sb.appendLine("Ahora: ${df.format(now.time)}")

        // Window: today (morning) or tomorrow (night).
        val windowStart: Long
        val windowEnd: Long
        if (kind == Kind.MORNING) {
            windowStart = System.currentTimeMillis()
            windowEnd = endOfDay(now)
        } else {
            val tomorrow = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
            windowStart = startOfDay(tomorrow)
            windowEnd = endOfDay(tomorrow)
        }

        // Assistant hub items in the window.
        val hub = AssistantStore.all().filter {
            it.triggerAtMs in windowStart..windowEnd && !it.done
        }.sortedBy { it.triggerAtMs }
        if (hub.isNotEmpty()) {
            sb.appendLine("Agenda del asistente:")
            hub.forEach {
                sb.appendLine("- ${it.type.name.lowercase()} ${AssistantTime.format(it.triggerAtMs)}: ${it.title}")
            }
        }

        // Pending reminders/notes (no time) worth surfacing.
        val pendingNotes = AssistantStore.byType(AssistantItemType.NOTE).filter { !it.done }.take(5)
        if (pendingNotes.isNotEmpty()) {
            sb.appendLine("Notas/pendientes:")
            pendingNotes.forEach { sb.appendLine("- ${it.title}") }
        }

        // Finance snapshot at night.
        if (kind == Kind.NIGHT) {
            val bal = AssistantStore.financeBalance()
            if (bal != 0.0) sb.appendLine("Balance finanzas: ${"%.2f".format(bal)}")
        }

        // System calendar events in the window (best-effort via existing tool).
        runCatching {
            val days = if (kind == Kind.MORNING) 1 else 2
            val cal = ToolRegistry.getInstance().getTool("get_calendar_events")
                ?.execute(mapOf("days_ahead" to days))
            if (cal?.isSuccess == true && !cal.data.isNullOrBlank()) {
                sb.appendLine("Calendario del sistema:")
                sb.appendLine(cal.data!!.take(800))
            }
        }

        // Weather (best-effort, morning only).
        if (kind == Kind.MORNING) {
            runCatching {
                val w = ToolRegistry.getInstance().getTool("weather")?.execute(emptyMap())
                if (w?.isSuccess == true && !w.data.isNullOrBlank()) {
                    sb.appendLine("Clima: ${w.data!!.take(300)}")
                }
            }
        }
        return sb.toString().trim()
    }

    private fun summarize(kind: Kind, state: String): String? {
        if (state.isBlank()) return null
        val prompt = buildString {
            appendLine(if (kind == Kind.MORNING)
                "Eres el asistente personal del usuario. Dale un briefing matutino breve, cálido y útil."
            else
                "Eres el asistente personal del usuario. Dale un resumen nocturno breve para preparar mañana.")
            appendLine()
            appendLine("Datos disponibles:")
            appendLine(state)
            appendLine()
            ProactiveMemory.preferencesSnippet().takeIf { it.isNotBlank() }?.let { appendLine(it); appendLine() }
            appendLine("Instrucciones:")
            appendLine("- Máximo 4-5 líneas, en español, tono natural (no robótico).")
            appendLine("- Menciona lo importante del día/mañana y, si ves algo accionable")
            appendLine("  (p.ej. reunión temprano sin alarma), SUGIÉRELO en una frase.")
            appendLine("- Si no hay nada relevante, dilo en una línea amable.")
            appendLine("- No inventes datos que no estén arriba.")
        }
        return LlmSessionManager.singleShot(prompt, 0.4)?.trim()?.takeIf { it.isNotBlank() }
    }

    /** If the LLM is unavailable, still deliver a useful deterministic summary. */
    private fun fallback(kind: Kind, state: String): String {
        return if (state.isBlank()) {
            if (kind == Kind.MORNING) "Buenos días. No tienes nada agendado por ahora."
            else "Nada pendiente para mañana. Buenas noches."
        } else {
            (if (kind == Kind.MORNING) "Tu día:\n" else "Mañana:\n") + state.take(400)
        }
    }

    private fun startOfDay(c: Calendar): Long = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun endOfDay(c: Calendar): Long = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
    }.timeInMillis
}
