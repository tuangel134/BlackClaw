package com.blackclaw.android.tool.impl

import com.blackclaw.android.agent.skill.UserSkillStore
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Look up a user-defined skill by id, name, or trigger phrase, and return its
 * full prompt so the agent can fold it into the current task plan. Does NOT
 * execute the skill as a sub-agent; it returns the instructions for the caller
 * to follow.
 */
class RunSkillTool : BaseTool() {
    override fun getName() = "run_skill"
    override fun getDisplayName() = "Skill"
    override fun getDescriptionEN() =
        "Resolve a user-defined skill (created in the Skills screen) and return " +
        "its prompt body. Use when the user mentions a skill by name or trigger phrase. " +
        "Match arg can be the skill id, exact name, or trigger phrase."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("match", "string",
            "Skill id, name, or trigger phrase to match.", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val needle = requireString(params, "match").trim()
        if (needle.isEmpty()) return ToolResult.error("match cannot be empty")
        val skill = UserSkillStore.find(needle)
            ?: UserSkillStore.all().firstOrNull { it.name.equals(needle, ignoreCase = true) }
            ?: UserSkillStore.matchTrigger(needle)
            ?: return ToolResult.error("No skill matched '$needle'")
        return ToolResult.success(
            "Skill: ${skill.name}\nDescripción: ${skill.description}\n\nPrompt:\n${skill.prompt}"
        )
    }
}
