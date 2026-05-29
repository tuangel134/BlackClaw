package com.blackclaw.android.agent

import com.blackclaw.android.tool.impl.OpenAppTool
import java.util.Locale

/**
 * Narrow execution guard for explicit "search in app" tasks.
 *
 * We only activate this for clear patterns like:
 * - "search [app] for [query]"
 * - "search for [query] on [app]"
 *
 * The guard keeps behavior unchanged for every other task, but prevents the agent
 * from claiming success before it has actually typed the query into the target app.
 */
internal class InAppSearchGuard private constructor(
    private val match: Match?
) {

    data class Match(
        val appName: String,
        val query: String,
        val resolvedPackage: String
    )

    private var openedTargetApp = false
    private var typedQuery = false

    fun buildPromptSection(): String {
        val task = match ?: return ""
        return "\n\n" + """
            ## Task Guard: In-App Search
            This request means: open ${task.appName} and search for "${task.query}" inside that app.
            Required execution steps before completion:
            1. Open the target app with open_app(app_name="${task.appName}") if it is not already open.
            2. Find the app's search field or search icon.
            3. Call input_text(text="${task.query}") to actually type the query.
            4. Submit the search and inspect the visible results with get_screen_info.
            5. Only then call finish(summary="what is visible in the results").
            Never claim the search succeeded from memory alone. If you cannot type the query, explain the blocker instead of finishing.
        """.trimIndent()
    }

    fun shouldBlockTextOnlyCompletion(): Boolean {
        return match != null && !typedQuery
    }

    fun buildCompletionCorrection(): String {
        val task = match ?: return "[System Guard] Continue the task instead of stopping."
        return "[System Guard] This is an in-app search task for ${task.appName}. " +
            "Do not stop yet. You must use input_text(text=\"${task.query}\") to type the search query, " +
            "submit it, and inspect the visible results before completing."
    }

    fun maybeBlockFinish(screenInfo: String? = null): String? {
        val task = match ?: return null
        if (typedQuery) return null
        val openHint = if (openedTargetApp) "" else " Open ${task.appName} first if needed."
        val nodeHint = buildNodeHint(screenInfo, task.query)
        return "[System Guard] Do not call finish yet for this ${task.appName} search task." +
            openHint +
            " You still need to type the query with input_text(text=\"${task.query}\"), " +
            "submit it, and then inspect the results screen." +
            nodeHint
    }

    fun recordSuccessfulTool(toolName: String, params: Map<String, Any>) {
        val task = match ?: return
        when (toolName) {
            "open_app" -> {
                val candidate = params["package_name"]?.toString()
                    ?: params["app_name"]?.toString()
                    ?: return
                val resolved = if (candidate.contains('.')) {
                    candidate
                } else {
                    OpenAppTool.resolveAppNameStatic(candidate)
                }
                if (resolved == task.resolvedPackage) {
                    openedTargetApp = true
                }
            }
            "input_text" -> {
                val text = params["text"]?.toString() ?: return
                val normalizedText = normalize(text)
                val normalizedQuery = normalize(task.query)
                if (normalizedText == normalizedQuery || normalizedText.contains(normalizedQuery)) {
                    typedQuery = true
                }
            }
        }
    }

    companion object {
        private val SEARCH_APP_FOR_QUERY = Regex(
            """^\s*search\s+(.+?)\s+for\s+(.+?)\s*$""",
            RegexOption.IGNORE_CASE
        )
        private val SEARCH_QUERY_ON_APP = Regex(
            """^\s*search\s+for\s+(.+?)\s+(?:on|in)\s+(.+?)\s*$""",
            RegexOption.IGNORE_CASE
        )

        fun fromTask(task: String): InAppSearchGuard {
            val trimmed = task.trim()
            val match = parse(trimmed)
            return InAppSearchGuard(match)
        }

        private fun parse(task: String): Match? {
            val parsed = SEARCH_APP_FOR_QUERY.matchEntire(task)?.let {
                ParsedParts(
                    appName = sanitizeAppName(it.groupValues[1]),
                    query = sanitizeQuery(it.groupValues[2])
                )
            } ?: SEARCH_QUERY_ON_APP.matchEntire(task)?.let {
                ParsedParts(
                    appName = sanitizeAppName(it.groupValues[2]),
                    query = sanitizeQuery(it.groupValues[1])
                )
            } ?: return null

            if (parsed.appName.isBlank() || parsed.query.isBlank()) return null

            val resolvedPackage = OpenAppTool.resolveAppNameStatic(parsed.appName) ?: return null
            return Match(
                appName = parsed.appName,
                query = parsed.query,
                resolvedPackage = resolvedPackage
            )
        }

        private fun sanitizeAppName(raw: String): String {
            return raw.trim()
                .removePrefix("the ")
                .removePrefix("The ")
                .removeSuffix(" app")
                .removeSuffix(" App")
                .trim()
        }

        private fun sanitizeQuery(raw: String): String {
            return raw.trim().removeSurrounding("\"").removeSurrounding("'").trim()
        }

        private fun normalize(value: String): String {
            return value.lowercase(Locale.US)
                .replace(Regex("""\s+"""), " ")
                .trim()
        }

        private fun buildNodeHint(screenInfo: String?, query: String): String {
            if (screenInfo.isNullOrBlank()) return ""
            val searchEditNode = screenInfo.lineSequence()
                .map { it.trim() }
                .firstOrNull { line ->
                    line.contains("search", ignoreCase = true) && line.contains(" edit")
                }
                ?.let { extractNodeId(it) }
            if (searchEditNode != null) {
                return " Current screen shows a search field at node_id=\"$searchEditNode\". " +
                    "Use input_text(text=\"$query\", node_id=\"$searchEditNode\") next."
            }

            val anyEditNode = screenInfo.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.contains(" edit") }
                ?.let { extractNodeId(it) }
            if (anyEditNode != null) {
                return " Current screen has an editable field at node_id=\"$anyEditNode\". " +
                    "Use input_text(text=\"$query\", node_id=\"$anyEditNode\") next."
            }
            return ""
        }

        private fun extractNodeId(line: String): String? {
            return Regex("""\[(n\d+)]""").find(line)?.groupValues?.getOrNull(1)
        }

        private data class ParsedParts(
            val appName: String,
            val query: String
        )
    }
}
