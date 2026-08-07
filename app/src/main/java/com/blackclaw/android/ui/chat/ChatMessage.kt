package com.blackclaw.android.ui.chat

data class ChatMessage(
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val toolSteps: List<ToolStep>? = null,
    val modelName: String? = null
) {
    /**
     * [CARDS] carries an `AssistCardCodec` payload in [content] instead of prose.
     *
     * A role rather than a field on every message: the role is already what selects the
     * renderer, and adding a nullable field to all four other kinds would mean every
     * reader had to remember it exists. It also keeps the transcript honest — a card row
     * is a separate thing that arrived, not a decoration on the reply.
     */
    enum class Role { USER, ASSISTANT, SYSTEM, TOOL_GROUP, CARDS }

    /**
     * True while this bubble is a placeholder waiting for the model.
     *
     * The pending state is encoded in the content rather than in a flag because the
     * streaming code finds the bubble to write into by searching for it. Wrapping that
     * search in a named property keeps the nine places that used to compare against a
     * bare `"..."` in agreement — including the renderer, which must catch it before
     * markdown parsing so the placeholder is never formatted.
     */
    val isPending: Boolean get() = role == Role.ASSISTANT && content == PENDING

    companion object {
        /**
         * Content of the bubble shown between sending and the first token arriving.
         *
         * Changing this string alone is not enough: it is also what
         * [CloudContextHandoffFormatter] filters out of history, so a placeholder that
         * no longer matches would be sent to the cloud model as if the assistant had
         * literally replied with an ellipsis.
         */
        const val PENDING = "..."
    }
}

data class ToolStep(
    val toolName: String,
    val summary: String,
    val success: Boolean = false
)
