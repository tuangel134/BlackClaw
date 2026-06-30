package com.blackclaw.android.agent

/**
 * Cloud LLM provider and model definitions.
 * Used by LlmConfigActivity to render the provider tabs + model cards.
 */

data class CloudModel(
    val id: String,
    val displayName: String,
    val inputPricePerM: Double,
    val outputPricePerM: Double,
    val tier: ModelTier,
    val contextSize: Int,
    val recommended: Boolean = false
)

enum class ModelTier(val stars: String, val label: String) {
    LITE("\u2606", "Lite"),       // ☆
    FAST("\u2605", "Fast"),       // ★
    SMART("\u2605\u2605", "Smart"),     // ★★
    PRO("\u2605\u2605\u2605", "Pro")    // ★★★
}

enum class CloudProvider(
    val displayName: String,
    val defaultBaseUrl: String,
    val models: List<CloudModel>,
    val showBaseUrl: Boolean = false
) {
    OPENAI(
        displayName = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        models = listOf(
            CloudModel("gpt-4o-mini", "GPT-4o Mini", 0.15, 0.60, ModelTier.FAST, 128_000, recommended = true),
            CloudModel("gpt-4o", "GPT-4o", 2.50, 10.00, ModelTier.SMART, 128_000),
            CloudModel("gpt-4.1", "GPT-4.1", 2.00, 8.00, ModelTier.PRO, 1_000_000),
            CloudModel("gpt-4.1-mini", "GPT-4.1 Mini", 0.40, 1.60, ModelTier.FAST, 1_000_000),
            CloudModel("gpt-4.1-nano", "GPT-4.1 Nano", 0.10, 0.40, ModelTier.LITE, 1_000_000),
        )
    ),
    ANTHROPIC(
        displayName = "Anthropic",
        defaultBaseUrl = "https://api.anthropic.com/v1",
        models = listOf(
            CloudModel("claude-sonnet-4-6", "Claude Sonnet 4.6", 3.00, 15.00, ModelTier.PRO, 200_000),
            CloudModel("claude-haiku-4-5", "Claude Haiku 4.5", 0.80, 4.00, ModelTier.FAST, 200_000, recommended = true),
        )
    ),
    GOOGLE(
        displayName = "Google",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
        models = listOf(
            CloudModel("gemini-2.5-flash", "Gemini 2.5 Flash", 0.15, 0.60, ModelTier.FAST, 1_000_000, recommended = true),
            CloudModel("gemini-2.5-pro", "Gemini 2.5 Pro", 1.25, 10.00, ModelTier.PRO, 1_000_000),
        )
    ),
    OPENCODE_ZEN(
        displayName = "OpenCode Zen (Gratis)",
        defaultBaseUrl = "https://opencode.ai/zen/v1",
        models = listOf(
            // Seed list (hand-verified free with Bearer public). The live list is
            // fetched + re-verified by OpenCodeZenModels and overrides this in the UI.
            CloudModel("deepseek-v4-flash-free", "Deepseek V4 Flash (Gratis)", 0.0, 0.0, ModelTier.SMART, 128_000, recommended = true),
            CloudModel("nemotron-3-ultra-free", "Nemotron 3 Ultra (Gratis)", 0.0, 0.0, ModelTier.PRO, 128_000),
            CloudModel("mimo-v2.5-free", "Mimo V2.5 (Gratis)", 0.0, 0.0, ModelTier.FAST, 128_000),
            CloudModel("north-mini-code-free", "North Mini Code (Gratis)", 0.0, 0.0, ModelTier.FAST, 128_000),
            CloudModel("big-pickle", "Big Pickle (Gratis)", 0.0, 0.0, ModelTier.SMART, 128_000),
        )
    ),
    CUSTOM(
        displayName = "Custom",
        defaultBaseUrl = "",
        models = emptyList(),
        showBaseUrl = true
    );

    companion object {
        /**
         * Find provider by name (case-insensitive).
         * Returns OPENAI as default.
         */
        fun fromName(name: String): CloudProvider {
            return entries.find { it.name.equals(name, ignoreCase = true) } ?: OPENAI
        }

        /**
         * Find the provider that contains a given model ID.
         */
        fun findProviderForModel(modelId: String): CloudProvider? {
            return entries.find { provider ->
                provider.models.any { it.id == modelId }
            }
        }
    }
}
