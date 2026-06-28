package com.blackclaw.android.tool.impl

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import com.blackclaw.android.utils.KVUtils
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Wellness & mood tracking tools.
 * Tracks mood, energy, sleep quality, screen time awareness, and provides insights.
 */

class MoodLogTool : BaseTool() {
    override fun getName() = "mood_log"
    override fun getDisplayName() = "Registrar ánimo"
    override fun getDescriptionEN() =
        "Log the user's current mood/energy level. Scale 1-10 or descriptive. " +
        "Use when user says 'estoy cansado', 'me siento bien', 'estoy estresado', etc."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "registra el estado de ánimo/energía del usuario"
    override fun getParameters() = listOf(
        ToolParameter("mood", "string", "Mood description or 1-10 score.", true),
        ToolParameter("energy", "string", "Energy level: low|medium|high or 1-10.", false),
        ToolParameter("note", "string", "Optional context (why they feel this way).", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val mood = requireString(params, "mood").trim()
        val energy = optionalString(params, "energy", "")
        val note = optionalString(params, "note", "")
        val result = WellnessStore.logMood(mood, energy, note)
        return ToolResult.success(result)
    }
}

class WellnessStatusTool : BaseTool() {
    override fun getName() = "wellness_status"
    override fun getDisplayName() = "Estado bienestar"
    override fun getDescriptionEN() =
        "Show wellness overview: recent moods, sleep inference, screen time, and suggestions."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "muestra resumen de bienestar (ánimo, sueño, pantalla)"
    override fun getParameters() = emptyList<ToolParameter>()
    override fun execute(params: Map<String, Any>): ToolResult {
        return ToolResult.success(WellnessStore.weeklyOverview())
    }
}

class SleepLogTool : BaseTool() {
    override fun getName() = "sleep_log"
    override fun getDisplayName() = "Registrar sueño"
    override fun getDescriptionEN() =
        "Log sleep data. Use when user says 'dormí 7 horas', 'me acosté tarde', 'dormí mal', etc."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "registra las horas/calidad de sueño"
    override fun getParameters() = listOf(
        ToolParameter("hours", "number", "Hours slept (e.g. 7.5).", false),
        ToolParameter("quality", "string", "bad|ok|good|great. Or 1-10.", false),
        ToolParameter("note", "string", "Optional note (e.g. 'me desperté a las 3').", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val hours = (params["hours"] as? Number)?.toDouble()
            ?: params["hours"]?.toString()?.toDoubleOrNull()
        val quality = optionalString(params, "quality", "")
        val note = optionalString(params, "note", "")
        val result = WellnessStore.logSleep(hours, quality, note)
        return ToolResult.success(result)
    }
}

/**
 * Persistent wellness data store.
 */
object WellnessStore {
    private const val KEY_MOOD = "wellness_mood_log_v1"
    private const val KEY_SLEEP = "wellness_sleep_log_v1"
    private const val MAX_ENTRIES = 60  // ~2 months

    data class MoodEntry(val timestamp: Long, val mood: String, val energy: String, val note: String)
    data class SleepEntry(val timestamp: Long, val hours: Double?, val quality: String, val note: String)

    fun logMood(mood: String, energy: String, note: String): String {
        val entries = moodEntries().toMutableList()
        entries.add(MoodEntry(System.currentTimeMillis(), mood, energy, note))
        if (entries.size > MAX_ENTRIES) entries.removeAt(0)
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("t", e.timestamp); put("m", e.mood); put("e", e.energy); put("n", e.note)
            })
        }
        KVUtils.putString(KEY_MOOD, arr.toString()); KVUtils.sync()

