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
class AssistantNoteTool : BaseTool() {
    override fun getName() = "assistant_note"
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

/** Immediate alert push (no scheduling). */
class AssistantAlertTool : BaseTool() {
    override fun getName() = "assistant_alert"
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
