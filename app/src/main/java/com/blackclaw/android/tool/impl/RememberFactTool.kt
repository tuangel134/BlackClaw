package com.blackclaw.android.tool.impl

import com.blackclaw.android.memory.UserMemoryStore
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Save a small fact for long-term recall.
 *
 * Use sparingly — the memory snippet is injected into every system prompt,
 * so storing too many facts wastes tokens. Good fits: user's name, home
 * city, work email domain, regular contacts, preferred apps, time zone.
 */
class RememberFactTool : BaseTool() {
    override fun getName() = "remember_fact"
    override fun getDisplayName() = "Remember"
    override fun getDescriptionEN() =
        "Save a small fact about the user for long-term recall (e.g. name='Alex', " +
        "home_city='Madrid', preferred_browser='Firefox'). " +
        "These facts are auto-injected into every future conversation. " +
        "Reuse the same 'key' to overwrite an existing fact. Keep keys short and stable."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("key", "string", "Short stable identifier (e.g. 'name', 'home_city').", true),
        ToolParameter("value", "string", "The value to remember.", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val key = requireString(params, "key").trim()
        val value = requireString(params, "value").trim()
        if (key.isEmpty()) return ToolResult.error("key cannot be empty")
        if (value.isEmpty()) return ToolResult.error("value cannot be empty")
        if (key.length > 64) return ToolResult.error("key too long (max 64 chars)")
        if (value.length > 512) return ToolResult.error("value too long (max 512 chars)")
        val fact = UserMemoryStore.remember(key, value)
            ?: return ToolResult.error("Could not store that fact securely. Try again after unlocking the device.")
        return ToolResult.success("Remembered ${fact.key} = ${fact.value}")
    }
}
