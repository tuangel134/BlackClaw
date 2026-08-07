package com.blackclaw.android.conversation

import com.blackclaw.android.utils.KVUtils
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Shared, bounded conversation timeline across local assistant surfaces. */
object ConversationRepository {
    private const val KEY = "conversation_engine_turns_v2"
    private const val KEY_REMOTE_BRIDGE = "conversation_engine_remote_bridge"
    private const val MAX_TURNS = 240

    enum class Surface { CHAT, QUICK_ASSIST, VOICE, ANDROID_AUTO, AUTOMATION, REMOTE, TASK }
    enum class Trust { LOCAL, REMOTE_ISOLATED }
    enum class Role { USER, ASSISTANT, SYSTEM }

    data class Turn(
        val id: String,
        val threadId: String,
        val surface: Surface,
        val trust: Trust,
        val role: Role,
        val text: String,
        val timestampMs: Long,
        val route: String = "",
    ) {
        fun toJson() = JSONObject().apply {
            put("id", id); put("thread", threadId); put("surface", surface.name); put("trust", trust.name)
            put("role", role.name); put("text", text); put("timestamp", timestampMs); put("route", route)
        }
        companion object {
            fun fromJson(o: JSONObject) = Turn(
                o.optString("id"), o.optString("thread"),
                runCatching { Surface.valueOf(o.optString("surface")) }.getOrDefault(Surface.TASK),
                runCatching { Trust.valueOf(o.optString("trust")) }.getOrDefault(Trust.LOCAL),
                runCatching { Role.valueOf(o.optString("role")) }.getOrDefault(Role.USER),
                o.optString("text"), o.optLong("timestamp"), o.optString("route"),
            )
        }
    }

    var remoteBridgeEnabled: Boolean
        get() = KVUtils.getBoolean(KEY_REMOTE_BRIDGE, false)
        set(value) { KVUtils.putBoolean(KEY_REMOTE_BRIDGE, value); KVUtils.sync() }

    @Synchronized fun all(): List<Turn> {
        val raw = KVUtils.getString(KEY, "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { runCatching { Turn.fromJson(a.getJSONObject(it)) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    fun appendLocal(surface: Surface, role: Role, text: String, route: String = "") =
        append("local", surface, Trust.LOCAL, role, text, route)

    fun appendRemote(identity: String, role: Role, text: String, route: String = "") =
        append("remote:${identity.ifBlank { "unknown" }}", Surface.REMOTE, Trust.REMOTE_ISOLATED, role, text, route)

    @Synchronized private fun append(thread: String, surface: Surface, trust: Trust, role: Role,
                                     text: String, route: String) {
        val clean = text.trim().take(2_000)
        if (clean.isBlank()) return
        val list = all().toMutableList()
        val previous = list.lastOrNull()
        if (previous?.threadId == thread && previous.role == role && previous.text == clean) return
        list += Turn(UUID.randomUUID().toString().take(8), thread, surface, trust, role, clean,
            System.currentTimeMillis(), route)
        persist(list.takeLast(MAX_TURNS))
    }

    fun recentLocalLines(maxTurns: Int = 10, maxChars: Int = 1_800): List<String> =
        buildContextLines(all(), Trust.LOCAL, "local", remoteBridgeEnabled, maxTurns, maxChars)

    fun recentRemoteLines(identity: String, maxTurns: Int = 10, maxChars: Int = 1_800): List<String> =
        buildContextLines(all(), Trust.REMOTE_ISOLATED, "remote:$identity", remoteBridgeEnabled, maxTurns, maxChars)

    internal fun buildContextLines(turns: List<Turn>, trust: Trust, threadId: String,
                                   bridgeRemoteToLocal: Boolean, maxTurns: Int, maxChars: Int): List<String> {
        val allowed = turns.filter {
            when (trust) {
                Trust.LOCAL -> it.trust == Trust.LOCAL
                Trust.REMOTE_ISOLATED -> it.threadId == threadId ||
                    (bridgeRemoteToLocal && it.trust == Trust.LOCAL)
            }
        }.takeLast(maxTurns)
        val result = ArrayDeque<String>(); var used = 0
        for (turn in allowed.asReversed()) {
            val prefix = when (turn.role) { Role.USER -> "Usuario"; Role.ASSISTANT -> "BlackClaw"; Role.SYSTEM -> "Sistema" }
            val line = "$prefix [${turn.surface.name.lowercase()}]: ${turn.text.take(500)}"
            if (used + line.length > maxChars) break
            result.addFirst(line); used += line.length
        }
        return result.toList()
    }

    private fun persist(turns: List<Turn>) {
        val a = JSONArray(); turns.forEach { a.put(it.toJson()) }
        KVUtils.putString(KEY, a.toString()); KVUtils.sync()
    }
}