        val moodEmoji = when {
            mood.contains("bien") || mood.contains("happy") || mood.toIntOrNull()?.let { it >= 7 } == true -> "😊"
            mood.contains("mal") || mood.contains("triste") || mood.contains("sad") || mood.toIntOrNull()?.let { it <= 3 } == true -> "😔"
            mood.contains("estres") || mood.contains("stress") || mood.contains("ansi") -> "😰"
            mood.contains("cansan") || mood.contains("tired") -> "😴"
            else -> "📝"
        }
        return "$moodEmoji Ánimo registrado: $mood${if (energy.isNotBlank()) " | Energía: $energy" else ""}${if (note.isNotBlank()) " ($note)" else ""}"
    }

    fun logSleep(hours: Double?, quality: String, note: String): String {
        val entries = sleepEntries().toMutableList()
        entries.add(SleepEntry(System.currentTimeMillis(), hours, quality, note))
        if (entries.size > MAX_ENTRIES) entries.removeAt(0)
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("t", e.timestamp); if (e.hours != null) put("h", e.hours)
                put("q", e.quality); put("n", e.note)
            })
        }
        KVUtils.putString(KEY_SLEEP, arr.toString()); KVUtils.sync()

        val emoji = when {
            hours != null && hours >= 7 -> "😴✓"
            hours != null && hours < 6 -> "⚠️"
            quality.contains("bad") || quality.contains("mal") -> "😟"
            else -> "🌙"
        }
        val hoursStr = if (hours != null) "${hours}h" else ""
        val qualityStr = if (quality.isNotBlank()) " ($quality)" else ""
        return "$emoji Sueño registrado: $hoursStr$qualityStr${if (note.isNotBlank()) " — $note" else ""}"
    }

    fun weeklyOverview(): String {
        val moods = moodEntries().filter { System.currentTimeMillis() - it.timestamp < 7 * 86_400_000 }
        val sleeps = sleepEntries().filter { System.currentTimeMillis() - it.timestamp < 7 * 86_400_000 }

        return buildString {
            append("🧘 Bienestar esta semana:\n\n")
            if (moods.isNotEmpty()) {
                append("Estado de ánimo (${moods.size} registros):\n")
                moods.takeLast(5).forEach { m ->
                    val df = SimpleDateFormat("EEE HH:mm", Locale.getDefault())
                    append("  ${df.format(Date(m.timestamp))}: ${m.mood}")
                    if (m.energy.isNotBlank()) append(" (energía: ${m.energy})")
                    append("\n")
                }
                append("\n")
            }
            if (sleeps.isNotEmpty()) {
                val avgHours = sleeps.mapNotNull { it.hours }.average()
                append("Sueño (${sleeps.size} noches):\n")
                if (avgHours > 0) append("  Promedio: ${"%.1f".format(avgHours)}h/noche\n")
                sleeps.takeLast(3).forEach { s ->
                    val df = SimpleDateFormat("EEE", Locale.getDefault())
                    append("  ${df.format(Date(s.timestamp))}: ${s.hours ?: "?"}h ${s.quality}\n")
                }
                append("\n")
            }
            if (moods.isEmpty() && sleeps.isEmpty()) {
                append("No hay datos esta semana. Dime cómo te sientes o cuánto dormiste para empezar a rastrear.")
            } else {
                // Simple insight
                val avgMoodScore = moods.mapNotNull { it.mood.toIntOrNull() }.average()
                if (avgMoodScore > 0) {
                    if (avgMoodScore >= 7) append("💪 Tu ánimo promedio es bueno (${"%.1f".format(avgMoodScore)}/10). ¡Sigue así!")
                    else if (avgMoodScore <= 4) append("💙 Tu ánimo ha estado bajo (${"%.1f".format(avgMoodScore)}/10). ¿Hay algo en lo que pueda ayudarte?")
                }
            }
        }
    }

    private fun moodEntries(): List<MoodEntry> {
        val raw = KVUtils.getString(KEY_MOOD, "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { val o = arr.getJSONObject(it)
                MoodEntry(o.optLong("t"), o.optString("m"), o.optString("e", ""), o.optString("n", ""))
            }
        }.getOrDefault(emptyList())
    }

    private fun sleepEntries(): List<SleepEntry> {
        val raw = KVUtils.getString(KEY_SLEEP, "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { val o = arr.getJSONObject(it)
                SleepEntry(o.optLong("t"), if (o.has("h")) o.getDouble("h") else null,
                    o.optString("q", ""), o.optString("n", ""))
            }
        }.getOrDefault(emptyList())
    }
}
