package com.blackclaw.android

/**
 * Typed event emitted by TaskOrchestrator during task execution.
 * Replaces the previous string-based callback protocol that required
 * fragile prefix parsing ("Task completed:", "Task failed", etc.).
 *
 * ComposeChatActivity (or any future UI) pattern-matches on these
 * to update the UI — no string parsing, no ambiguity.
 */
sealed class TaskEvent {

    /** LLM responded with text (chat answer or task summary). */
    data class Response(val text: String, val modelName: String? = null) : TaskEvent()

    /** A tool is being executed (e.g. "Send Message", "Open App"). */
    data class ToolAction(val toolName: String) : TaskEvent()

    /** Tool execution result. */
    data class ToolResult(val toolName: String, val success: Boolean, val detail: String) : TaskEvent()

    /**
     * Structured results a surface can draw as cards.
     *
     * Separate from [ToolResult] because that one is a status line: its detail is
     * truncated to 300 characters and its tool name is replaced with a localised display
     * name, both of which are right for a progress row and fatal for a payload.
     *
     * @param payload an [com.blackclaw.android.cards.AssistCardCodec] payload. A surface
     *   that does not draw cards simply ignores this event.
     */
    data class ToolCards(val payload: String) : TaskEvent()

    /** Agent loop started a new round. */
    data class LoopStart(val round: Int) : TaskEvent()

    /** Skill/workflow step progress. */
    data class Progress(val step: Int, val description: String) : TaskEvent()

    /** Token usage update. */
    data class TokenUpdate(
        val step: Int,
        val formattedTokens: String,
        val formattedCost: String,
        val tokenState: com.blackclaw.android.agent.TokenMonitor.State
    ) : TaskEvent()

    /** Task completed successfully with an answer/summary. */
    data class Completed(val answer: String, val modelName: String? = null) : TaskEvent()

    /** Task failed with an error. */
    data class Failed(val error: String) : TaskEvent()

    /** Task was cancelled by user. */
    object Cancelled : TaskEvent()

    /** Task blocked by system dialog. */
    object Blocked : TaskEvent()

    /** Thinking/content stream from LLM (non-streaming mode). */
    data class Thinking(val content: String) : TaskEvent()
}
