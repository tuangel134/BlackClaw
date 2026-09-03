package com.blackclaw.android.tool.impl

import com.blackclaw.android.assistant.RoutineEngine
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Tools for managing and executing Routines — multi-step automated sequences.
 */

/** Execute an existing routine by name or id. */
class RunRoutineTool : BaseTool() {
    override fun getName() = "run_routine"
    override fun getDisplayName() = "Ejecutar rutina"
    override fun getDescriptionEN() =
        "Execute a saved routine by name (fuzzy match). Routines are multi-step automated " +
        "sequences like 'morning routine', 'focus mode', 'workout'. Use list_routines to see available ones."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "ejecuta una rutina guardada (secuencia multi-paso)"
    override fun getParameters() = listOf(
        ToolParameter("name", "string", "Routine name or id (fuzzy match).", true),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val query = requireString(params, "name").trim()
        val routine = RoutineEngine.findByName(query) ?: RoutineEngine.find(query)
            ?: return ToolResult.error("No encontré rutina '$query'. Usa list_routines para ver las disponibles.")
        val result = RoutineEngine.execute(routine)
        return if (result.success) ToolResult.success(result.summary)
        else ToolResult.error(result.summary)
    }
}

/** List all saved routines. */
class ListRoutinesTool : BaseTool() {
    override fun getName() = "list_routines"
    override fun getDisplayName() = "Ver rutinas"
    override fun getDescriptionEN() =
        "List all saved routines with their schedules and step count."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "lista todas las rutinas guardadas"
    override fun getParameters() = emptyList<ToolParameter>()
    override fun execute(params: Map<String, Any>): ToolResult {
        val routines = RoutineEngine.all()
        if (routines.isEmpty()) return ToolResult.success("No hay rutinas guardadas.")
        val sb = StringBuilder()
        routines.forEach { r ->
            sb.append("${r.icon} [${r.id}] ${r.name}")
            if (r.triggerTime.isNotBlank()) sb.append(" ⏰ ${r.triggerTime} ${r.triggerDays}")
            sb.append(" (${r.steps.size} pasos, ejecutada ${r.runCount}x)")
            if (!r.enabled) sb.append(" [deshabilitada]")
            sb.append("\n")
            if (r.description.isNotBlank()) sb.append("   ${r.description}\n")
        }
        return ToolResult.success(sb.toString().trim())
    }
}

