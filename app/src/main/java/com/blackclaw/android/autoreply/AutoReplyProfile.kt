package com.blackclaw.android.autoreply

import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.SecretStore
import com.blackclaw.android.utils.XLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * User-defined auto-reply profile for a single contact / channel.
 *
 *  contactName  → matches sender (substring, case-insensitive)
 *  app          → "WhatsApp" | "Telegram" — the messaging app to monitor
 *  personality  → free-form text describing how to reply (style, tone, length)
 *  conversationContext → optional pasted history so the LLM can mimic the user's voice
 *  enabled      → false = profile saved but cron not active
 *  cronEnabled  → if true, a periodic ScheduledTask checks the inbox even when
 *                 BlackClaw isn't open (uses AlarmManager via ScheduledTaskManager)
 *  cronIntervalMinutes → minutes between cron runs when cronEnabled = true
 */
data class AutoReplyProfile(
    val id: String,
    val contactName: String,
    val app: String,
    val personality: String,
    val conversationContext: String,
    val enabled: Boolean,
    val cronEnabled: Boolean,
    val cronIntervalMinutes: Int,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("contactName", contactName)
        put("app", app)
        put("personality", personality)
        put("conversationContext", conversationContext)
        put("enabled", enabled)
        put("cronEnabled", cronEnabled)
        put("cronIntervalMinutes", cronIntervalMinutes)
        put("createdAtMs", createdAtMs)
        put("updatedAtMs", updatedAtMs)
    }

    /** Compose the prompt the agent will receive when this profile fires. */
    fun composeAgentPrompt(): String = buildString {
        appendLine("Eres BlackClaw actuando como $app auto-reply para el contacto \"$contactName\".")
        appendLine()
        if (personality.isNotBlank()) {
            appendLine("## Personalidad / estilo de respuesta")
            appendLine(personality.trim())
            appendLine()
        }
        if (conversationContext.isNotBlank()) {
            appendLine("## Historial de cómo responde el usuario (imítalo)")
            appendLine(conversationContext.trim().take(8000))
            appendLine()
        }
        appendLine("## Instrucciones de ejecución")
        appendLine("1. Abre $app y revisa los mensajes recientes de \"$contactName\".")
        appendLine("2. Si hay un mensaje sin responder, escribe una respuesta siguiendo la personalidad y el historial.")
        appendLine("3. Si el contexto sugiere que el usuario está ocupado, responde brevemente excusándote.")
        appendLine("4. NO respondas a mensajes que no sean del contacto especificado.")
        appendLine("5. Después de responder, llama finish() con un resumen.")
    }

    companion object {
        fun fromJson(o: JSONObject) = AutoReplyProfile(
            id = o.optString("id", UUID.randomUUID().toString().take(8)),
            contactName = o.getString("contactName"),
            app = o.optString("app", "WhatsApp"),
            personality = o.optString("personality", ""),
            conversationContext = o.optString("conversationContext", ""),
            enabled = o.optBoolean("enabled", false),
            cronEnabled = o.optBoolean("cronEnabled", false),
            cronIntervalMinutes = o.optInt("cronIntervalMinutes", 15),
            createdAtMs = o.optLong("createdAtMs", System.currentTimeMillis()),
            updatedAtMs = o.optLong("updatedAtMs", System.currentTimeMillis()),
        )

        fun blank() = AutoReplyProfile(
            id = UUID.randomUUID().toString().take(8),
            contactName = "", app = "WhatsApp",
            personality = "Responde de forma natural y breve, como lo haría yo.",
            conversationContext = "",
            enabled = true, cronEnabled = false, cronIntervalMinutes = 15,
            createdAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
        )
    }
}

object AutoReplyProfileStore {
    private const val TAG = "AutoReplyProfileStore"
    private const val KEY = "KEY_AUTO_REPLY_PROFILES_V1"

    @Synchronized
    fun all(): List<AutoReplyProfile> {
        val raw = readEncryptedOrMigrateLegacy() ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { AutoReplyProfile.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun find(id: String): AutoReplyProfile? = all().firstOrNull { it.id == id }

    @Synchronized
    private fun saveAll(profiles: List<AutoReplyProfile>): Boolean {
        val arr = JSONArray()
        profiles.forEach { arr.put(it.toJson()) }
        if (!SecretStore.putString(KEY, arr.toString())) return false
        KVUtils.remove(KEY)
        KVUtils.sync()
        return true
    }

    /**
     * Profiles may contain an entire imported conversation, contact identity and
     * personal writing instructions. Migrate the document as one encrypted unit so
     * future fields cannot accidentally remain in plaintext.
     */
    private fun readEncryptedOrMigrateLegacy(): String? {
        if (SecretStore.contains(KEY)) return SecretStore.getString(KEY)

        val legacy = KVUtils.getString(KEY, "")
        if (legacy.isBlank()) return ""
        val migrated = SecretStore.putString(KEY, legacy) && SecretStore.getString(KEY) == legacy
        if (migrated) {
            KVUtils.remove(KEY)
            KVUtils.sync()
            XLog.i(TAG, "Migrated auto-reply profiles to encrypted storage")
        } else {
            XLog.w(TAG, "Auto-reply profile migration deferred; legacy data retained")
        }
        return legacy
    }

    @Synchronized
    fun upsert(profile: AutoReplyProfile): AutoReplyProfile? {
        val now = System.currentTimeMillis()
        val current = all().toMutableList()
        val idx = current.indexOfFirst { it.id == profile.id }
        val final = if (idx >= 0) profile.copy(updatedAtMs = now) else profile
        if (idx >= 0) current[idx] = final else current.add(final)
        if (!saveAll(current)) {
            XLog.e(TAG, "Could not persist auto-reply profile securely")
            return null
        }
        return final
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val before = all()
        val after = before.filterNot { it.id == id }
        if (after.size == before.size) return false
        return saveAll(after).also { saved ->
            if (!saved) XLog.e(TAG, "Could not persist auto-reply profile deletion")
        }
    }

    @Synchronized
    fun toggleEnabled(id: String): AutoReplyProfile? {
        val p = find(id) ?: return null
        return upsert(p.copy(enabled = !p.enabled, updatedAtMs = System.currentTimeMillis()))
    }
}
