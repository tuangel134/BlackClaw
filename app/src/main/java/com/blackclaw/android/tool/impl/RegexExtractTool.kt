package com.blackclaw.android.tool.impl

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/** Extract all matches of a regex from text. Useful for parsing emails/URLs/phone numbers. */
class RegexExtractTool : BaseTool() {
    override fun getName() = "regex_extract"
    override fun getDisplayName() = "Regex"
    override fun getDescriptionEN() =
        "Extrae todas las coincidencias de una regex en un texto. " +
        "Atajos predefinidos disponibles para 'pattern': email, url, phone, ipv4, hashtag, mention."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("text", "string", "Texto donde buscar.", true),
        ToolParameter("pattern", "string",
            "Regex Java estándar, o un atajo: email|url|phone|ipv4|hashtag|mention.", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val text = requireString(params, "text")
        val patternRaw = requireString(params, "pattern").trim()
        val pattern = SHORTCUTS[patternRaw.lowercase()] ?: patternRaw
        return try {
            val regex = Regex(pattern)
            val matches = regex.findAll(text).map { it.value }.toList().distinct()
            if (matches.isEmpty()) ToolResult.success("Sin coincidencias.")
            else ToolResult.success("${matches.size} coincidencia(s):\n" + matches.joinToString("\n") { "- $it" })
        } catch (e: Exception) {
            ToolResult.error("Regex inválida: ${e.message}")
        }
    }

    private val SHORTCUTS = mapOf(
        "email" to """[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""",
        "url" to """https?://[^\s)]+""",
        "phone" to """\+?\d[\d\s\-().]{6,}\d""",
        "ipv4" to """\b(?:\d{1,3}\.){3}\d{1,3}\b""",
        "hashtag" to """#\w+""",
        "mention" to """@\w+""",
    )
}