/** Create a new routine. Steps are passed as a JSON array. */
class CreateRoutineTool : BaseTool() {
    override fun getName() = "create_routine"
    override fun getDisplayName() = "Crear rutina"
    override fun getDescriptionEN() =
        "Create a multi-step routine the user can run later or schedule. " +
        "steps is a JSON array: [{\"tool\":\"tool_name\",\"params\":{...},\"desc\":\"what this step does\"}]. " +
        "trigger_time (optional) is 'HH:MM' to auto-run daily. trigger_days: 'daily'|'weekdays'|'mon,wed,fri'."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "crea una rutina multi-paso (morning routine, focus mode, etc.)"
    override fun getParameters() = listOf(
        ToolParameter("name", "string", "Routine name (e.g. 'Rutina mañana').", true),
        ToolParameter("steps", "string", "JSON array of steps: [{\"tool\":\"...\",\"params\":{},\"desc\":\"...\"}].", true),
        ToolParameter("description", "string", "Short description of what the routine does.", false),
        ToolParameter("icon", "string", "Emoji icon (default ⚡).", false),
        ToolParameter("trigger_time", "string", "Auto-run at this time daily (HH:MM). Omit for manual only.", false),
        ToolParameter("trigger_days", "string", "When to auto-run: daily|weekdays|mon,wed,fri. Default daily.", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val name = requireString(params, "name").trim()
        val stepsJson = requireString(params, "steps")

        val steps = try {
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            val parsed: List<Map<String, Any>> = Gson().fromJson(stepsJson, type)
            parsed.map { stepMap ->
                val toolName = stepMap["tool"]?.toString() ?: return ToolResult.error("Cada step necesita 'tool'.")
                @Suppress("UNCHECKED_CAST")
                val stepParams = (stepMap["params"] as? Map<String, Any>) ?: emptyMap()
                val desc = stepMap["desc"]?.toString() ?: ""
                val delay = (stepMap["delay"] as? Number)?.toLong() ?: 1000L
                RoutineEngine.RoutineStep(toolName, stepParams, delay, desc)
            }
        } catch (e: Exception) {
            return ToolResult.error("Error parseando steps JSON: ${e.message}")
        }

        if (steps.isEmpty()) return ToolResult.error("La rutina necesita al menos un paso.")

        val routine = RoutineEngine.create(RoutineEngine.Routine(
            id = "",
            name = name,
            description = optionalString(params, "description", ""),
            icon = optionalString(params, "icon", "⚡"),
            steps = steps,
            triggerTime = optionalString(params, "trigger_time", ""),
            triggerDays = optionalString(params, "trigger_days", "daily"),
        )) ?: return ToolResult.error("No pude guardar la rutina de forma segura. Inténtalo de nuevo después de desbloquear el dispositivo.")

        val schedule = if (routine.triggerTime.isNotBlank())
            " Programada: ${routine.triggerTime} (${routine.triggerDays})" else " (manual)"
        return ToolResult.success("Rutina '${routine.name}' creada con ${steps.size} pasos.$schedule")
    }
}

/** Delete a routine by name or id. */
class DeleteRoutineTool : BaseTool() {
    override fun getName() = "delete_routine"
    override fun getDisplayName() = "Borrar rutina"
    override fun getDescriptionEN() = "Delete a routine by name or id."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "borra una rutina por nombre o id"
    override fun getParameters() = listOf(
        ToolParameter("name", "string", "Routine name or id.", true),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val query = requireString(params, "name").trim()
        val routine = RoutineEngine.findByName(query) ?: RoutineEngine.find(query)
            ?: return ToolResult.error("No encontré rutina '$query'.")
        if (!RoutineEngine.delete(routine.id)) {
            return ToolResult.error("No pude eliminar la rutina de forma segura.")
        }
        return ToolResult.success("Rutina '${routine.name}' eliminada.")
    }
}

/** Tool for the AI to learn about the user profile. */
class LearnUserTool : BaseTool() {
    override fun getName() = "learn_user"
    override fun getDisplayName() = "Aprender del usuario"
    override fun getDescriptionEN() =
        "Save a learned trait or preference about the user to their profile. " +
        "Use when you notice a pattern or the user reveals something about themselves. " +
        "Categories: name, city, wake_time, sleep_time, work_hours, interest, contact, routine_note, trait. " +
        "This builds a profile so you can anticipate their needs better over time."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "guarda un rasgo/preferencia aprendida del usuario en su perfil"
    override fun getParameters() = listOf(
        ToolParameter("category", "string", "What kind of info: name|city|wake_time|sleep_time|work_hours|interest|contact|routine_note|trait.", true),
        ToolParameter("value", "string", "The value to save.", true),
        ToolParameter("key", "string", "For 'trait': a custom key name.", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val category = requireString(params, "category").trim().lowercase()
        val value = requireString(params, "value").trim()
        if (value.isBlank()) return ToolResult.error("Valor vacío.")

        val profile = com.blackclaw.android.memory.UserProfile.get()
        val updated = when (category) {
            "name" -> profile.copy(name = value)
            "city" -> profile.copy(city = value)
            "wake_time" -> profile.copy(wakeUpHour = value.filter { it.isDigit() }.take(2).toIntOrNull() ?: -1)
            "sleep_time" -> profile.copy(sleepHour = value.filter { it.isDigit() }.take(2).toIntOrNull() ?: -1)
            "work_hours" -> {
                val parts = value.split("-", "–", "a").map { it.trim().filter { c -> c.isDigit() } }
                if (parts.size == 2) {
                    profile.copy(
                        workStartHour = parts[0].toIntOrNull() ?: -1,
                        workEndHour = parts[1].toIntOrNull() ?: -1,
                    )
                } else profile
            }
            "interest" -> {
                val interests = profile.interests.toMutableList()
                if (value !in interests) interests.add(value)
                profile.copy(interests = interests.takeLast(10))
            }
            "contact" -> {
                val contacts = profile.topContacts.toMutableList()
                if (value !in contacts) contacts.add(value)
                profile.copy(topContacts = contacts.takeLast(10))
            }
            "routine_note" -> profile.copy(routineNotes = value.take(200))
            "trait" -> {
                val key = optionalString(params, "key", category)
                val traits = profile.traits.toMutableMap()
                traits[key] = value
                profile.copy(traits = traits)
            }
            else -> {
                val traits = profile.traits.toMutableMap()
                traits[category] = value
                profile.copy(traits = traits)
            }
        }
        if (!com.blackclaw.android.memory.UserProfile.save(updated)) {
            return ToolResult.error("No pude guardar ese aprendizaje de forma segura. Inténtalo de nuevo después de desbloquear el dispositivo.")
        }
        return ToolResult.success("Aprendido: $category = '$value'. Lo tendré en cuenta.")
    }
}
