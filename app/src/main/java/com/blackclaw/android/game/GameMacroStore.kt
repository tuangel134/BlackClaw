package com.blackclaw.android.game

import com.blackclaw.android.utils.KVUtils
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object GameMacroStore {
    private const val KEY = "game_macros_v1"
    private const val MAX_MACROS = 30
    private const val MAX_GESTURES = 300

    @Synchronized fun list(): List<GameMacro> {
        val raw = KVUtils.getString(KEY, "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { fromJson(array.optJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    @Synchronized fun find(name: String): GameMacro? =
        list().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) || it.id == name }

    @Synchronized fun save(name: String, packageName: String, gestures: List<GameGesture>): GameMacro {
        require(name.isNotBlank()) { "Macro name cannot be blank" }
        require(gestures.isNotEmpty()) { "Macro must contain gestures" }
        require(gestures.size <= MAX_GESTURES) { "Macro exceeds $MAX_GESTURES gestures" }
        val macros = list().filterNot { it.name.equals(name.trim(), ignoreCase = true) }.toMutableList()
        val macro = GameMacro(
            id = UUID.randomUUID().toString(),
            name = name.trim().take(60),
            packageName = packageName,
            createdAtMs = System.currentTimeMillis(),
            gestures = gestures,
        )
        macros += macro
        persist(macros.takeLast(MAX_MACROS))
        return macro
    }

    @Synchronized fun delete(name: String): Boolean {
        val before = list()
        val after = before.filterNot { it.name.equals(name.trim(), ignoreCase = true) || it.id == name }
        if (after.size == before.size) return false
        persist(after)
        return true
    }

    private fun persist(macros: List<GameMacro>) {
        val array = JSONArray()
        macros.forEach { array.put(toJson(it)) }
        KVUtils.putString(KEY, array.toString())
        KVUtils.sync()
    }

    private fun toJson(macro: GameMacro) = JSONObject().apply {
        put("id", macro.id)
        put("name", macro.name)
        put("package", macro.packageName)
        put("created", macro.createdAtMs)
        put("gestures", JSONArray().apply {
            macro.gestures.forEach { gesture ->
                put(JSONObject().apply {
                    put("delay", gesture.delayBeforeMs)
                    when (gesture) {
                        is GameGesture.Tap -> {
                            put("type", "tap"); put("x", gesture.x); put("y", gesture.y)
                        }
                        is GameGesture.Swipe -> {
                            put("type", "swipe"); put("x", gesture.startX); put("y", gesture.startY)
                            put("endX", gesture.endX); put("endY", gesture.endY); put("duration", gesture.durationMs)
                        }
                    }
                })
            }
        })
    }

    private fun fromJson(o: JSONObject?): GameMacro? {
        o ?: return null
        val gesturesJson = o.optJSONArray("gestures") ?: return null
        val gestures = (0 until gesturesJson.length()).mapNotNull { index ->
            val g = gesturesJson.optJSONObject(index) ?: return@mapNotNull null
            when (g.optString("type")) {
                "tap" -> GameGesture.Tap(g.optInt("x"), g.optInt("y"), g.optLong("delay"))
                "swipe" -> GameGesture.Swipe(
                    g.optInt("x"), g.optInt("y"), g.optInt("endX"), g.optInt("endY"),
                    g.optLong("duration"), g.optLong("delay"),
                )
                else -> null
            }
        }
        if (gestures.isEmpty()) return null
        return GameMacro(o.optString("id"), o.optString("name"), o.optString("package"),
            o.optLong("created"), gestures)
    }
}
