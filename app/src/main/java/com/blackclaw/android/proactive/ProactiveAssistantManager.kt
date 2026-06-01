package com.blackclaw.android.proactive

import com.blackclaw.android.agent.llm.LlmSessionManager
import com.blackclaw.android.tool.ToolRegistry
import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The brains of the Proactive Assistant.
 *
 * Flow per notification (only when [ProactiveConfig.enabled]):
 *   1. De-dupe / debounce so we don't re-process the same notification.
 *   2. Ask the LLM ONCE (cheap, no agent loop) to classify the notification
 *      against the user's instructions and decide an action as strict JSON.
 *   3. Execute the decided action by calling existing tools directly
 *      (set_alarm, schedule_task, create_calendar_event, kb_add_todo,
 *      system_notify) — gated by the user's allow-toggles.
 *
 * Why a single-shot call and not the full agent loop: this runs on EVERY
 * notification, so it must be cheap and fast. A one-shot classification keeps
 * token use minimal and avoids hammering cloud rate limits. The full agent loop
 * is reserved for when the user explicitly asks for a task.
 */
object ProactiveAssistantManager {

    private const val TAG = "ProactiveAssistant"
    private const val KEY_LAST_LOG = "proactive_last_log"

    private val worker = Executors.newSingleThreadExecutor()

    // Debounce: ignore identical (pkg|title|text) within this window.
    private const val DEDUPE_WINDOW_MS = 30_000L
    private var lastSig: String = ""
    private var lastSigAt: Long = 0L

    /** Entry point from ClawNotificationListener. Non-blocking. */
    fun onNotification(pkg: String, title: String, text: String) {
        if (!ProactiveConfig.enabled) return
        if (!ProactiveConfig.isAppWatched(pkg)) return
        if (title.isBlank() && text.isBlank()) return

        val sig = "$pkg|$title|$text"
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (sig == lastSig && now - lastSigAt < DEDUPE_WINDOW_MS) return
            lastSig = sig
            lastSigAt = now
        }

