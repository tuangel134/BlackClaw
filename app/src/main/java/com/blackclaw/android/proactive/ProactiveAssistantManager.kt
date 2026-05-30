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
        // Some OEMs / apps redact notification content ("Contenido oculto",
        // "Datos confidenciales ocultos", "1 mensaje nuevo"). We can't classify
        // what we can't read — skip quietly but leave a breadcrumb in the log so
        // the user understands why nothing happened.
        if (isRedacted(title, text)) {
            XLog.d(TAG, "Proactive: notification content hidden by OS/app for $pkg")
            return
        }
        val decision = classify(pkg, title, text) ?: return
        val action = decision.optString("action", "ignore").lowercase()
        if (action == "ignore") {
            XLog.d(TAG, "Proactive: ignored notification from $pkg")
            return
        }
        XLog.i(TAG, "Proactive decision for $pkg: $action — ${decision.optString("reason")}")
        executeDecision(pkg, title, text, action, decision)
    }

    private fun isRedacted(title: String, text: String): Boolean {
        val s = (title + " " + text).lowercase()
        val markers = listOf(
            "contenido oculto", "datos confidenciales", "confidential",
            "content hidden", "nuevo mensaje", "new message", "mensajes nuevos",
            "messages", "te ha enviado un mensaje", "sent you a message",
        )
        // Treat as redacted only when the text is essentially just a marker
        // (very short and matching), not when it merely contains the word.
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
            appendLine("A new notification arrived. Decide if it needs a time-sensitive action.")
            appendLine()
            appendLine("Current date/time: $nowStr")
            appendLine()
            appendLine("## User's instructions")
            appendLine(ProactiveConfig.instructions.trim())
            appendLine()
            appendLine("## Notification")
            appendLine("App package: $pkg")
            appendLine("Title: $title")
            appendLine("Text: $text")
            appendLine()
            appendLine("## Allowed actions: ${allowed.joinToString(", ")}")
            appendLine("- alarm: a clock alarm (the user must be awake/somewhere at a time).")
            appendLine("- reminder: a scheduled reminder notification at a future time.")
            appendLine("- note: save a short note/todo for later.")
            appendLine("- calendar: create a calendar event.")
            appendLine("- finance: record a payment/charge/income (include amount, negative=expense).")
            appendLine("- notify: just surface a heads-up to the user now (important info).")
            appendLine("- ignore: do nothing (promotions, spam, social, casual chat).")
            appendLine()
            appendLine("## Respond with ONE strict JSON object, no prose, no markdown:")
            appendLine("{")
            appendLine("  \"action\": \"alarm|reminder|note|calendar|finance|notify|ignore\",")
            appendLine("  \"reason\": \"<one short sentence>\",")
            appendLine("  \"datetime\": \"YYYY-MM-DD HH:MM\",  // for alarm/reminder/calendar; omit otherwise")
            appendLine("  \"label\": \"<short label/title>\",")
            appendLine("  \"message\": \"<text for note/notify>\",")
            appendLine("  \"amount\": 0,        // for finance: negative=expense, positive=income")
            appendLine("  \"category\": \"\"     // for finance: optional category")
            appendLine("}")
            appendLine("If unsure, use \"ignore\". Only act when clearly justified by the instructions.")
        }

        val raw = LlmSessionManager.singleShot(prompt, 0.2) ?: run {
            XLog.w(TAG, "Proactive classify: LLM returned null")
            return null
        }
        return parseJson(raw)
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

    private fun executeDecision(
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

    private fun notifyUser(title: String, body: String) {
        runCatching {
            ToolRegistry.getInstance().executeTool("assistant_alert", mapOf(
                "title" to title, "body" to body,
            ))
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
