package com.blackclaw.android.tool.impl

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Pure-local "summarizer" — keeps the longest sentences as a heuristic excerpt.
 * Useful for the local LLM when input is too long to fit in the context window.
 *
 * NOT a real summarization. The agent should usually do summarization itself.
 * This is a fallback when the input >> the model's context.
 */
class SummarizeTextTool : BaseTool() {
    override fun getName() = "summarize_extract"
    override fun getDisplayName() = "Resumen extractivo"
    override fun getDescriptionEN() =
        "Heuristic extractive summary — keeps the N longest sentences. " +
        "Use only when input doesn't fit in context. The agent's own summary is better."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("text", "string", "Texto a resumir.", true),
        ToolParameter("sentences", "integer", "Cuántas frases conservar (default 3).", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val text = requireString(params, "text").trim()
        if (text.isEmpty()) return ToolResult.error("text vacío")
        val n = optionalInt(params, "sentences", 3).coerceIn(1, 20)
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.length > 20 }
        if (sentences.isEmpty()) return ToolResult.success(text.take(400))
        val ranked = sentences.mapIndexed { i, s -> i to s }
            .sortedByDescending { it.second.length }
            .take(n)
            .sortedBy { it.first }
            .map { it.second }
        return ToolResult.success(ranked.joinToString(" "))
    }
}