        worker.submit {
            try {
                process(pkg, title, text)
            } catch (e: Throwable) {
                XLog.w(TAG, "Proactive processing failed: ${e.message}")
            }
        }
    }

    private fun process(pkg: String, title: String, text: String) {
        var t = title
        var x = text

        // Redacted content: optionally deep-read the chat via accessibility.
        if (isRedacted(t, x)) {
            if (ProactiveConfig.deepRead) {
                val deep = tryDeepRead(pkg)
                if (deep != null) { x = deep } else {
                    XLog.d(TAG, "Proactive: content hidden, deep-read empty for $pkg"); return
                }
            } else {
                XLog.d(TAG, "Proactive: content hidden by OS/app for $pkg (deep-read off)")
                return
            }
        }

        val decision = classify(pkg, t, x) ?: return
        val actions = decision.optJSONArray("actions")
        val actionCount = actions?.length() ?: 0
        val firstAction = if (actionCount > 0) actions!!.getJSONObject(0).optString("action", "ignore") else "ignore"
        ProactiveMemory.recordEvent(pkg, t, x, if (actionCount == 0) "ignore" else firstAction)
        if (actionCount == 0) {
            XLog.d(TAG, "Proactive: nothing actionable from $pkg")
            return
        }

        // Gating: rolling rate limit.
        if (!ProactiveMemory.canAct(ProactiveConfig.maxActionsPerHour)) {
            XLog.w(TAG, "Proactive: hourly action limit reached, skipping")
            logAction("⏸️ Límite por hora alcanzado — omití una acción", false)
            return
        }

        // Confidence gating: if the model isn't confident and the user wants to
        // be asked, surface a suggestion instead of acting.
        val confidence = decision.optDouble("confidence", 1.0)
        if (ProactiveConfig.askWhenUnsure && confidence < 0.55) {
            askUser(decision, t, x)
            return
        }

        XLog.i(TAG, "Proactive: $actionCount action(s) for $pkg — ${decision.optString("reason")}")
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val quiet = ProactiveConfig.inQuietHours(hour)
        var executed = 0
        for (i in 0 until actionCount) {
            val a = actions!!.getJSONObject(i)
            val type = a.optString("action", "ignore").lowercase()
            // In quiet hours, suppress purely-noisy notify/alert actions but
            // still create timed items (they fire later, not now).
            if (quiet && (type == "notify" || type == "alert")) {
                XLog.i(TAG, "Proactive: quiet hours, skipping $type")
                continue
            }
            executeAction(pkg, t, x, type, a)
            executed++
        }
        if (executed > 0) ProactiveMemory.recordAction()
    }

    /** Open the source chat and scrape visible text to recover a redacted msg.
     *  Cooldown-guarded so we never open the app repeatedly (which on dual-app
     *  devices pops the OEM "which app?" chooser). */
    private val lastDeepReadAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val DEEP_READ_COOLDOWN_MS = 5 * 60_000L

    private fun tryDeepRead(pkg: String): String? {
        val now = System.currentTimeMillis()
        val last = lastDeepReadAt[pkg] ?: 0L
        if (now - last < DEEP_READ_COOLDOWN_MS) {
            XLog.d(TAG, "Deep-read cooldown active for $pkg, skipping")
            return null
        }
        lastDeepReadAt[pkg] = now
        return runCatching {
            val svc = com.blackclaw.android.service.ClawAccessibilityService
                .getConnectedInstance(2_000L) ?: return null
            svc.openApp(pkg)
            Thread.sleep(1800)
            val tree = svc.getScreenTree() ?: return null
            // Keep it small: the last chunk of on-screen text.
            tree.take(1500)
        }.getOrNull()
    }

    /** Ask the user (suggestion notification) instead of acting autonomously. */
    private fun askUser(decision: JSONObject, title: String, text: String) {
        val label = decision.optString("label").ifBlank { title }
        val reason = decision.optString("reason").ifBlank { text.take(60) }
        ToolRegistry.getInstance().executeTool("assistant_alert", mapOf(
            "title" to "¿Quieres que actúe?",
            "body" to "$label — $reason. Ábreme para confirmarlo.",
        ))
        logAction("❓ Sugerencia (sin certeza) — $label", true)
    }

    private fun isRedacted(title: String, text: String): Boolean {
        val s = (title + " " + text).lowercase()
        val markers = listOf(
            "contenido oculto", "datos confidenciales", "confidential",
            "content hidden", "nuevo mensaje", "new message", "mensajes nuevos",
            "messages", "te ha enviado un mensaje", "sent you a message",
        )
        return text.length < 40 && markers.any { s.contains(it) }
    }

    /**
     * One-shot LLM classification. Returns a JSON object with at least:
     *   action: ignore | alarm | reminder | note | calendar | notify
     *   reason: short string
     * plus action-specific fields. Returns null on failure (fail safe = do
     * nothing rather than act wrongly).
     */
    private fun classify(pkg: String, title: String, text: String): JSONObject? {
        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm (EEE)", Locale.getDefault())
            .format(Date())

        val allowed = buildList {
            if (ProactiveConfig.allowAlarms) add("alarm")
            if (ProactiveConfig.allowReminders) add("reminder")
            if (ProactiveConfig.allowNotes) add("note")
            if (ProactiveConfig.allowCalendar) add("calendar")
            if (ProactiveConfig.allowFinance) add("finance")
            add("notify")
            add("ignore")
        }

        val prompt = buildString {
            appendLine("You are the proactive assistant inside BlackClaw on the user's phone.")
            appendLine("A new notification arrived. Decide if it needs time-sensitive action(s).")
            appendLine()
            appendLine("Current date/time: $nowStr")
            appendLine()
            appendLine("## User's instructions")
            appendLine(ProactiveConfig.instructions.trim())
            appendLine()
            ProactiveMemory.preferencesSnippet().takeIf { it.isNotBlank() }?.let { appendLine(it); appendLine() }
            ProactiveMemory.recentSnippet().takeIf { it.isNotBlank() }?.let { appendLine(it); appendLine() }
            // Cross-check: what's already in the hub so we don't duplicate.
            existingHubSnippet().takeIf { it.isNotBlank() }?.let { appendLine(it); appendLine() }
            appendLine("## New notification")
            appendLine("App package: $pkg")
            appendLine("Title: $title")
            appendLine("Text: $text")
            appendLine()
            appendLine("## Allowed action types: ${allowed.joinToString(", ")}")
            appendLine("- alarm: a clock alarm (must be awake/somewhere at a time).")
            appendLine("- reminder: a scheduled reminder notification at a future time.")
            appendLine("- note: save a short note/todo.")
            appendLine("- calendar: create a calendar event.")
            appendLine("- finance: record a payment/charge/income (amount, negative=expense).")
            appendLine("- notify: surface a heads-up to the user now.")
            appendLine("- ignore: do nothing (promotions, spam, social, casual chat).")
            appendLine()
            appendLine("IMPORTANT:")
            appendLine("- A single notification may justify SEVERAL actions. Example: a 6am flight →")
            appendLine("  alarm 04:00 + calendar event + note 'bring passport'. Return all of them.")
            appendLine("- Do NOT duplicate something already in the hub above (check times/titles).")
            appendLine("- If a recent event relates (a time changed), prefer ignore or a single note;")
            appendLine("  do not stack near-duplicate reminders.")
            appendLine()
            appendLine("## Respond with ONE strict JSON object, no prose, no markdown:")
            appendLine("{")
            appendLine("  \"confidence\": 0.0-1.0,   // how sure you are this needs action")
            appendLine("  \"reason\": \"<one short sentence>\",")
            appendLine("  \"actions\": [             // empty array = ignore")
            appendLine("    {")
            appendLine("      \"action\": \"alarm|reminder|note|calendar|finance|notify\",")
            appendLine("      \"datetime\": \"YYYY-MM-DD HH:MM\",  // for alarm/reminder/calendar")
            appendLine("      \"label\": \"<short title>\",")
            appendLine("      \"message\": \"<text for note/notify>\",")
            appendLine("      \"amount\": 0, \"category\": \"\"   // finance only")
            appendLine("    }")
            appendLine("  ]")
            appendLine("}")
            appendLine("If nothing is justified, return {\"confidence\":0,\"reason\":\"...\",\"actions\":[]}.")
        }

        val raw = LlmSessionManager.singleShot(prompt, 0.2) ?: run {
            XLog.w(TAG, "Proactive classify: LLM returned null")
            return null
        }
        return parseJson(raw)
    }

    /** Compact view of upcoming hub items so the model avoids duplicates. */
    private fun existingHubSnippet(): String {
        val now = System.currentTimeMillis()
        val upcoming = com.blackclaw.android.assistant.AssistantStore.all()
            .filter { (it.triggerAtMs > now || it.triggerAtMs == 0L) && !it.done }
            .sortedBy { it.triggerAtMs }
            .take(10)
        if (upcoming.isEmpty()) return ""
        val sb = StringBuilder("## Already in the assistant hub (do NOT duplicate)\n")
        upcoming.forEach {
            sb.append("- ${it.type.name.lowercase()}: ${it.title}")
            if (it.triggerAtMs > 0)
                sb.append(" @ ${com.blackclaw.android.assistant.AssistantTime.format(it.triggerAtMs)}")
            sb.append('\n')
        }
        return sb.toString().trim()
    }

    private fun parseJson(raw: String): JSONObject? {
        // The model may wrap JSON in ```json fences or add stray text.
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) {
            XLog.w(TAG, "Proactive: no JSON in LLM output: ${raw.take(120)}")
            return null
        }
        return try {
            JSONObject(raw.substring(start, end + 1))
        } catch (e: Exception) {
            XLog.w(TAG, "Proactive: bad JSON: ${e.message}")
            null
        }
    }

    private fun executeAction(
        pkg: String, title: String, text: String,
        action: String, decision: JSONObject,
    ) {
        val registry = ToolRegistry.getInstance()
        val label = decision.optString("label").ifBlank { title.ifBlank { "BlackClaw" } }
        val message = decision.optString("message").ifBlank { text }
        val datetime = decision.optString("datetime").ifBlank { null }

        when (action) {
            "alarm" -> {
                if (!ProactiveConfig.allowAlarms) return
                if (datetime == null) { XLog.w(TAG, "Proactive alarm: no datetime"); return }
                val r = registry.executeTool("assistant_alarm", mapOf(
                    "when" to datetime, "label" to label,
                ))
                logAction("⏰ Alarma — $label ($datetime)", r.isSuccess)
            }
            "reminder" -> {
                if (!ProactiveConfig.allowReminders) return
                if (datetime == null) { XLog.w(TAG, "Proactive reminder: no datetime"); return }
                val r = registry.executeTool("assistant_reminder", mapOf(
                    "title" to (label.ifBlank { message }),
                    "when" to datetime,
                    "body" to message,
                ))
                logAction("🔔 Recordatorio $datetime — $message", r.isSuccess)
            }
            "calendar" -> {
                if (!ProactiveConfig.allowCalendar) return
                if (datetime == null) { XLog.w(TAG, "Proactive calendar: no datetime"); return }
                val r = registry.executeTool("assistant_event", mapOf(
                    "title" to label, "start" to datetime,
                ))
                logAction("📅 Evento $datetime — $label", r.isSuccess)
            }
            "note" -> {
                if (!ProactiveConfig.allowNotes) return
                val r = registry.executeTool("assistant_note", mapOf(
                    "title" to label, "body" to message,
                ))
                logAction("📝 Nota guardada — ${label.ifBlank { message }}", r.isSuccess)
            }
            "finance" -> {
                if (!ProactiveConfig.allowFinance) return
                val amt = decision.optDouble("amount", 0.0)
                if (amt == 0.0) { XLog.w(TAG, "Proactive finance: no amount"); return }
                val r = registry.executeTool("assistant_finance", mapOf(
                    "description" to (label.ifBlank { message }),
                    "amount" to amt,
                    "category" to decision.optString("category", ""),
                ))
                logAction("💰 Finanza — ${label.ifBlank { message }} ($amt)", r.isSuccess)
            }
            "notify", "alert" -> {
                val r = registry.executeTool("assistant_alert", mapOf(
                    "title" to label, "body" to message,
                ))
                logAction("📢 Aviso — $label: $message", r.isSuccess)
            }
            else -> XLog.d(TAG, "Proactive: unknown action '$action'")
        }
    }

    private fun logAction(line: String, ok: Boolean) {
        val ts = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        val entry = "${if (ok) "✓" else "✗"} $ts  $line"
        XLog.i(TAG, "Proactive action: $entry")
        // Keep a short rolling log for the settings screen (last ~20 lines).
        val prev = KVUtils.getString(KEY_LAST_LOG, "")
        val merged = (listOf(entry) + prev.split("\n")).filter { it.isNotBlank() }.take(20)
        KVUtils.putString(KEY_LAST_LOG, merged.joinToString("\n"))
        KVUtils.sync()
    }

    /** Recent action log for the UI. */
    fun recentLog(): List<String> =
        KVUtils.getString(KEY_LAST_LOG, "").split("\n").filter { it.isNotBlank() }

    fun clearLog() {
        KVUtils.putString(KEY_LAST_LOG, "")
        KVUtils.sync()
    }
}
