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
            CloudModel("gpt-5.6-sol", "GPT-5.6 Sol", 5.00, 30.00, ModelTier.PRO, 1_050_000, recommended = true),
            CloudModel("gpt-5.6-terra", "GPT-5.6 Terra", 2.00, 12.00, ModelTier.SMART, 1_050_000),
            CloudModel("gpt-5.6-luna", "GPT-5.6 Luna", 0.20, 1.20, ModelTier.FAST, 1_050_000),
        )
    ),
    ANTHROPIC(
        displayName = "Anthropic",
        defaultBaseUrl = "https://api.anthropic.com/v1",
        models = listOf(
            CloudModel("claude-fable-5", "Claude Fable 5", 10.00, 50.00, ModelTier.PRO, 1_000_000),
            CloudModel("claude-opus-5", "Claude Opus 5", 5.00, 25.00, ModelTier.PRO, 1_000_000, recommended = true),
            CloudModel("claude-sonnet-5", "Claude Sonnet 5", 2.00, 10.00, ModelTier.SMART, 1_000_000),
            CloudModel("claude-haiku-4-5", "Claude Haiku 4.5", 1.00, 5.00, ModelTier.FAST, 200_000),
        )
    ),
    GOOGLE(
        displayName = "Google",
        // The app uses LangChain's OpenAI-compatible client for Google. The
        // /openai/ suffix is required for chat/completions and model listing.
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/",
        models = listOf(
            CloudModel("gemini-3.6-flash", "Gemini 3.6 Flash", 1.50, 7.50, ModelTier.SMART, 1_000_000, recommended = true),
            CloudModel("gemini-3.5-flash", "Gemini 3.5 Flash", 1.50, 9.00, ModelTier.SMART, 1_000_000),
            CloudModel("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", 0.30, 2.50, ModelTier.FAST, 1_000_000),
            CloudModel("gemini-3.1-flash-lite", "Gemini 3.1 Flash-Lite", 0.25, 1.50, ModelTier.FAST, 1_000_000),
            CloudModel("gemini-3.1-pro-preview", "Gemini 3.1 Pro (Preview)", 2.00, 12.00, ModelTier.PRO, 1_000_000),
            // Stable 2.5 models remain available as a migration fallback while
            // providers roll out the Gemini 3 catalog to every account.
            CloudModel("gemini-2.5-pro", "Gemini 2.5 Pro", 1.25, 10.00, ModelTier.PRO, 1_000_000),
            CloudModel("gemini-2.5-flash", "Gemini 2.5 Flash", 0.30, 2.50, ModelTier.FAST, 1_000_000),
            CloudModel("gemini-2.5-flash-lite", "Gemini 2.5 Flash-Lite", 0.10, 0.40, ModelTier.LITE, 1_000_000),
        )
    ),
    OPENCODE_ZEN(
        displayName = "BlackClaw Free",
        defaultBaseUrl = "https://opencode.ai/zen/v1",
        models = listOf(
            // Seed list (hand-verified free with Bearer public). The live list is
            // fetched + re-verified by OpenCodeZenModels and overrides this in the UI.
            // big-pickle = DeepSeek V4 Flash (reliable alias; the *-free one hangs).
            CloudModel("big-pickle", "DeepSeek V4 Flash (Big Pickle)", 0.0, 0.0, ModelTier.SMART, 128_000, recommended = true),
            CloudModel("nemotron-3-ultra-free", "Nemotron 3 Ultra (Gratis)", 0.0, 0.0, ModelTier.PRO, 128_000),
            CloudModel("mimo-v2.5-free", "Mimo V2.5 (Gratis)", 0.0, 0.0, ModelTier.FAST, 128_000),
            CloudModel("north-mini-code-free", "North Mini Code (Gratis)", 0.0, 0.0, ModelTier.FAST, 128_000),
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
