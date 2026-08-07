package com.blackclaw.android.automation

import com.blackclaw.android.utils.KVUtils
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Persistent event-driven automations. Clock/cron rules continue using ScheduledTaskManager. */
object AutomationRuleStore {
    private const val KEY = "automation_rules_v1"
    private const val MAX_RULES = 100

    enum class Trigger { NOTIFICATION, LOCATION_ENTER, LOCATION_EXIT }

    data class Rule(
        val id: String,
        val name: String,
        val enabled: Boolean,
        val trigger: Trigger,
        val match: String,
        val packageName: String,
        val actionText: String,
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
        val radiusM: Float = 150f,
        val cooldownMs: Long = 5 * 60_000L,
        val createdAtMs: Long = System.currentTimeMillis(),
        val lastRunAtMs: Long = 0L,
        val runCount: Int = 0,
    ) {
        fun toJson() = JSONObject().apply {
            put("id", id); put("name", name); put("enabled", enabled); put("trigger", trigger.name)
            put("match", match); put("package", packageName); put("action", actionText)
            put("lat", latitude); put("lon", longitude); put("radius", radiusM.toDouble())
            put("cooldown", cooldownMs); put("created", createdAtMs); put("lastRun", lastRunAtMs)
            put("runCount", runCount)
        }

        companion object {
            fun fromJson(o: JSONObject) = Rule(
                id = o.optString("id"), name = o.optString("name"), enabled = o.optBoolean("enabled", true),
                trigger = runCatching { Trigger.valueOf(o.optString("trigger")) }.getOrDefault(Trigger.NOTIFICATION),
                match = o.optString("match"), packageName = o.optString("package"), actionText = o.optString("action"),
                latitude = o.optDouble("lat"), longitude = o.optDouble("lon"),
                radiusM = o.optDouble("radius", 150.0).toFloat(), cooldownMs = o.optLong("cooldown", 300_000L),
                createdAtMs = o.optLong("created"), lastRunAtMs = o.optLong("lastRun"), runCount = o.optInt("runCount"),
            )
        }
    }

    @Synchronized fun list(): List<Rule> {
        val raw = KVUtils.getString(KEY, "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { runCatching { Rule.fromJson(a.getJSONObject(it)) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    @Synchronized fun create(
        name: String, trigger: Trigger, match: String, packageName: String, actionText: String,
        latitude: Double = 0.0, longitude: Double = 0.0, radiusM: Float = 150f, cooldownMs: Long = 300_000L,
    ): Rule {
        require(name.isNotBlank() && actionText.isNotBlank())
        val rule = Rule(UUID.randomUUID().toString().take(8), name.trim().take(80), true, trigger,
            match.trim().take(200), packageName.trim(), actionText.trim().take(2_000), latitude, longitude,
            radiusM.coerceIn(50f, 5_000f), cooldownMs.coerceIn(10_000L, 7 * 24 * 60 * 60_000L))
        save((list().filterNot { it.name.equals(rule.name, true) } + rule).takeLast(MAX_RULES))
        return rule
    }

    @Synchronized fun delete(idOrName: String): Boolean = mutate(idOrName) { null }
    @Synchronized fun setEnabled(idOrName: String, enabled: Boolean): Boolean = mutate(idOrName) { it.copy(enabled = enabled) }
    @Synchronized fun markRun(id: String, now: Long = System.currentTimeMillis()) = mutate(id) {
        it.copy(lastRunAtMs = now, runCount = it.runCount + 1)
    }

    private fun mutate(idOrName: String, change: (Rule) -> Rule?): Boolean {
        val all = list().toMutableList()
        val index = all.indexOfFirst { it.id == idOrName || it.name.equals(idOrName, true) }
        if (index < 0) return false
        val changed = change(all[index])
        if (changed == null) all.removeAt(index) else all[index] = changed
        save(all); return true
    }

    private fun save(rules: List<Rule>) {
        val a = JSONArray(); rules.forEach { a.put(it.toJson()) }
        KVUtils.putString(KEY, a.toString()); KVUtils.sync()
    }
}
