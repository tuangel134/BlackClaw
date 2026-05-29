package com.blackclaw.android.tool.impl

import android.content.Intent
import android.net.Uri
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Open a web search for the given query in the default browser / search app.
 *
 * This is an "intent" tool — it does NOT scrape results. Cloud-mode agents already
 * have web reasoning built into the model; local-mode agents can use this to surface
 * a search result page for the user to read.
 */
class WebSearchTool : BaseTool() {
    override fun getName() = "web_search"
    override fun getDisplayName() = "Web Search"
    override fun getDescriptionEN() =
        "Open a web search for the given query in the default browser. " +
        "Engine options: 'google' (default), 'duckduckgo', 'bing'. " +
        "Use only when the user explicitly wants to look something up online."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("query", "string", "Search query.", true),
        ToolParameter("engine", "string", "google | duckduckgo | bing. Default google.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val query = requireString(params, "query").trim()
        if (query.isEmpty()) return ToolResult.error("query cannot be empty")
        val engine = optionalString(params, "engine", "google").lowercase().trim()
        val encoded = Uri.encode(query)
        val url = when (engine) {
            "duckduckgo", "ddg" -> "https://duckduckgo.com/?q=$encoded"
            "bing" -> "https://www.bing.com/search?q=$encoded"
            "google", "" -> "https://www.google.com/search?q=$encoded"
            else -> return ToolResult.error("Unknown engine '$engine'. Use google|duckduckgo|bing")
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ClawApplication.instance.startActivity(intent)
            ToolResult.success("Searching $engine for: $query")
        } catch (e: Exception) {
            ToolResult.error("Failed to open search: ${e.message}")
        }
    }
}
