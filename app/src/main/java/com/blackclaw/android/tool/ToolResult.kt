package com.blackclaw.android.tool

/**
 * What a tool hands back.
 *
 * ## The card payload, and why it is transient
 *
 * [data] is written for the model to read, so it is prose. Prose is a lossy form for the
 * UI: the tool had temperatures, coordinates, prices and URLs and flattened them into a
 * sentence, and the only way to draw a real card from that was to match substrings in it.
 * [cards] carries the same facts in structured form for the UI to draw.
 *
 * It is `@Transient` on purpose. The agent loop serialises this whole object with Gson and
 * sends it to the model, so a normal field would ship every value twice — once as prose
 * and once as JSON — and the user would pay tokens for the duplicate. Transient fields are
 * skipped by Gson, so the model keeps seeing exactly what it saw before.
 *
 * Adding it as a defaulted parameter keeps the several hundred existing `success(...)` and
 * `error(...)` call sites, including the Java ones, compiling untouched.
 */
class ToolResult private constructor(
    val isSuccess: Boolean,
    val data: String?,
    val error: String?,
    @Transient val cards: String? = null,
) {
    /** True when this result carries something the UI can draw as a card. */
    val hasCards: Boolean get() = !cards.isNullOrBlank()

    companion object {
        @JvmStatic
        fun success(data: String): ToolResult = ToolResult(true, data, null)

        @JvmStatic
        fun error(error: String): ToolResult = ToolResult(false, null, error)

        /**
         * Success with a structured payload for the UI.
         *
         * @param data the prose the model reads — unchanged in shape and content from what
         *   the tool would have returned anyway, so switching a tool to this factory
         *   cannot alter how the agent behaves.
         * @param cards an [com.blackclaw.android.cards.AssistCardCodec] payload, or null.
         */
        @JvmStatic
        fun successWithCards(data: String, cards: String?): ToolResult =
            ToolResult(true, data, null, cards?.takeIf { it.isNotBlank() })
    }

    // Deliberately omits [cards]: this string ends up in logs, and the payload would bury
    // the part a reader is looking for.
    override fun toString(): String = if (isSuccess) {
        "ToolResult{success=true, data='$data'}"
    } else {
        "ToolResult{success=false, error='$error'}"
    }
}
