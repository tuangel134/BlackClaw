package com.blackclaw.android.agent.skill

/**
 * BlackClaw Skill definition.
 *
 * A skill is a pre-defined multi-step action sequence that saves 3-10+
 * LLM rounds. Skills execute deterministically first, falling back to
 * LLM agent loop only when deterministic steps fail.
 *
 * Format: structured skill definition with steps, params, and triggers.
 */
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val category: SkillCategory,
    val estimatedStepsSaved: Int = 5,
    val steps: List<SkillStep>,
    val parameters: List<SkillParameter> = emptyList(),
    val triggerPatterns: List<String> = emptyList(),
    val fallbackGoal: String = "",
    /** If true, this skill appears in the Task UI for users to initiate.
     *  If false, it's only used internally by the LLM agent (e.g. dismiss_popup, go_back). */
    val userFacing: Boolean = false
)

enum class SkillCategory(val label: String, val icon: String) {
    INPUT("Input", "\uD83D\uDD0D"),        // 🔍
    NAVIGATION("Navigation", "\uD83D\uDCF1"), // 📱
    MESSAGING("Messaging", "\uD83D\uDCAC"),   // 💬
    DISMISS("Dismiss", "❌"),
    MEDIA("Media", "\uD83D\uDCF7"),           // 📷
    GENERAL("General", "\uD83D\uDCCB")        // 📋
}

data class SkillStep(
    val toolName: String,
    val params: Map<String, String> = emptyMap(),
    val description: String = "",
    val optional: Boolean = false,
    val retries: Int = 1
)

data class SkillParameter(
    val name: String,
    val type: String = "string",
    val required: Boolean = true,
    val description: String = "",
    val defaultValue: String = ""
)

/**
 * Result of a skill execution.
 */
data class SkillResult(
    val success: Boolean,
    val stepsUsed: Int,
    val tokensUsed: Int = 0,
    val fallbackUsed: Boolean = false,
    val message: String = ""
)
