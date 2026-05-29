package com.blackclaw.android.tool.impl

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Count words/chars/lines/tokens for a given text.
 * Useful when the user asks "how long is this", "is this under 280 chars", etc.
 */
class CountTool : BaseTool() {
    override fun getName() = "count_text"
    override fun getDisplayName() = "Contar texto"
    override fun getDescriptionEN() =
        "Count words, characters (with/without spaces), and lines in a string. " +
        "Use for tweet-length checks, summary length, etc."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("text", "string", "Text to measure.", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val t = requireString(params, "text")
        val chars = t.length
        val charsNoSpace = t.count { !it.isWhitespace() }
        val words = t.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        val lines = t.lines().size
        val approxTokens = (chars / 4).coerceAtLeast(1)  // rough heuristic
        return ToolResult.success(
            "Caracteres: $chars (sin espacios: $charsNoSpace) · Palabras: $words · Líneas: $lines · ≈Tokens: $approxTokens"
        )
    }
}
