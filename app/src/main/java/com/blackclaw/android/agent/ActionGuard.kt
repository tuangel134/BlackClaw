package com.blackclaw.android.agent

import com.blackclaw.android.utils.XLog

/**
 * Soft heuristic guard that flags risky tool invocations and lets the
 * agent loop choose to refuse, ask for confirmation, or proceed.
 *
 * This is NOT a security boundary. The accessibility service can still do
 * anything the user can do. The point is to catch *prompt-side* mistakes
 * where the model hallucinates a destructive action the user did not ask for,
 * or where an untrusted source (a notification the proactive assistant read)
 * tries to steer the agent into doing something dangerous.
 *
 * Heuristics — based on tool name + param keywords, in EN/ES/中文. Tunable.
 */
object ActionGuard {

    private const val TAG = "ActionGuard"

    enum class Risk {
        SAFE,
        ELEVATED,   // worth flagging in the loop's logging, still OK to run
        DESTRUCTIVE // refuse or require user confirmation
    }

    /**
     * Patterns that mark a tool's args / target as destructive.
     * Covers English, Spanish (the app's primary UI language) and a few
     * common 中文 phrasings, since the model and notifications can be in any.
     */
    private val DESTRUCTIVE_PATTERNS = listOf(
        // delete everything / all data / account / history
        Regex("(?i)\\bdelete\\s+(everything|all|all my|account|chat history)"),
        Regex("(?i)\\b(borra|borrar|elimina|eliminar)\\s+(todo|todos|toda|todas|mi cuenta|la cuenta|el historial|todo el historial|todos los contactos)"),
        // clear all data / cache / history
        Regex("(?i)\\bclear\\s+(all data|app data|cache|history)"),
        Regex("(?i)\\b(borra|borrar|limpia|limpiar|vaciar?)\\s+(todos los datos|datos de la app|cache|caché|el historial|historial)"),
        // factory reset
        Regex("(?i)\\bfactory\\s+reset"),
        Regex("(?i)\\b(restablecimiento|restablecer|restaurar)\\s+(de\\s+)?fábrica|formatear (el )?(telefono|teléfono|dispositivo)"),
        Regex("(?i)恢复出厂|出厂设置|格式化"),
        // unsubscribe all / mass-remove
        Regex("(?i)\\bunsubscribe\\s+all"),
        Regex("(?i)\\bremove\\s+all\\s+contacts"),
        Regex("(?i)\\b(elimina|borra|quita)\\s+(todos los|todas las)\\s+contactos?"),
        // payments / purchases / transfers
        Regex("(?i)\\b(buy|purchase|checkout|pay)\\b"),
        Regex("(?i)\\b(compra|comprar|pagar|paga|realiza el pago|confirmar (la )?compra|transfiere|transferir|envía dinero|enviar dinero)\\b"),
        Regex("(?i)\\b(transfer|transferir|envía|enviar)\\s+\\$?\\d"),
        Regex("(?i)付款|转账|购买|结账"),
        // uninstall / wipe apps
        Regex("(?i)\\b(uninstall|wipe)\\b"),
        Regex("(?i)\\b(desinstala|desinstalar)\\b"),
    )

    /**
     * Signs that an instruction is being injected by an untrusted source
     * (e.g. a notification body the proactive assistant read). These should
     * never be treated as legitimate user intent.
     */
    private val INJECTION_PATTERNS = listOf(
        Regex("(?i)ignore (all )?(previous|prior|above) (instructions|prompts?)"),
        Regex("(?i)(ignora|olvida) (todas )?(las )?(instrucciones|órdenes) (previas|anteriores)"),
        Regex("(?i)you are now (a|an|in)\\b"),
        Regex("(?i)(ahora eres|actúa como|haz de cuenta que eres)\\b"),
        Regex("(?i)system prompt|developer message|jailbreak"),
        Regex("(?i)disregard (your|the) (rules|guidelines|safety)"),
    )

    private val ELEVATED_TOOLS = setOf(
        "send_message", "send_sms", "make_call",
        "set_brightness", "set_volume",
        "toggle_setting",
    )

    private val DESTRUCTIVE_TOOLS = setOf(
        "forget_fact",      // when key='all'
        "cancel_scheduled_task",
    )

    /**
     * Tools whose params are free-form user/notification content rather than
     * agent intent. For these we DON'T run the destructive text patterns over
     * the body (the user is allowed to *write* "pay me" in a message); we only
     * scan the injection patterns to flag manipulation.
     */
    private val CONTENT_BEARING_TOOLS = setOf(
        "send_message", "send_sms", "assistant_draft_reply",
        "assistant_note", "kb_write", "remember_fact",
    )

    fun assess(toolName: String, params: Map<String, Any>): Risk {
        // Exact destructive tool+param combos first (most specific).
        if (toolName == "forget_fact" &&
            params["key"]?.toString().equals("all", ignoreCase = true)) {
            return Risk.DESTRUCTIVE
        }
        if (toolName == "cancel_scheduled_task" &&
            params["id"]?.toString().equals("all", ignoreCase = true)) {
            return Risk.DESTRUCTIVE
        }

        // For content-bearing tools, the message body is legitimately free text.
        // Scanning it for "pay"/"buy" would block normal messages, so we only
        // scan the tool name + non-content params there.
        val isContentTool = toolName in CONTENT_BEARING_TOOLS
        val scanned = if (isContentTool) {
            (listOf(toolName) + params.filterKeys { it !in CONTENT_KEYS }.values.map { it.toString() })
                .joinToString(" ")
        } else {
            (listOf(toolName) + params.values.map { it.toString() }).joinToString(" ")
        }

        if (DESTRUCTIVE_PATTERNS.any { it.containsMatchIn(scanned) }) {
            XLog.w(TAG, "DESTRUCTIVE pattern matched in $toolName")
            return Risk.DESTRUCTIVE
        }

        if (toolName in DESTRUCTIVE_TOOLS) return Risk.ELEVATED
        if (toolName in ELEVATED_TOOLS) return Risk.ELEVATED
        return Risk.SAFE
    }

    /** Param keys whose values are free-form content (don't scan for destructive verbs). */
    private val CONTENT_KEYS = setOf("message", "body", "text", "draft", "content", "value", "note")

    /**
     * Detect prompt-injection signs in untrusted text (e.g. a notification body
     * before the proactive assistant acts on it). Returns true if the text looks
     * like it's trying to hijack the agent. Callers should treat the source as
     * data, not instructions.
     */
    fun looksLikeInjection(untrustedText: String?): Boolean {
        if (untrustedText.isNullOrBlank()) return false
        val hit = INJECTION_PATTERNS.any { it.containsMatchIn(untrustedText) }
        if (hit) XLog.w(TAG, "Possible prompt-injection in untrusted text")
        return hit
    }

    /** Build a short rationale string the agent loop can include in logs / surfaces. */
    fun describe(risk: Risk, toolName: String): String = when (risk) {
        Risk.SAFE -> ""
        Risk.ELEVATED -> "[note] $toolName is user-visible — be sure the request asked for it."
        Risk.DESTRUCTIVE -> "[blocked] $toolName looks destructive. Refuse or ask the user to confirm explicitly."
    }
}
