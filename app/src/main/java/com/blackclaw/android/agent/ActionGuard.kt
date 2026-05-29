package com.blackclaw.android.agent

import com.blackclaw.android.utils.XLog

/**
 * Soft heuristic guard that flags risky tool invocations and lets the
 * agent loop choose to refuse, ask for confirmation, or proceed.
 *
 * This is NOT a security boundary. The accessibility service can still do
 * anything the user can do. The point is to catch *prompt-side* mistakes
 * where the model hallucinates a destructive action the user did not ask for.
 *
 * Heuristics — based on tool name + param keywords. Tunable per-tool.
 */
object ActionGuard {

    private const val TAG = "ActionGuard"

    enum class Risk {
        SAFE,
        ELEVATED,   // worth flagging in the loop's logging, still OK to run
        DESTRUCTIVE // refuse or require user confirmation
    }

    /** Patterns that mark a tool args / target as destructive. */
    private val DESTRUCTIVE_PATTERNS = listOf(
        Regex("(?i)\\bdelete\\s+(everything|all|all my|account|chat history)"),
        Regex("(?i)\\bclear\\s+(all data|app data|cache|history)"),
        Regex("(?i)\\bfactory\\s+reset"),
        Regex("(?i)\\bunsubscribe\\s+all"),
        Regex("(?i)\\bremove\\s+all\\s+contacts"),
        Regex("(?i)\\bbuy\\b|\\bpurchase\\b|\\bcheckout\\b|\\bpay\\b"),
        Regex("(?i)\\btransfer\\s+\\$?\\d"),
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

    fun assess(toolName: String, params: Map<String, Any>): Risk {
        val flat = (listOf(toolName) + params.values.map { it.toString() })
            .joinToString(" ")

        if (DESTRUCTIVE_PATTERNS.any { it.containsMatchIn(flat) }) {
            XLog.w(TAG, "DESTRUCTIVE pattern matched in $toolName($params)")
            return Risk.DESTRUCTIVE
        }

        if (toolName == "forget_fact" &&
            params["key"]?.toString().equals("all", ignoreCase = true)) {
            return Risk.DESTRUCTIVE
        }
        if (toolName == "cancel_scheduled_task" &&
            params["id"]?.toString().equals("all", ignoreCase = true)) {
            return Risk.DESTRUCTIVE
        }

        if (toolName in DESTRUCTIVE_TOOLS) return Risk.ELEVATED
        if (toolName in ELEVATED_TOOLS) return Risk.ELEVATED
        return Risk.SAFE
    }

    /** Build a short rationale string the agent loop can include in logs / surfaces. */
    fun describe(risk: Risk, toolName: String): String = when (risk) {
        Risk.SAFE -> ""
        Risk.ELEVATED -> "[note] $toolName is user-visible — be sure the request asked for it."
        Risk.DESTRUCTIVE -> "[blocked] $toolName looks destructive. Refuse or ask the user to confirm explicitly."
    }
}
