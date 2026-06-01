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
    private const val KEY_CORRECTIONS = "proactive_corrections"  // JSON obj: category -> {rejects, quickRejects, lastT}
    private const val KEY_PKG_STATS = "proactive_pkg_stats"      // JSON obj: pkg -> {total, ignores}
    private const val KEY_PKG_PROPOSED = "proactive_pkg_proposed" // JSON array of pkgs already proposed to mute
    private const val MAX_PREFS = 25
    private const val MAX_RECENT = 8

    /** Mute proposal triggers once a package has this many notifications, mostly ignored. */
    const val MUTE_MIN_TOTAL = 5
    const val MUTE_IGNORE_RATIO = 0.85

    /** A deletion within this window of creation counts as a "quick reject" (stronger signal). */
    const val QUICK_REJECT_MS = 10 * 60 * 1000L

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
        // Per-package tally (persistent) for preference learning.
        if (pkg.isNotBlank()) recordPkgStat(pkg, action == "ignore")
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

    // ── Correction learning ──
    // When the user deletes something the assistant created, that's negative
    // feedback. We tally rejects per "category" (the item type, e.g. reminder /
    // alarm / finance / draft). Repeated/quick rejects make the assistant more
    // conservative about that category via [correctionGuidanceSnippet], which is
    // fed back into the proactive classifier prompt.

    data class CategoryFeedback(val rejects: Int, val quickRejects: Int, val lastT: Long)

    /**
     * Record that an AI-created item was deleted. [ageMs] is how long the item
     * existed before deletion; a short age is a stronger "no, don't do this".
     * Returns the running reject count for the category.
     */
    @Synchronized
    fun recordCorrection(category: String, ageMs: Long): Int {
        val cat = category.lowercase().trim().ifBlank { "other" }
        val obj = correctionsObj()
        val existing = obj.optJSONObject(cat)
        val rejects = (existing?.optInt("rejects") ?: 0) + 1
        val quick = (existing?.optInt("quickRejects") ?: 0) + (if (ageMs in 0 until QUICK_REJECT_MS) 1 else 0)
        obj.put(cat, JSONObject().apply {
            put("rejects", rejects); put("quickRejects", quick); put("lastT", System.currentTimeMillis())
        })
        KVUtils.putString(KEY_CORRECTIONS, obj.toString()); KVUtils.sync()
        return rejects
    }

    @Synchronized
    fun categoryFeedback(category: String): CategoryFeedback {
        val o = correctionsObj().optJSONObject(category.lowercase().trim()) ?: return CategoryFeedback(0, 0, 0)
        return CategoryFeedback(o.optInt("rejects"), o.optInt("quickRejects"), o.optLong("lastT"))
    }

    /**
     * How strongly the assistant should hold back on a category, 0.0 (no signal)
     * to 1.0 (frequently rejected). Decays over time so old corrections fade.
     */
    fun conservatismFor(category: String): Double {
        val fb = categoryFeedback(category)
        return conservatismScore(fb.rejects, fb.quickRejects, ageMs = System.currentTimeMillis() - fb.lastT)
    }

    /**
     * Pure scoring: quick rejects weigh double; saturates around 4 weighted
     * rejects. If the last correction is old, the score decays linearly to 0
     * over [decayWindowMs] (default 30 days) so the assistant stops being timid
     * about something the user only disliked once, long ago. Extracted so it's
     * unit-testable without MMKV.
     */
    fun conservatismScore(
        rejects: Int,
        quickRejects: Int,
        ageMs: Long = 0L,
        decayWindowMs: Long = 30L * 24 * 60 * 60 * 1000,
    ): Double {
        if (rejects <= 0) return 0.0
        val weighted = rejects + quickRejects
        val base = (weighted / 4.0).coerceIn(0.0, 1.0)
        if (ageMs <= 0L) return base
        val decay = (1.0 - ageMs.toDouble() / decayWindowMs).coerceIn(0.0, 1.0)
        return base * decay
    }

    /** Categories the user has pushed back on enough to warrant caution. */
    private fun correctedCategories(): List<Pair<String, Double>> {
        val obj = correctionsObj()
        return obj.keys().asSequence()
            .map { it to conservatismFor(it) }
            .filter { it.second >= 0.5 }
            .sortedByDescending { it.second }
            .toList()
    }

    /** Guidance line fed into the classifier so it backs off on rejected categories. */
    fun correctionGuidanceSnippet(): String {
        val cats = correctedCategories()
        if (cats.isEmpty()) return ""
        val esName = mapOf(
            "reminder" to "recordatorios", "alarm" to "alarmas", "finance" to "registros de finanzas",
            "note" to "notas", "event" to "eventos", "alert" to "avisos", "draft" to "borradores",
            "shopping" to "items de compras",
        )
        return "## Correcciones del usuario (sé más prudente)\n" +
            cats.joinToString("\n") { (cat, _) ->
                "- El usuario suele borrar los ${esName[cat] ?: cat} que creo solo/a. " +
                    "Crea ${esName[cat] ?: cat} únicamente si es claramente necesario; si dudas, ignora o pregunta."
            }
    }

    private fun correctionsObj(): JSONObject {
        val raw = KVUtils.getString(KEY_CORRECTIONS, "")
        if (raw.isBlank()) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    // ── Per-package preference learning ──
    // We tally, per notifying app, how many of its notifications the assistant
    // ended up ignoring. When an app is almost always ignored, that's a strong
    // hint the user doesn't care about it — so the assistant can PROPOSE (once)
    // to stop watching it, instead of waking the LLM on every one of its pings.

    data class PkgStat(val total: Int, val ignores: Int) {
        val ignoreRatio: Double get() = if (total == 0) 0.0 else ignores.toDouble() / total
    }

    @Synchronized
    fun recordPkgStat(pkg: String, ignored: Boolean) {
        val obj = pkgStatsObj()
        val existing = obj.optJSONObject(pkg)
        val total = (existing?.optInt("total") ?: 0) + 1
        val ignores = (existing?.optInt("ignores") ?: 0) + (if (ignored) 1 else 0)
        obj.put(pkg, JSONObject().apply { put("total", total); put("ignores", ignores) })
        KVUtils.putString(KEY_PKG_STATS, obj.toString()); KVUtils.sync()
    }

    @Synchronized
    fun pkgStat(pkg: String): PkgStat {
        val o = pkgStatsObj().optJSONObject(pkg) ?: return PkgStat(0, 0)
        return PkgStat(o.optInt("total"), o.optInt("ignores"))
    }

    /**
     * Pure: should we propose muting this app? True when it has enough history
     * and is ignored most of the time. Extracted for unit testing.
     */
    fun shouldProposeMute(
        stat: PkgStat,
        minTotal: Int = MUTE_MIN_TOTAL,
        ignoreRatio: Double = MUTE_IGNORE_RATIO,
    ): Boolean = stat.total >= minTotal && stat.ignoreRatio >= ignoreRatio

    /**
     * The first watched package that's a good mute candidate and hasn't been
     * proposed yet. Returns null if none. Side-effect-free except for reading.
     */
    @Synchronized
    fun nextMuteCandidate(): String? {
        val obj = pkgStatsObj()
        val proposed = proposedMutes()
        for (pkg in obj.keys()) {
            if (pkg in proposed) continue
            if (shouldProposeMute(pkgStat(pkg))) return pkg
        }
        return null
    }

    @Synchronized
    fun markMuteProposed(pkg: String) {
        val set = proposedMutes().toMutableSet()
        set.add(pkg)
        val arr = JSONArray(); set.forEach { arr.put(it) }
        KVUtils.putString(KEY_PKG_PROPOSED, arr.toString()); KVUtils.sync()
    }

    private fun proposedMutes(): Set<String> {
        val raw = KVUtils.getString(KEY_PKG_PROPOSED, "")
        if (raw.isBlank()) return emptySet()
        return runCatching {
            val a = JSONArray(raw); (0 until a.length()).map { a.getString(it) }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun pkgStatsObj(): JSONObject {
        val raw = KVUtils.getString(KEY_PKG_STATS, "")
        if (raw.isBlank()) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun save(key: String, list: List<String>) {
        val arr = JSONArray(); list.forEach { arr.put(it) }
        KVUtils.putString(key, arr.toString()); KVUtils.sync()
    }
}
