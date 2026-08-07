package com.blackclaw.android.proactive

import com.blackclaw.android.memory.JsonListStore
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
    private const val KEY_CLASSIFY_TIMES = "proactive_classify_times" // JSON array of epoch ms
    private const val KEY_CORRECTIONS = "proactive_corrections"  // JSON obj: category -> {rejects, quickRejects, lastT}
    private const val KEY_PKG_STATS = "proactive_pkg_stats"      // JSON obj: pkg -> {total, ignores}
    private const val MAX_PREFS = 25
    private const val MAX_RECENT = 8
    private const val ONE_HOUR_MS = 3_600_000L

    /** A deletion within this window of creation counts as a "quick reject" (stronger signal). */
    const val QUICK_REJECT_MS = 10 * 60 * 1000L

    // ── Learned preferences ──
    //
    // Kept hand-rolled, and deliberately. This is an array of bare strings, while
    // JsonListStore stores each item as a JSON object. Moving it would rewrite
    // ["no me avises de promociones"] as [{"v":"no me avises de promociones"}], and every
    // existing user's array would then fail to parse — throwing away learnings the
    // assistant accumulated over weeks to save a dozen lines here. The same reasoning
    // covers KEY_CORRECTIONS and KEY_PKG_STATS below, which are maps rather than lists
    // and have no list-shaped equivalent at all.
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

    /**
     * The one list here that is a list of records, so the one that moves to
     * [JsonListStore]. Notably this removes an fsync from the notification path: events
     * are recorded from a `NotificationListenerService` callback, which is exactly where
     * a synchronous flush is most expensive and least useful.
     *
     * Stored oldest first — [JsonListStore]'s convention — while [recentEvents] keeps
     * returning newest first, since that is the order the classifier prompt reads them in.
     */
    private val recentStore = object : JsonListStore<RecentEvent>(KEY_RECENT, MAX_RECENT) {
        override val logTag = "ProactiveMemory"
        override fun toJson(item: RecentEvent): JSONObject = JSONObject().apply {
            put("t", item.t)
            put("pkg", item.pkg)
            put("title", item.title)
            put("text", item.text)
            put("action", item.action)
        }

        override fun fromJson(json: JSONObject): RecentEvent? = RecentEvent(
            t = json.optLong("t"),
            pkg = json.optString("pkg"),
            title = json.optString("title"),
            text = json.optString("text"),
            action = json.optString("action"),
        )

        override fun timestampOf(item: RecentEvent): Long = item.t
    }

    /** Recently processed notifications, **newest first**. */
    fun recentEvents(): List<RecentEvent> = recentStore.all().asReversed()

    fun recordEvent(pkg: String, title: String, text: String, action: String) {
        recentStore.append(
            RecentEvent(System.currentTimeMillis(), pkg, title.take(80), text.take(160), action)
        )
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

    /**
     * A rolling count of when something happened, used for the per-hour limits.
     *
     * ## Why not [JsonListStore]
     *
     * This looks like the same shape and is not. [JsonListStore] stores records as JSON
     * objects and keeps the newest N; this stores bare epoch numbers and keeps whatever
     * falls inside a time window, with no fixed count. Bending it to fit would mean
     * rewriting `[1770000000000, …]` as `[{"t":…}, …]`, and the old array would then read
     * back as unparseable — silently resetting the rate limiter for every existing user.
     *
     * So: a second, smaller abstraction rather than one abstraction wearing a disguise.
     * The duplication that mattered — two byte-identical copies of read-filter-append,
     * each ending in an fsync — is gone either way.
     */
    private class RollingWindow(private val key: String, private val windowMs: Long) {
        private val lock = Any()

        /** Timestamps inside the window. */
        fun current(now: Long = System.currentTimeMillis()): List<Long> = synchronized(lock) {
            read().filter { it >= now - windowMs }
        }

        /** Records [now] and prunes anything that has aged out. */
        fun record(now: Long = System.currentTimeMillis()) = synchronized(lock) {
            val kept = read().filter { it >= now - windowMs } + now
            val array = JSONArray()
            kept.forEach { array.put(it) }
            KVUtils.putString(key, array.toString())
            // No sync(). Both callers run on notification delivery; an fsync there costs
            // more than the worst case of losing a rate-limit tick on a hard power cut.
        }

        private fun read(): List<Long> {
            val raw = KVUtils.getString(key, "")
            if (raw.isBlank()) return emptyList()
            return runCatching {
                val a = JSONArray(raw)
                (0 until a.length()).map { a.optLong(it) }.filter { it > 0L }
            }.getOrDefault(emptyList())
        }
    }

    private val actionWindow = RollingWindow(KEY_ACTION_TIMES, ONE_HOUR_MS)

    /**
     * Separate window from actions: this bounds how often the LLM is woken even when it
     * decides to do nothing, which is what protects against a notification storm.
     */
    private val classifyWindow = RollingWindow(KEY_CLASSIFY_TIMES, ONE_HOUR_MS)

    fun canAct(maxPerHour: Int): Boolean = actionWindow.current().size < maxPerHour

    fun recordAction() = actionWindow.record()

    fun canClassify(maxPerHour: Int): Boolean = classifyWindow.current().size < maxPerHour

    fun recordClassification() = classifyWindow.record()

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

    /** Guidance line fed into the classifier so it's aware of previous corrections,
     *  but still biases toward action. Only surfaces for heavily-corrected categories. */
    fun correctionGuidanceSnippet(): String {
        val cats = correctedCategories()
        if (cats.isEmpty()) return ""
        val esName = mapOf(
            "reminder" to "recordatorios", "alarm" to "alarmas", "finance" to "registros de finanzas",
            "note" to "notas", "event" to "eventos", "alert" to "avisos", "draft" to "borradores",
            "shopping" to "items de compras",
        )
        return "## Ajustes aprendidos (el usuario borró algunos de estos)\n" +
            cats.joinToString("\n") { (cat, _) ->
                "- ${esName[cat] ?: cat}: el usuario borró algunos. Asegúrate de que sean realmente relevantes antes de crear."
            }
    }

    private fun correctionsObj(): JSONObject {
        val raw = KVUtils.getString(KEY_CORRECTIONS, "")
        if (raw.isBlank()) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    // ── Per-package preference learning ──
    // We tally ignored notifications for adaptive local filtering and diagnostics.
    // This signal must never disable monitoring: a later notification from the
    // same app can still be urgent even if the previous hundred were irrelevant.

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
