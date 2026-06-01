package com.blackclaw.android.proactive

import com.blackclaw.android.utils.KVUtils
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight learning + short-term context for the proactive assistant.
 *
 *  - Learned preferences: free-form lines the assistant accumulates about what
 *    the user cares about / ignores (fed back into the classifier prompt).
 *  - Recent events ring buffer: the last few notifications it processed, so the
 *    classifier can relate a new message to a prior one ("the meeting moved").
 *  - Rolling action counter: enforces ProactiveConfig.maxActionsPerHour.
 *  - Feedback: when the user deletes an AI-created item quickly, we learn to be
 *    more conservative about that kind of thing.
 */
object ProactiveMemory {

    private const val KEY_PREFS = "proactive_learned_prefs"      // JSON array of strings
    private const val KEY_RECENT = "proactive_recent_events"     // JSON array of {t,pkg,title,text,action}
    private const val KEY_ACTION_TIMES = "proactive_action_times" // JSON array of epoch ms
    private const val MAX_PREFS = 25
    private const val MAX_RECENT = 8

    // ── Learned preferences ──
    @Synchronized
    fun learnedPreferences(): List<String> {
        val raw = KVUtils.getString(KEY_PREFS, "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw); (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun addPreference(line: String) {
        val clean = line.trim().take(120)
        if (clean.isBlank()) return
        val list = learnedPreferences().toMutableList()
        // De-dup (case-insensitive) and cap.
        if (list.any { it.equals(clean, ignoreCase = true) }) return
        list.add(0, clean)
        while (list.size > MAX_PREFS) list.removeAt(list.size - 1)
        save(KEY_PREFS, list)
    }

    fun preferencesSnippet(): String {
        val prefs = learnedPreferences()
        if (prefs.isEmpty()) return ""
        return "## Lo aprendido sobre el usuario (respétalo)\n" +
            prefs.joinToString("\n") { "- $it" }
    }

    // ── Recent events (for relating messages) ──
    data class RecentEvent(val t: Long, val pkg: String, val title: String, val text: String, val action: String)

    @Synchronized
    fun recentEvents(): List<RecentEvent> {
        val raw = KVUtils.getString(KEY_RECENT, "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                RecentEvent(o.optLong("t"), o.optString("pkg"), o.optString("title"),
                    o.optString("text"), o.optString("action"))
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun recordEvent(pkg: String, title: String, text: String, action: String) {
        val list = recentEvents().toMutableList()
        list.add(0, RecentEvent(System.currentTimeMillis(), pkg, title.take(80), text.take(160), action))
        while (list.size > MAX_RECENT) list.removeAt(list.size - 1)
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("t", it.t); put("pkg", it.pkg); put("title", it.title)
                put("text", it.text); put("action", it.action)
            })
        }
        KVUtils.putString(KEY_RECENT, arr.toString()); KVUtils.sync()
    }

    fun recentSnippet(): String {
        val ev = recentEvents().filter { it.action != "ignore" }
        if (ev.isEmpty()) return ""
        val df = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return "## Notificaciones recientes que ya procesé (relaciona si aplica)\n" +
            ev.take(5).joinToString("\n") {
                "- [${df.format(java.util.Date(it.t))}] ${it.pkg}: ${it.title} — ${it.text} → ${it.action}"
            }
    }

    // ── Rolling action rate limit ──
    @Synchronized
    fun canAct(maxPerHour: Int): Boolean {
        val now = System.currentTimeMillis()
        val cutoff = now - 3_600_000L
        val times = actionTimes().filter { it >= cutoff }
        return times.size < maxPerHour
    }

    @Synchronized
    fun recordAction() {
        val now = System.currentTimeMillis()
        val cutoff = now - 3_600_000L
        val times = actionTimes().filter { it >= cutoff }.toMutableList()
        times.add(now)
        val arr = JSONArray(); times.forEach { arr.put(it) }
        KVUtils.putString(KEY_ACTION_TIMES, arr.toString()); KVUtils.sync()
    }

    private fun actionTimes(): List<Long> {
        val raw = KVUtils.getString(KEY_ACTION_TIMES, "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw); (0 until a.length()).map { a.getLong(it) }
        }.getOrDefault(emptyList())
    }

    private fun save(key: String, list: List<String>) {
        val arr = JSONArray(); list.forEach { arr.put(it) }
        KVUtils.putString(key, arr.toString()); KVUtils.sync()
    }
}
