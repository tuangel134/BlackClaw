package com.blackclaw.android.tool.impl

import com.blackclaw.android.ClawApplication
import com.blackclaw.android.assistant.AssistantItemType
import com.blackclaw.android.assistant.AssistantReceiver
import com.blackclaw.android.assistant.AssistantScheduler
import com.blackclaw.android.assistant.AssistantStore
import com.blackclaw.android.assistant.AssistantTime
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Native Assistant tools. These write into BlackClaw's own Assistant hub
 * (AssistantStore) and arm native push notifications via AssistantScheduler —
 * no external Clock / Calendar / Notes app required. The proactive assistant
 * and the chat agent both use these.
 */

/** Reminder: fires a native push notification at a future time. */
class AssistantReminderTool : BaseTool() {
    override fun getName() = "assistant_reminder"
    override fun getDisplayName() = "Recordatorio"
    override fun getDescriptionEN() =
        "Create a native in-app reminder that fires a BlackClaw push notification at a time. " +
        "Use for 'remind me to…'. Time: 'YYYY-MM-DD HH:MM', 'tomorrow 09:00', 'in 30m', or 'HH:MM'. " +
        "Optional repeat: none|daily|weekly. Stored in the Assistant hub — no external app."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "crea un recordatorio nativo que avisa con notificación push"
    override fun getParameters() = listOf(
        ToolParameter("title", "string", "What to remind about.", true),
        ToolParameter("when", "string", "When to fire (e.g. 'tomorrow 09:00', 'in 2h').", true),
        ToolParameter("body", "string", "Optional extra detail.", false),
        ToolParameter("repeat", "string", "none | daily | weekly (default none).", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val title = requireString(params, "title").trim()
        val ts = AssistantTime.parse(optionalString(params, "when", ""))
        if (ts <= 0) return ToolResult.error("No pude entender la fecha/hora '${params["when"]}'.")
        val item = AssistantStore.create(
            type = AssistantItemType.REMINDER, title = title,
            body = optionalString(params, "body", ""),
            triggerAtMs = ts, repeat = optionalString(params, "repeat", "none").lowercase(),
            source = "ai",
        )
        AssistantScheduler.arm(ClawApplication.instance, item)
        return ToolResult.success("Recordatorio guardado: '$title' para ${AssistantTime.format(ts)}")
    }
}

/** Alarm: like a reminder but high-priority/heads-up at a clock time. */
class AssistantAlarmTool : BaseTool() {
    override fun getName() = "assistant_alarm"
    override fun getDisplayName() = "Alarma"
    override fun getDescriptionEN() =
        "Set a native in-app alarm that fires a high-priority BlackClaw notification at a clock time. " +
        "Time: 'HH:MM' (next occurrence), 'tomorrow 07:00', or 'YYYY-MM-DD HH:MM'. " +
        "Optional repeat: none|daily|weekly. Optional challenge to force the user awake: " +
        "none|math|memory|type (math problem / memory sequence / type a phrase). " +
        "Use for wake-ups and 'be somewhere at X'."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "pone una alarma nativa (notificación de alta prioridad, con reto opcional)"
    override fun getParameters() = listOf(
        ToolParameter("when", "string", "Clock time, e.g. '07:30', 'tomorrow 07:00'.", true),
        ToolParameter("label", "string", "Optional alarm label.", false),
        ToolParameter("repeat", "string", "none | daily | weekly (default none).", false),
        ToolParameter("challenge", "string",
            "none | math | memory | type — a wake-up challenge required to dismiss. Default none.", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val ts = AssistantTime.parse(requireString(params, "when"))
        if (ts <= 0) return ToolResult.error("No pude entender la hora '${params["when"]}'.")
        val label = optionalString(params, "label", "Alarma")
        val challenge = optionalString(params, "challenge", "none").lowercase()
        val item = AssistantStore.create(
            type = AssistantItemType.ALARM, title = label,
            triggerAtMs = ts, repeat = optionalString(params, "repeat", "none").lowercase(),
            challenge = challenge, source = "ai",
        )
        AssistantScheduler.arm(ClawApplication.instance, item)
        val extra = if (challenge != "none") " (reto: $challenge)" else ""
        return ToolResult.success("Alarma puesta: '$label' a las ${AssistantTime.format(ts)}$extra")
    }
}

/** Note: a persistent text note / todo in the hub. */
class AssistantNoteTool : BaseTool() {    override fun getName() = "assistant_note"
    override fun getDisplayName() = "Nota"
    override fun getDescriptionEN() =
        "Save a native in-app note or todo in the Assistant hub. Use for things to remember " +
        "with no specific time. No external app."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "guarda una nota/todo nativa en el hub del asistente"
    override fun getParameters() = listOf(
        ToolParameter("title", "string", "Short note title.", true),
        ToolParameter("body", "string", "Optional note detail.", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val title = requireString(params, "title").trim()
        AssistantStore.create(
            type = AssistantItemType.NOTE, title = title,
            body = optionalString(params, "body", ""), source = "ai",
        )
        return ToolResult.success("Nota guardada: '$title'")
    }
}

/** Calendar event stored natively (also fires a reminder push at start time). */
class AssistantEventTool : BaseTool() {
    override fun getName() = "assistant_event"
    override fun getDisplayName() = "Evento"
    override fun getDescriptionEN() =
        "Create a native in-app calendar event in the Assistant hub and notify at start time. " +
        "Time: 'YYYY-MM-DD HH:MM', 'tomorrow 15:00'. No external calendar app."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "crea un evento de calendario nativo y avisa al iniciar"
    override fun getParameters() = listOf(
        ToolParameter("title", "string", "Event title.", true),
        ToolParameter("start", "string", "Start time.", true),
        ToolParameter("location", "string", "Optional location.", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val title = requireString(params, "title").trim()
        val ts = AssistantTime.parse(requireString(params, "start"))
        if (ts <= 0) return ToolResult.error("No pude entender la fecha '${params["start"]}'.")
        val loc = optionalString(params, "location", "")
        val item = AssistantStore.create(
            type = AssistantItemType.EVENT, title = title,
            body = if (loc.isNotBlank()) "📍 $loc" else "", triggerAtMs = ts, source = "ai",
        )
        AssistantScheduler.arm(ClawApplication.instance, item)
        return ToolResult.success("Evento creado: '$title' el ${AssistantTime.format(ts)}")
    }
}

/**
 * Smart appointment: the one-shot "tengo una reunión a las 7" flow. Creates a
 * single calendar EVENT that ALSO rings like an alarm at its time, shows on the
 * calendar/agenda, warns about conflicts, and can add an early heads-up
 * reminder. Works for anything from "a las 7" to "en 3 semanas" — the native
 * alarm fires whenever that moment arrives.
 */
class AssistantAppointmentTool : BaseTool() {
    override fun getName() = "assistant_appointment"
    override fun getDisplayName() = "Cita / reunión"
    override fun getDescriptionEN() =
        "Schedule an appointment/meeting the user mentions ('tengo una reunión a las 7', " +
        "'cita el viernes 10:00', 'en 3 semanas tengo X'). Creates ONE calendar event that ALSO " +
        "rings like an alarm at its time and shows on the calendar/agenda. Use this for ANY future " +
        "commitment with a time — it handles near and far future. " +
        "Time: 'HH:MM', 'tomorrow 15:00', 'el viernes 10:00', 'YYYY-MM-DD HH:MM'. " +
        "Set ring=false for a silent calendar entry (no alarm). remind_before_min adds an early " +
        "heads-up reminder (e.g. 30 = notify 30 min before)."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "agenda una cita/reunión: la pone en calendario, suena como alarma y avisa antes"
    override fun getParameters() = listOf(
        ToolParameter("title", "string", "What the appointment is (e.g. 'Reunión con cliente').", true),
        ToolParameter("when", "string", "When it starts (e.g. 'a las 7' → '19:00', 'el viernes 10:00').", true),
        ToolParameter("location", "string", "Optional place.", false),
        ToolParameter("ring", "string", "true (rings like an alarm, default) | false (silent calendar entry).", false),
        ToolParameter("remind_before_min", "string", "Optional minutes before to send an early reminder (e.g. '30').", false),
        ToolParameter("repeat", "string", "none|daily|weekly|weekdays|weekends|monthly (default none).", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val title = requireString(params, "title").trim()
        val ts = AssistantTime.parse(requireString(params, "when"))
        if (ts <= 0) return ToolResult.error("No pude entender la fecha/hora '${params["when"]}'.")
        val loc = optionalString(params, "location", "")
        val ring = optionalString(params, "ring", "true").lowercase() != "false"
        val repeat = optionalString(params, "repeat", "none").lowercase()
        val remindBefore = optionalString(params, "remind_before_min", "").toIntOrNull() ?: 0

        // Warn about conflicts (something else within 30 min).
        val conflicts = AssistantStore.conflictsAt(ts, windowMin = 30)
        val event = AssistantStore.create(
            type = AssistantItemType.EVENT, title = title,
            body = if (loc.isNotBlank()) "📍 $loc" else "",
            triggerAtMs = ts, repeat = repeat, ring = ring, source = "ai",
        )
        AssistantScheduler.arm(ClawApplication.instance, event)

        // Optional early heads-up reminder.
        if (remindBefore > 0) {
            val remindAt = ts - remindBefore * 60_000L
            if (remindAt > System.currentTimeMillis()) {
                val r = AssistantStore.create(
                    type = AssistantItemType.REMINDER,
                    title = "Pronto: $title",
                    body = "En $remindBefore min" + if (loc.isNotBlank()) " · 📍 $loc" else "",
                    triggerAtMs = remindAt, source = "ai", category = "lead",
                )
                AssistantScheduler.arm(ClawApplication.instance, r)
            }
        }

        val sb = StringBuilder("Listo, jefe. Agendé '$title' para ${AssistantTime.format(ts)}")
        if (ring) sb.append(" y sonará como alarma")
        if (remindBefore > 0) sb.append("; te aviso $remindBefore min antes")
        sb.append(". Está en tu calendario y agenda.")
        if (conflicts.isNotEmpty()) {
            val c = conflicts.first()
            sb.append(" ⚠️ Ojo: ya tienes '${c.title}' a ${AssistantTime.format(c.triggerAtMs)}.")
        }
        return ToolResult.success(sb.toString())
    }
}

/** Read the upcoming agenda (alarms/reminders/events) for a spoken summary. */
class AssistantAgendaTool : BaseTool() {
    override fun getName() = "assistant_agenda"
    override fun getDisplayName() = "Agenda"
    override fun getDescriptionEN() =
        "Read the user's upcoming agenda (alarms, reminders, events) from the Assistant hub. " +
        "Use for 'qué tengo hoy', 'qué tengo mañana', 'mi agenda de esta semana', 'what's coming up'. " +
        "range: today | tomorrow | week | all (default today). Returns a chronological list."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "lee la agenda próxima (hoy/mañana/semana) para resumirla"
    override fun getParameters() = listOf(
        ToolParameter("range", "string", "today | tomorrow | week | all (default today).", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val range = optionalString(params, "range", "today").lowercase()
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance()
        fun startOfDay(offsetDays: Int): Long {
            val c = java.util.Calendar.getInstance()
            c.add(java.util.Calendar.DAY_OF_YEAR, offsetDays)
            c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0)
            c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }
        val (from, to) = when (range) {
            "tomorrow", "mañana" -> startOfDay(1) to startOfDay(2)
            "week", "semana" -> now to startOfDay(8)
            "all", "todo" -> now to Long.MAX_VALUE
            else -> now to startOfDay(1) // today
        }
        val items = AssistantStore.upcoming(limit = 50, fromMs = from)
            .filter { it.triggerAtMs < to }
        if (items.isEmpty()) {
            val label = when (range) {
                "tomorrow", "mañana" -> "mañana"
                "week", "semana" -> "esta semana"
                "all", "todo" -> "próximamente"
                else -> "hoy"
            }
            return ToolResult.success("No tienes nada agendado $label, jefe.")
        }
        val sb = StringBuilder()
        items.forEach { i ->
            val kind = when {
                i.ring || i.type == AssistantItemType.ALARM -> "⏰"
                i.type == AssistantItemType.EVENT -> "📅"
                else -> "🔔"
            }
            sb.append("$kind ${AssistantTime.format(i.triggerAtMs)} — ${i.title}")
            if (i.body.isNotBlank()) sb.append(" (${i.body})")
            sb.append("\n")
        }
        return ToolResult.success(sb.toString().trim())
    }
}

/** Immediate alert push (no scheduling). */
class AssistantAlertTool : BaseTool() {    override fun getName() = "assistant_alert"
    override fun getDisplayName() = "Aviso"
    override fun getDescriptionEN() =
        "Surface an important heads-up to the user right now as a native push notification, " +
        "and log it in the Assistant hub. Use for time-critical info the user should see immediately."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "envía un aviso push inmediato y lo registra en el hub"
    override fun getParameters() = listOf(
        ToolParameter("title", "string", "Alert title.", true),
        ToolParameter("body", "string", "Alert message.", true),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val title = requireString(params, "title").trim()
        val body = requireString(params, "body").trim()
        AssistantStore.create(type = AssistantItemType.ALERT, title = title, body = body, source = "ai")
        AssistantReceiver.postNotification(ClawApplication.instance, "📢 $title", body, highPriority = true)
        return ToolResult.success("Aviso enviado: '$title'")
    }
}

/** Finance entry: income (positive) or expense (negative). */
class AssistantFinanceTool : BaseTool() {
    override fun getName() = "assistant_finance"
    override fun getDisplayName() = "Finanzas"
    override fun getDescriptionEN() =
        "Record a native finance entry (income or expense) in the Assistant hub. " +
        "amount is positive for income, negative for expense. Useful when a notification or the user " +
        "mentions a payment, charge, salary, or purchase. Tracks a running balance."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "registra un ingreso/gasto en el control de finanzas del hub"
    override fun getParameters() = listOf(
        ToolParameter("description", "string", "What the entry is for.", true),
        ToolParameter("amount", "number", "Positive = income, negative = expense.", true),
        ToolParameter("category", "string", "Optional category (food, salary, bills…).", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val desc = requireString(params, "description").trim()
        val amount = (params["amount"] as? Number)?.toDouble()
            ?: params["amount"]?.toString()?.toDoubleOrNull()
            ?: return ToolResult.error("amount inválido")
        AssistantStore.create(
            type = AssistantItemType.FINANCE, title = desc,
            amount = amount, category = optionalString(params, "category", ""), source = "ai",
        )
        val bal = AssistantStore.financeBalance()
        val sign = if (amount >= 0) "+" else ""
        return ToolResult.success("Registrado: $desc ($sign$amount). Balance: ${"%.2f".format(bal)}")
    }
}

/** Read what's currently in the Assistant hub (so the AI can manage it). */
class AssistantListTool : BaseTool() {
    override fun getName() = "assistant_list"
    override fun getDisplayName() = "Ver asistente"
    override fun getDescriptionEN() =
        "List items in the Assistant hub. Optional type filter: reminder|alarm|note|event|alert|finance. " +
        "Use to check what reminders/alarms/notes/events exist, the finance balance, or before " +
        "editing/avoiding duplicates. Returns a compact summary with ids."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "lista lo que hay en el hub del asistente (recordatorios, notas, finanzas…)"
    override fun getParameters() = listOf(
        ToolParameter("type", "string",
            "Optional filter: reminder|alarm|note|event|alert|finance. Omit for all.", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val filter = optionalString(params, "type", "").trim().uppercase()
        val items = if (filter.isNotBlank()) {
            val t = runCatching { com.blackclaw.android.assistant.AssistantItemType.valueOf(filter) }.getOrNull()
                ?: return ToolResult.error("Tipo inválido. Usa reminder|alarm|note|event|alert|finance.")
            com.blackclaw.android.assistant.AssistantStore.byType(t)
        } else {
            com.blackclaw.android.assistant.AssistantStore.all()
        }
        if (items.isEmpty()) return ToolResult.success("El hub del asistente está vacío.")
        val sb = StringBuilder()
        items.forEach { i ->
            sb.append("[${i.id}] ${i.type.name.lowercase()}: ${i.title}")
            if (i.triggerAtMs > 0) sb.append(" @ ${com.blackclaw.android.assistant.AssistantTime.format(i.triggerAtMs)}")
            if (i.type == com.blackclaw.android.assistant.AssistantItemType.FINANCE) sb.append(" (${i.amount})")
            if (i.done) sb.append(" ✓")
            sb.append('\n')
        }
        val bal = com.blackclaw.android.assistant.AssistantStore.financeBalance()
        if (filter.isBlank() || filter == "FINANCE") sb.append("Balance finanzas: ${"%.2f".format(bal)}")
        return ToolResult.success(sb.toString().trim())
    }
}

/** Delete / complete an item by id. */
class AssistantRemoveTool : BaseTool() {
    override fun getName() = "assistant_remove"
    override fun getDisplayName() = "Quitar del asistente"
    override fun getDescriptionEN() =
        "Delete an Assistant hub item by id (from assistant_list). Use to cancel a reminder/alarm/event " +
        "or remove a done note. Cancels its alarm too."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "borra o cancela un elemento del hub por id"
    override fun getParameters() = listOf(
        ToolParameter("id", "string", "Item id from assistant_list.", true),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val id = requireString(params, "id").trim()
        com.blackclaw.android.assistant.AssistantScheduler.cancel(ClawApplication.instance, id)
        val ok = com.blackclaw.android.assistant.AssistantStore.delete(id)
        return if (ok) ToolResult.success("Elemento $id eliminado.")
        else ToolResult.error("No encontré el elemento $id.")
    }
}

/** Location-based reminder (geofence): fires when the user enters/exits a place. */
class AssistantLocationReminderTool : BaseTool() {
    override fun getName() = "assistant_location_reminder"
    override fun getDisplayName() = "Recordatorio por ubicación"
    override fun getDescriptionEN() =
        "Create a location reminder that fires when the user ARRIVES at or LEAVES a place. " +
        "Provide lat & lon (use get_location for the current spot, e.g. 'remind me when I get home' " +
        "while at home) and a radius in meters (default 150). trigger=enter|exit. " +
        "Fires within a few minutes of crossing — no constant GPS."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "recordatorio que salta al llegar/salir de un lugar (geofence)"
    override fun getParameters() = listOf(
        ToolParameter("title", "string", "What to remind.", true),
        ToolParameter("lat", "number", "Latitude of the place.", true),
        ToolParameter("lon", "number", "Longitude of the place.", true),
        ToolParameter("radius_m", "integer", "Radius in meters (default 150).", false),
        ToolParameter("trigger", "string", "enter | exit (default enter).", false),
        ToolParameter("body", "string", "Optional detail.", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val title = requireString(params, "title").trim()
        val lat = (params["lat"] as? Number)?.toDouble() ?: params["lat"]?.toString()?.toDoubleOrNull()
            ?: return ToolResult.error("lat inválido")
        val lon = (params["lon"] as? Number)?.toDouble() ?: params["lon"]?.toString()?.toDoubleOrNull()
            ?: return ToolResult.error("lon inválido")
        val radius = optionalInt(params, "radius_m", 150).coerceIn(50, 5000)
        val trigger = optionalString(params, "trigger", "enter").lowercase()
        AssistantStore.create(
            type = AssistantItemType.REMINDER, title = title,
            body = optionalString(params, "body", ""),
            lat = lat, lon = lon, radiusM = radius, geoTrigger = trigger, source = "ai",
        )
        val verb = if (trigger == "exit") "salgas de" else "llegues a"
        return ToolResult.success("Recordatorio por ubicación: '$title' cuando $verb ese lugar (${radius}m).")
    }
}

/** Add an item to the shopping list. */
class AssistantShoppingTool : BaseTool() {
    override fun getName() = "assistant_shopping_add"
    override fun getDisplayName() = "Lista de compras"
    override fun getDescriptionEN() =
        "Add one or more items to the shopping list in the Assistant hub. " +
        "Pass a single item in 'item', or several comma-separated in 'items'."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "añade artículos a la lista de compras"
    override fun getParameters() = listOf(
        ToolParameter("item", "string", "A single item to add.", false),
        ToolParameter("items", "string", "Comma-separated items to add.", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val single = optionalString(params, "item", "").trim()
        val multi = optionalString(params, "items", "")
        val list = buildList {
            if (single.isNotBlank()) add(single)
            addAll(multi.split(",").map { it.trim() }.filter { it.isNotBlank() })
        }.distinct()
        if (list.isEmpty()) return ToolResult.error("Indica 'item' o 'items'.")
        list.forEach {
            com.blackclaw.android.assistant.AssistantStore.create(
                type = com.blackclaw.android.assistant.AssistantItemType.SHOPPING,
                title = it, source = "ai")
        }
        return ToolResult.success("Añadido a la lista: ${list.joinToString()}")
    }
}

/** Set the monthly spending budget. */
class AssistantBudgetTool : BaseTool() {
    override fun getName() = "assistant_set_budget"
    override fun getDisplayName() = "Presupuesto"
    override fun getDescriptionEN() =
        "Set the user's monthly spending budget. The assistant warns when expenses approach it. " +
        "amount in the user's currency; 0 clears the budget."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "fija el presupuesto mensual de gastos"
    override fun getParameters() = listOf(
        ToolParameter("amount", "number", "Monthly budget amount (0 to clear).", true),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val amount = (params["amount"] as? Number)?.toDouble()
            ?: params["amount"]?.toString()?.toDoubleOrNull()
            ?: return ToolResult.error("amount inválido")
        com.blackclaw.android.assistant.AssistantStore.monthlyBudget = amount.coerceAtLeast(0.0)
        val spent = com.blackclaw.android.assistant.AssistantStore.monthExpenses()
        return if (amount <= 0) ToolResult.success("Presupuesto mensual eliminado.")
        else ToolResult.success("Presupuesto mensual: ${"%.2f".format(amount)}. Gastado este mes: ${"%.2f".format(spent)}.")
    }
}

/** Medication reminder: a daily repeating reminder at a clock time. */
class AssistantMedicationTool : BaseTool() {
    override fun getName() = "assistant_medication"
    override fun getDisplayName() = "Medicación"
    override fun getDescriptionEN() =
        "Set a recurring medication reminder. Fires a notification daily at the given time. " +
        "Use for 'remind me to take X every day at HH:MM'. times can be a single 'HH:MM' or " +
        "comma-separated for multiple doses."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "recordatorio de medicación diario (una o varias tomas)"
    override fun getParameters() = listOf(
        ToolParameter("medication", "string", "Medication name / what to take.", true),
        ToolParameter("times", "string", "Time(s) HH:MM, comma-separated for several doses.", true),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val med = requireString(params, "medication").trim()
        val times = requireString(params, "times").split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (times.isEmpty()) return ToolResult.error("Indica al menos una hora HH:MM.")
        var made = 0
        times.forEach { t ->
            val ts = com.blackclaw.android.assistant.AssistantTime.parse(t)
            if (ts > 0) {
                val item = com.blackclaw.android.assistant.AssistantStore.create(
                    type = com.blackclaw.android.assistant.AssistantItemType.REMINDER,
                    title = "💊 Tomar $med",
                    body = "Recordatorio de medicación",
                    triggerAtMs = ts, repeat = "daily", category = "medication", source = "ai")
                com.blackclaw.android.assistant.AssistantScheduler.arm(ClawApplication.instance, item)
                made++
            }
        }
        if (made == 0) return ToolResult.error("No pude entender las horas.")
        return ToolResult.success("Recordatorio de $med creado ($made toma(s) al día).")
    }
}

/** Promise tracking: create a follow-up reminder so the user keeps a commitment. */
class AssistantTrackPromiseTool : BaseTool() {
    override fun getName() = "assistant_track_promise"
    override fun getDisplayName() = "Seguir promesa"
    override fun getDescriptionEN() =
        "Track a commitment the user made (e.g. 'I'll call Ana tomorrow') so BlackClaw reminds " +
        "them if they haven't done it. Provide what was promised and when to follow up."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "crea un seguimiento de una promesa/compromiso del usuario"
    override fun getParameters() = listOf(
        ToolParameter("promise", "string", "What the user committed to do.", true),
        ToolParameter("follow_up", "string", "When to remind, e.g. 'tomorrow 18:00', 'in 3h'.", true),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val promise = requireString(params, "promise").trim()
        val ts = com.blackclaw.android.assistant.AssistantTime.parse(requireString(params, "follow_up"))
        if (ts <= 0) return ToolResult.error("No pude entender cuándo recordar.")
        val item = com.blackclaw.android.assistant.AssistantStore.create(
            type = com.blackclaw.android.assistant.AssistantItemType.REMINDER,
            title = "¿Hiciste esto? $promise",
            body = "Seguimiento de algo que dijiste que harías.",
            triggerAtMs = ts, category = "promise", source = "ai")
        com.blackclaw.android.assistant.AssistantScheduler.arm(ClawApplication.instance, item)
        return ToolResult.success("Te recordaré: '$promise' el ${com.blackclaw.android.assistant.AssistantTime.format(ts)}")
    }
}

/** Leave-soon reminder: alert the user to leave ahead of an appointment. */
class AssistantLeaveReminderTool : BaseTool() {
    override fun getName() = "assistant_leave_reminder"
    override fun getDisplayName() = "Aviso de salida"
    override fun getDescriptionEN() =
        "Remind the user to LEAVE for an appointment ahead of time. Give the appointment time and " +
        "how many minutes before to alert (travel + prep buffer). Creates a reminder at " +
        "(appointment - lead_minutes)."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "avisa con antelación para salir hacia una cita"
    override fun getParameters() = listOf(
        ToolParameter("what", "string", "The appointment/where to go.", true),
        ToolParameter("appointment", "string", "Appointment time, e.g. 'today 15:00', 'tomorrow 09:00'.", true),
        ToolParameter("lead_minutes", "integer", "Minutes before to alert (default 30).", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val what = requireString(params, "what").trim()
        val appt = com.blackclaw.android.assistant.AssistantTime.parse(requireString(params, "appointment"))
        if (appt <= 0) return ToolResult.error("No pude entender la hora de la cita.")
        val lead = optionalInt(params, "lead_minutes", 30).coerceIn(1, 240)
        val triggerAt = appt - lead * 60_000L
        if (triggerAt <= System.currentTimeMillis())
            return ToolResult.error("Esa salida ya pasó o es inmediata.")
        val item = com.blackclaw.android.assistant.AssistantStore.create(
            type = com.blackclaw.android.assistant.AssistantItemType.REMINDER,
            title = "🚗 Sal ya: $what",
            body = "Tu cita es a las ${com.blackclaw.android.assistant.AssistantTime.format(appt)} ($lead min antes).",
            triggerAtMs = triggerAt, category = "leave", source = "ai")
        com.blackclaw.android.assistant.AssistantScheduler.arm(ClawApplication.instance, item)
        return ToolResult.success("Te avisaré salir a las ${com.blackclaw.android.assistant.AssistantTime.format(triggerAt)} para '$what'.")
    }
}

/** Draft reply: store a suggested reply the user can review/copy/send. */
class AssistantDraftReplyTool : BaseTool() {
    override fun getName() = "assistant_draft_reply"
    override fun getDisplayName() = "Borrador de respuesta"
    override fun getDescriptionEN() =
        "Save a suggested reply draft for a message the user received, so they can review and " +
        "send it later. Provide who it's for and the drafted text. Shows up in the assistant hub " +
        "as a draft the user can copy."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "guarda un borrador de respuesta sugerido para revisar/enviar"
    override fun getParameters() = listOf(
        ToolParameter("to", "string", "Who the reply is for (contact / app).", true),
        ToolParameter("draft", "string", "The drafted reply text.", true),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val to = requireString(params, "to").trim()
        val draft = requireString(params, "draft").trim()
        if (draft.isEmpty()) return ToolResult.error("draft vacío")
        com.blackclaw.android.assistant.AssistantStore.create(
            type = com.blackclaw.android.assistant.AssistantItemType.ALERT,
            title = "✍️ Borrador para $to",
            body = draft, category = "draft", source = "ai")
        return ToolResult.success("Borrador guardado para $to.")
    }
}

/** Recurring bill: a monthly reminder a few days before a charge. */
class AssistantRecurringBillTool : BaseTool() {
    override fun getName() = "assistant_recurring_bill"
    override fun getDisplayName() = "Factura recurrente"
    override fun getDescriptionEN() =
        "Track a recurring bill/subscription. Creates a monthly reminder a few days before it's " +
        "charged so the user is never surprised. Optionally records the amount."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "recordatorio mensual antes de un cobro recurrente (suscripción/factura)"
    override fun getParameters() = listOf(
        ToolParameter("name", "string", "Bill/subscription name (e.g. Netflix).", true),
        ToolParameter("day_of_month", "integer", "Day it charges (1-28).", true),
        ToolParameter("amount", "number", "Optional amount.", false),
        ToolParameter("days_before", "integer", "Days before to remind (default 2).", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val name = requireString(params, "name").trim()
        val day = requireInt(params, "day_of_month").coerceIn(1, 28)
        val daysBefore = optionalInt(params, "days_before", 2).coerceIn(0, 10)
        val amount = (params["amount"] as? Number)?.toDouble()
            ?: params["amount"]?.toString()?.toDoubleOrNull() ?: 0.0

        // Compute the next reminder date: (charge day - daysBefore) this or next month.
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.DAY_OF_MONTH, day)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 10); cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        cal.add(java.util.Calendar.DAY_OF_MONTH, -daysBefore)
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(java.util.Calendar.MONTH, 1)

