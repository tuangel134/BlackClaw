package com.blackclaw.android.proactive

import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Durable state machine for plans inferred from notifications/conversations.
 *
 * It exists so the proactive assistant can remember that a plan is merely proposed,
 * later accepted, rescheduled, declined or cancelled, and can keep any generated
 * Assistant items linked to that plan instead of creating duplicates.
 */
object ProactiveCommitmentStore {
    private const val TAG = "ProactiveCommitmentStore"
    private const val KEY = "proactive_commitments_v1"
    private const val MAX_ITEMS = 48
    private const val ACTIVE_MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

    data class Commitment(
        val id: String,
        val pkg: String,
        val threadKey: String,
        val label: String,
        val eventAtMs: Long,
        val state: ProactiveDecisionPolicy.CommitmentState,
        val confidence: Double,
        /** Actions the classifier recommends if/when the user actually confirms the plan. */
        val desiredActions: String = "",
        val alarmLeadMinutes: Int = 30,
        val alarmItemId: String = "",
        val reminderItemId: String = "",
        val calendarItemId: String = "",
        val createdAtMs: Long = System.currentTimeMillis(),
        val updatedAtMs: Long = System.currentTimeMillis(),
    ) {
        fun toJson() = JSONObject().apply {
            put("id", id); put("pkg", pkg); put("threadKey", threadKey); put("label", label)
            put("eventAtMs", eventAtMs); put("state", state.name); put("confidence", confidence)
            put("desiredActions", desiredActions); put("alarmLeadMinutes", alarmLeadMinutes)
            put("alarmItemId", alarmItemId); put("reminderItemId", reminderItemId); put("calendarItemId", calendarItemId)
            put("createdAtMs", createdAtMs); put("updatedAtMs", updatedAtMs)
        }

        companion object {
            fun fromJson(o: JSONObject): Commitment? = runCatching {
                Commitment(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString().take(10) },
                    pkg = o.optString("pkg"),
                    threadKey = o.optString("threadKey"),
                    label = o.optString("label"),
                    eventAtMs = o.optLong("eventAtMs"),
                    state = ProactiveDecisionPolicy.CommitmentState.parse(o.optString("state")),
                    confidence = o.optDouble("confidence", 0.0),
                    desiredActions = o.optString("desiredActions"),
                    alarmLeadMinutes = o.optInt("alarmLeadMinutes", 30).coerceIn(0, 360),
                    alarmItemId = o.optString("alarmItemId"),
                    reminderItemId = o.optString("reminderItemId"),
                    calendarItemId = o.optString("calendarItemId"),
                    createdAtMs = o.optLong("createdAtMs", System.currentTimeMillis()),
                    updatedAtMs = o.optLong("updatedAtMs", System.currentTimeMillis()),
                )
            }.getOrNull()
        }
    }

    @Synchronized
    fun all(now: Long = System.currentTimeMillis()): List<Commitment> {
        val raw = KVUtils.getString(KEY, "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { Commitment.fromJson(arr.getJSONObject(it)) }
                .filter { now - it.updatedAtMs <= ACTIVE_MAX_AGE_MS || !ProactiveDecisionPolicy.isTerminal(it.state) }
        }.getOrElse {
            XLog.w(TAG, "Could not parse commitments: ${it.message}")
            emptyList()
        }
    }

    @Synchronized
    private fun save(items: List<Commitment>) {
        val sorted = items.sortedByDescending { it.updatedAtMs }.take(MAX_ITEMS)
        val arr = JSONArray(); sorted.forEach { arr.put(it.toJson()) }
        KVUtils.putString(KEY, arr.toString())
        // Notification classification is asynchronous; losing the last state on a hard power cut
        // is preferable to an fsync on every notification. Other writes will flush naturally.
    }

    private fun normalize(s: String): String = s.lowercase()
        .replace(Regex("[^\\p{L}\\p{N} ]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    internal fun labelSimilarity(a: String, b: String): Double {
        val aa = normalize(a).split(' ').filter { it.length >= 3 }.toSet()
        val bb = normalize(b).split(' ').filter { it.length >= 3 }.toSet()
        if (aa.isEmpty() || bb.isEmpty()) return 0.0
        val common = aa.intersect(bb).size.toDouble()
        return common / aa.union(bb).size.toDouble()
    }

    /**
     * Resolve a stable commitment in the same conversation. We prefer semantic label overlap,
     * then fall back to the only active plan in that thread. This lets "mejor a las 7" update
     * the existing 6pm plan instead of generating a second one.
     */
    @Synchronized
    fun resolve(
        pkg: String,
        threadKey: String,
        label: String,
        eventAtMs: Long,
        state: ProactiveDecisionPolicy.CommitmentState,
        confidence: Double,
        desiredActions: String = "",
        alarmLeadMinutes: Int = 30,
        now: Long = System.currentTimeMillis(),
    ): Commitment {
        val items = all(now).toMutableList()
        val sameThread = items.filter {
            it.pkg == pkg && normalize(it.threadKey) == normalize(threadKey) &&
                now - it.updatedAtMs <= ACTIVE_MAX_AGE_MS
        }
        val active = sameThread.filterNot { ProactiveDecisionPolicy.isTerminal(it.state) }
        val byLabel = active.maxByOrNull { labelSimilarity(it.label, label) }
            ?.takeIf { labelSimilarity(it.label, label) >= 0.25 }
        val existing = byLabel ?: active.singleOrNull()

        val resolved = if (existing != null) {
            existing.copy(
                label = label.ifBlank { existing.label },
                eventAtMs = if (eventAtMs > 0) eventAtMs else existing.eventAtMs,
                state = state,
                confidence = confidence,
                desiredActions = desiredActions.ifBlank { existing.desiredActions },
                alarmLeadMinutes = alarmLeadMinutes.coerceIn(0, 360),
                updatedAtMs = now,
            )
        } else {
            Commitment(
                id = UUID.randomUUID().toString().take(10),
                pkg = pkg,
                threadKey = threadKey,
                label = label,
                eventAtMs = eventAtMs,
                state = state,
                confidence = confidence,
                desiredActions = desiredActions,
                alarmLeadMinutes = alarmLeadMinutes.coerceIn(0, 360),
                createdAtMs = now,
                updatedAtMs = now,
            )
        }
        items.removeAll { it.id == resolved.id }
        items.add(resolved)
        save(items)
        return resolved
    }

    @Synchronized
    fun find(id: String): Commitment? = all().firstOrNull { it.id == id }

    @Synchronized
    fun updateLinks(
        id: String,
        alarmItemId: String? = null,
        reminderItemId: String? = null,
        calendarItemId: String? = null,
    ): Commitment? {
        val items = all().toMutableList()
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val old = items[idx]
        val next = old.copy(
            alarmItemId = alarmItemId ?: old.alarmItemId,
            reminderItemId = reminderItemId ?: old.reminderItemId,
            calendarItemId = calendarItemId ?: old.calendarItemId,
            updatedAtMs = System.currentTimeMillis(),
        )
        items[idx] = next
        save(items)
        return next
    }

    @Synchronized
    fun markState(id: String, state: ProactiveDecisionPolicy.CommitmentState): Commitment? {
        val items = all().toMutableList()
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val next = items[idx].copy(state = state, updatedAtMs = System.currentTimeMillis())
        items[idx] = next
        save(items)
        return next
    }

    @Synchronized
    fun clearLinks(id: String): Commitment? {
        val items = all().toMutableList()
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val next = items[idx].copy(
            alarmItemId = "",
            reminderItemId = "",
            calendarItemId = "",
            updatedAtMs = System.currentTimeMillis(),
        )
        items[idx] = next
        save(items)
        return next
    }

    fun isConfirmed(id: String): Boolean = find(id)?.let { ProactiveDecisionPolicy.isConfirmed(it.state) } == true

    @Synchronized
    fun clear() {
        KVUtils.putString(KEY, "")
        KVUtils.sync()
    }
}
