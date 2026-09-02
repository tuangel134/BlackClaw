package com.blackclaw.android.proactive

import com.blackclaw.android.agent.llm.LlmSessionManager
import com.blackclaw.android.assistant.AssistantItemType
import com.blackclaw.android.assistant.AssistantReceiver
import com.blackclaw.android.assistant.AssistantScheduler
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

    enum class Kind { MORNING, NIGHT, WEEKLY }

    fun run(kind: Kind) {
        if (!ProactiveConfig.enabled) return
        try {
            if (kind == Kind.WEEKLY) { runWeeklyFinance(); return }
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
            // Surprise alarms are opt-in. Even when enabled, autoSetMorningAlarms only
            // considers user-created items or AI items linked to a confirmed commitment.
            if (kind == Kind.NIGHT && ProactiveConfig.autoMorningAlarms) {
                runCatching { autoSetMorningAlarms() }
            }
            // After the morning briefing, auto-create detected habits
            if (kind == Kind.MORNING) runCatching { surfaceHabitSuggestion() }
            // Periodic profile learning from interaction patterns
            runCatching { com.blackclaw.android.memory.UserProfile.learnFromInteractions() }
            XLog.i(TAG, "$kind briefing delivered")
        } catch (e: Throwable) {
            XLog.w(TAG, "Briefing failed: ${e.message}")
        }
    }

    /**
     * Night briefing: check if tomorrow has early events (before 10am) that
     * don't have a corresponding alarm. If so, auto-create an alarm 30 min before.
     */
    private fun autoSetMorningAlarms() {
        if (!ProactiveConfig.allowAlarms) return
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val tomorrowStart = (tomorrow.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
        val tomorrowMorning = (tomorrow.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 10); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis

        // Find trustworthy events/reminders tomorrow morning. A generic AI-created item
        // is not enough evidence: it must be linked to a commitment that is still confirmed.
        val earlyItems = AssistantStore.all().filter {
            it.triggerAtMs in tomorrowStart until tomorrowMorning && !it.done &&
                it.type in setOf(AssistantItemType.EVENT, AssistantItemType.REMINDER) &&
                (it.source == "user" ||
                    (it.originRef.isNotBlank() && ProactiveCommitmentStore.isConfirmed(it.originRef)))
        }
        if (earlyItems.isEmpty()) return

        // Check existing alarms for tomorrow
        val existingAlarms = AssistantStore.all().filter {
            it.type == AssistantItemType.ALARM && !it.done &&
            it.triggerAtMs in tomorrowStart until tomorrowMorning
        }.map { it.triggerAtMs }

        for (item in earlyItems) {
            val alarmTime = item.triggerAtMs - 30 * 60_000L  // 30 min before
            // Skip if there's already an alarm within 15 min of when we'd set one
            val hasAlarm = existingAlarms.any { kotlin.math.abs(it - alarmTime) < 15 * 60_000L }
            if (hasAlarm) continue

            if (alarmTime <= System.currentTimeMillis()) continue
            val alarm = AssistantStore.create(
                type = AssistantItemType.ALARM,
                title = "Despierta: ${item.title}",
                body = "Preparado por el resumen nocturno para ${AssistantTime.format(item.triggerAtMs)}",
                triggerAtMs = alarmTime,
                ring = true,
                category = "proactive_briefing_alarm",
                source = "ai",
                originRef = item.originRef,
            )
            AssistantScheduler.arm(ClawApplication.instance, alarm)
            XLog.i(TAG, "Auto-set confirmed morning alarm at ${AssistantTime.format(alarmTime)} for '${item.title}'")
        }
    }

    /**
     * After the morning briefing, handle a detected habit. By default the
     * assistant only SUGGESTS it once (creating a recurring alarm unannounced is
     * jarring); if the user opted into [ProactiveConfig.autoCreateHabits] it sets
     * it up automatically as before.
     */
    private fun surfaceHabitSuggestion() {
        val habit = HabitTracker.newHabits().firstOrNull() ?: return

        // Default path: suggest once, don't create anything. Reversible, no surprise.
        if (!ProactiveConfig.autoCreateHabits) {
            val body = "${HabitTracker.describe(habit)} Si quieres, puedo programarlo " +
                "automáticamente cada semana — dímelo y lo activo."
            AssistantStore.create(
                type = AssistantItemType.ALERT,
                title = "💡 ¿Automatizar un hábito?",
                body = body, category = "habit", source = "ai",
            )
            AssistantReceiver.postDecisionNotification(
                ClawApplication.instance, "💡 ¿Automatizar este hábito?", body,
                "Programa este hábito semanal: ${HabitTracker.describe(habit)}")
            HabitTracker.markSuggested(habit)
            XLog.i(TAG, "Suggested habit (no auto-create): ${habit.id}")
            return
        }

        // Opt-in path: actually create the recurring item.
        val registry = ToolRegistry.getInstance()
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, habit.dayOfWeek)
            set(Calendar.HOUR_OF_DAY, habit.hour)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.WEEK_OF_YEAR, 1)
        }
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(cal.timeInMillis))
        when (habit.kind) {
            "alarm" -> {
                registry.executeTool("assistant_alarm", mapOf(
                    "when" to timeStr,
                    "label" to "Alarma recurrente (hábito detectado)",
                    "repeat" to "weekly",
                ))
            }
            "reminder" -> {
                registry.executeTool("assistant_reminder", mapOf(
                    "title" to "Recordatorio recurrente (hábito detectado)",
                    "when" to timeStr,
                    "repeat" to "weekly",
                ))
            }
            else -> {
                // For events and other types, just create a note
                registry.executeTool("assistant_note", mapOf(
                    "title" to "Patrón: ${HabitTracker.describe(habit)}",
                ))
            }
        }
        val text = "Automaticé un patrón: ${HabitTracker.describe(habit)}"
        AssistantStore.create(
            type = AssistantItemType.ALERT,
            title = "⚡ Hábito automatizado",
            body = text, category = "habit", source = "ai",
        )
        AssistantReceiver.postNotification(ClawApplication.instance, "⚡ Hábito automatizado", text, highPriority = false)
        HabitTracker.markSuggested(habit)
        XLog.i(TAG, "Auto-created habit: ${habit.id}")
    }

    /**
     * Weekly finance recap: spending/income over the last 7 days, top categories,
     * and how the month is tracking against the budget. Fully local maths; the LLM
     * only phrases it nicely (with a deterministic fallback so it always lands).
     */
    private fun runWeeklyFinance() {
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        val spent = AssistantStore.expensesSince(weekAgo)
        val income = AssistantStore.incomeSince(weekAgo)
        val byCat = AssistantStore.expensesByCategorySince(weekAgo)
        val budget = AssistantStore.monthlyBudget
        val monthSpent = AssistantStore.monthExpenses()
        val avgWeek = AssistantStore.avgWeeklyExpenses(4)
        val goal = AssistantStore.savingsGoal
        val goalName = AssistantStore.savingsGoalName
        val balance = AssistantStore.financeBalance()

        // Build a compact data block.
        val data = buildString {
            appendLine("Resumen de los últimos 7 días:")
            appendLine("- Gastos: ${"%.2f".format(spent)}")
            if (income > 0) appendLine("- Ingresos: ${"%.2f".format(income)}")
            appendLine("- Neto: ${"%.2f".format(income - spent)}")
            if (byCat.isNotEmpty()) {
                appendLine("Gastos por categoría:")
                byCat.take(6).forEach { (cat, amt) -> appendLine("- $cat: ${"%.2f".format(amt)}") }
            }
            // Anomaly: this week vs the 4-week average.
            if (avgWeek > 0) {
                val deltaPct = ((spent - avgWeek) / avgWeek * 100).toInt()
                appendLine("Promedio semanal previo: ${"%.2f".format(avgWeek)} (esta semana ${if (deltaPct >= 0) "+" else ""}$deltaPct%)")
            }
            if (budget > 0) {
                val pct = (monthSpent / budget * 100).toInt()
                appendLine("Presupuesto del mes: ${"%.0f".format(monthSpent)} de ${"%.0f".format(budget)} ($pct%)")
                appendLine("Disponible este mes: ${"%.0f".format(budget - monthSpent)}")
            }
            if (goal > 0) {
                val pct = if (goal > 0) (balance / goal * 100).toInt().coerceAtLeast(0) else 0
                appendLine("Meta de ahorro${if (goalName.isNotBlank()) " ($goalName)" else ""}: " +
                    "${"%.0f".format(balance.coerceAtLeast(0.0))} de ${"%.0f".format(goal)} ($pct%)")
            }
        }.trim()

        val title = "📊 Resumen financiero semanal"
        val text = summarizeWeekly(data, spent, income, budget, monthSpent)
            ?: weeklyFallback(spent, income, byCat, budget, monthSpent, avgWeek, goal, goalName, balance)
        AssistantStore.create(type = AssistantItemType.ALERT, title = title, body = text, source = "ai")
        AssistantReceiver.postNotification(ClawApplication.instance, title, text, highPriority = false)
        if (ProactiveConfig.speakBriefings) Speaker.speak("$title. $text")
        XLog.i(TAG, "Weekly finance summary delivered")
    }

    private fun summarizeWeekly(
        data: String, spent: Double, income: Double, budget: Double, monthSpent: Double,
    ): String? {
        if (spent == 0.0 && income == 0.0 && monthSpent == 0.0) return null
        val prompt = buildString {
            appendLine("Eres el asistente personal del usuario. Dale un resumen financiero semanal breve y útil.")
            appendLine()
            appendLine("Datos (no inventes nada fuera de esto):")
            appendLine(data)
            appendLine()
            ProactiveMemory.preferencesSnippet().takeIf { it.isNotBlank() }?.let { appendLine(it); appendLine() }
            appendLine("Instrucciones:")
            appendLine("- Máximo 4 líneas, español, tono cercano y claro.")
            appendLine("- Resalta el gasto total de la semana y la categoría donde más gastó.")
            appendLine("- Si hay presupuesto, di si va bien o si debe cuidarse el resto del mes.")
            appendLine("- Da UN consejo accionable si aplica. No inventes cifras.")
        }
        return LlmSessionManager.singleShot(prompt, 0.4)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun weeklyFallback(
        spent: Double, income: Double, byCat: List<Pair<String, Double>>,
        budget: Double, monthSpent: Double,
        avgWeek: Double = 0.0, goal: Double = 0.0, goalName: String = "", balance: Double = 0.0,
    ): String {
        if (spent == 0.0 && income == 0.0 && monthSpent == 0.0)
            return "Esta semana no registré movimientos de dinero. Si quieres, ve anotando tus gastos y te haré el resumen."
        val sb = StringBuilder()
        sb.append("Esta semana gastaste ${"%.2f".format(spent)}")
        if (income > 0) sb.append(" e ingresaste ${"%.2f".format(income)}")
        sb.append(".")
        byCat.firstOrNull()?.let { (cat, amt) ->
            sb.append(" Donde más gastaste: $cat (${"%.2f".format(amt)}).")
        }
        // Anomaly callout.
        if (avgWeek > 0) {
            val deltaPct = ((spent - avgWeek) / avgWeek * 100).toInt()
            when {
                deltaPct >= 25 -> sb.append(" Ojo: gastaste $deltaPct% más que tu promedio; cuida el resto del mes.")
                deltaPct <= -20 -> sb.append(" Bien: gastaste ${-deltaPct}% menos que tu promedio.")
            }
        }
        if (budget > 0) {
            val remaining = budget - monthSpent
            val pct = (monthSpent / budget * 100).toInt()
            sb.append(" Llevas $pct% del presupuesto del mes; te quedan ${"%.0f".format(remaining)}.")
        }
        if (goal > 0) {
            val pct = (balance / goal * 100).toInt().coerceAtLeast(0)
            sb.append(" Meta${if (goalName.isNotBlank()) " ($goalName)" else ""}: $pct% (${"%.0f".format(balance.coerceAtLeast(0.0))}/${"%.0f".format(goal)}).")
        }
        return sb.toString()
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
            val budget = AssistantStore.monthlyBudget
            if (budget > 0) {
                val spent = AssistantStore.monthExpenses()
                sb.appendLine("Presupuesto mes: ${"%.0f".format(spent)} de ${"%.0f".format(budget)} gastado")
            }
        }
        // Shopping list reminder in both briefings.
        val shopping = AssistantStore.byType(AssistantItemType.SHOPPING).filter { !it.done }
        if (shopping.isNotEmpty()) {
            sb.appendLine("Lista de compras (${shopping.size}): " +
                shopping.take(8).joinToString(", ") { it.title })
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
                "Eres el asistente personal del usuario. Dale un briefing matutino breve, cálido y útil. " +
                    "Resume únicamente lo que aparece en los datos; no afirmes que creaste una alarma o acción si no está registrada ahí."
            else
                "Eres el asistente personal del usuario. Dale un resumen nocturno breve para preparar mañana. " +
                    "No conviertas planes dudosos en compromisos ni afirmes que pusiste alarmas salvo que los datos lo demuestren.")
            appendLine()
            appendLine("Datos disponibles:")
            appendLine(state)
            appendLine()
            ProactiveMemory.preferencesSnippet().takeIf { it.isNotBlank() }?.let { appendLine(it); appendLine() }
            appendLine("Instrucciones:")
            appendLine("- Máximo 4-5 líneas, en español, tono natural y directo (no preguntas retóricas).")
            appendLine("- Menciona lo importante del día/mañana.")
            appendLine("- No inventes acciones. Si algo parece útil pero todavía no está confirmado, preséntalo como pendiente/sugerencia, no como hecho.")
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