        val amtStr = if (amount != 0.0) " (~${"%.2f".format(kotlin.math.abs(amount))})" else ""
        val item = com.blackclaw.android.assistant.AssistantStore.create(
            type = com.blackclaw.android.assistant.AssistantItemType.REMINDER,
            title = "💳 Cobro próximo: $name$amtStr",
            body = "Se cobra el día $day de cada mes.",
            triggerAtMs = cal.timeInMillis, repeat = "monthly",
            amount = if (amount != 0.0) -kotlin.math.abs(amount) else 0.0,
            category = "bill", source = "ai")
        com.blackclaw.android.assistant.AssistantScheduler.arm(ClawApplication.instance, item)
        return ToolResult.success(
            "Factura '$name' registrada: aviso el ${com.blackclaw.android.assistant.AssistantTime.format(cal.timeInMillis)} y cada mes.")
    }
}


/** Savings goal: set a target the weekly summary tracks against. */
class AssistantSavingsGoalTool : BaseTool() {
    override fun getName() = "assistant_savings_goal"
    override fun getDisplayName() = "Meta de ahorro"
    override fun getDescriptionEN() =
        "Set or clear the user's savings goal (a target amount, optionally named, e.g. 'vacaciones'). " +
        "The weekly finance summary will report progress toward it. amount=0 clears the goal."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "fija una meta de ahorro (la app reporta el avance)"
    override fun getParameters() = listOf(
        ToolParameter("amount", "number", "Target amount to save (0 to clear).", true),
        ToolParameter("name", "string", "Optional label for the goal (e.g. vacaciones).", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val amount = (params["amount"] as? Number)?.toDouble()
            ?: params["amount"]?.toString()?.toDoubleOrNull()
            ?: return ToolResult.error("amount inválido")
        AssistantStore.savingsGoal = amount.coerceAtLeast(0.0)
        AssistantStore.savingsGoalName = optionalString(params, "name", "").trim()
        if (amount <= 0) return ToolResult.success("Meta de ahorro eliminada.")
        val label = AssistantStore.savingsGoalName.ifBlank { "ahorro" }
        val bal = AssistantStore.financeBalance()
        return ToolResult.success(
            "Meta de $label: ${"%.2f".format(amount)}. Balance actual: ${"%.2f".format(bal)}.")
    }
}

/** Export all finance entries to a CSV file the user can open/share. */
class AssistantExportFinanceTool : BaseTool() {
    override fun getName() = "assistant_export_finance"
    override fun getDisplayName() = "Exportar finanzas (CSV)"
    override fun getDescriptionEN() =
        "Export all recorded finance entries (income & expenses) to a CSV file saved in the app's " +
        "external files (Documents/BlackClaw). Use when the user wants a backup or to open their " +
        "data in a spreadsheet. Returns the file path."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "exporta las finanzas a un archivo CSV"
    override fun getParameters() = emptyList<ToolParameter>()
    override fun execute(params: Map<String, Any>): ToolResult {
        val csv = AssistantStore.financeCsv()
        if (csv.lines().size <= 1) return ToolResult.error("No hay movimientos de finanzas para exportar.")
        return try {
            val ctx = ClawApplication.instance
            val dir = java.io.File(ctx.getExternalFilesDir(null), "exports").apply { mkdirs() }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val file = java.io.File(dir, "blackclaw_finanzas_$stamp.csv")
            file.writeText(csv)
            val rows = csv.lines().size - 1
            ToolResult.success("Exporté $rows movimientos a ${file.absolutePath}")
        } catch (e: Exception) {
            ToolResult.error("No pude escribir el CSV: ${e.message}")
        }
    }
}
