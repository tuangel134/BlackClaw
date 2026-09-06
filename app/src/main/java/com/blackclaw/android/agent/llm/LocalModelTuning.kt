package com.blackclaw.android.agent.llm

import com.blackclaw.android.utils.KVUtils
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest
import kotlin.math.ceil

enum class LocalModelPreset {
    PRECISE,
    BALANCED,
    CREATIVE,
    DETERMINISTIC,
    CUSTOM,
}

data class LocalModelTuning(
    val preset: LocalModelPreset = LocalModelPreset.BALANCED,
    val temperature: Double = 0.7,
    val topP: Double = 0.95,
    val topK: Int = 64,
    val seed: Int = 0,
    val maxOutputTokens: Int = 1536,
    val contextWindowTokens: Int = 8192,
    val autoCompactContext: Boolean = true,
) {
    fun normalized(): LocalModelTuning {
        val normalizedContext = contextWindowTokens.coerceIn(2048, 32768)
        val normalizedOutput = maxOutputTokens
            .coerceIn(64, 4096)
            .coerceAtMost((normalizedContext - LocalContextBudget.SAFETY_TOKENS - 256).coerceAtLeast(64))
        return copy(
            temperature = temperature.coerceIn(0.0, 2.0),
            topP = topP.coerceIn(0.01, 1.0),
            // LiteRT-LM's GPU sampler currently supports a practical max top-k of 64.
            topK = topK.coerceIn(1, 64),
            maxOutputTokens = normalizedOutput,
            contextWindowTokens = normalizedContext,
        )
    }

    fun samplerConfig(temperatureOverride: Double? = null): SamplerConfig {
        val value = normalized()
        return SamplerConfig(
            topK = value.topK,
            topP = value.topP,
            temperature = (temperatureOverride ?: value.temperature).coerceIn(0.0, 2.0),
            seed = value.seed,
        )
    }

    companion object {
        fun preset(preset: LocalModelPreset, contextWindowTokens: Int = 8192): LocalModelTuning = when (preset) {
            LocalModelPreset.PRECISE -> LocalModelTuning(
                preset = preset,
                temperature = 0.2,
                topP = 0.85,
                topK = 32,
                seed = 0,
                maxOutputTokens = 1024,
                contextWindowTokens = contextWindowTokens,
            )
            LocalModelPreset.BALANCED -> LocalModelTuning(
                preset = preset,
                temperature = 0.7,
                topP = 0.95,
                topK = 64,
                seed = 0,
                maxOutputTokens = 1536,
                contextWindowTokens = contextWindowTokens,
            )
            LocalModelPreset.CREATIVE -> LocalModelTuning(
                preset = preset,
                temperature = 1.0,
                topP = 0.98,
                topK = 64,
                seed = 0,
                maxOutputTokens = 2048,
                contextWindowTokens = contextWindowTokens,
            )
            LocalModelPreset.DETERMINISTIC -> LocalModelTuning(
                preset = preset,
                temperature = 0.1,
                topP = 0.9,
                topK = 32,
                seed = 42,
                maxOutputTokens = 1024,
                contextWindowTokens = contextWindowTokens,
            )
            LocalModelPreset.CUSTOM -> LocalModelTuning(preset = preset, contextWindowTokens = contextWindowTokens)
        }.normalized()
    }
}

/** Per-model generation/runtime settings. These are non-secret preferences. */
object LocalModelTuningStore {
    private const val PREFIX = "blackclaw_local_model_tuning_v1_"
    private val gson = Gson()

    fun get(modelPath: String): LocalModelTuning {
        if (modelPath.isBlank()) return LocalModelTuning()
        val raw = KVUtils.getString(storageKey(modelPath), "")
        if (raw.isBlank()) return LocalModelTuning()
        return runCatching { gson.fromJson(raw, LocalModelTuning::class.java).normalized() }
            .getOrElse { LocalModelTuning() }
    }

    fun save(modelPath: String, tuning: LocalModelTuning): Boolean {
        if (modelPath.isBlank()) return false
        return KVUtils.putString(storageKey(modelPath), gson.toJson(tuning.normalized()))
    }

    fun reset(modelPath: String) {
        if (modelPath.isNotBlank()) KVUtils.remove(storageKey(modelPath))
    }

    private fun storageKey(modelPath: String): String {
        val knownId = LocalModelManager.AVAILABLE_MODELS.firstOrNull { model ->
            modelPath.endsWith(model.fileName, ignoreCase = true) ||
                File(modelPath).name.equals(model.url.substringAfterLast('/').substringBefore('?'), ignoreCase = true)
        }?.id
        val stable = knownId ?: File(modelPath).name.lowercase()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(stable.toByteArray(Charsets.UTF_8))
            .take(10)
            .joinToString("") { "%02x".format(it) }
        return PREFIX + digest
    }
}

/** Conservative token budgeting around LiteRT-LM's stateful Conversation API. */
object LocalContextBudget {
    const val SAFETY_TOKENS = 384
    private const val APPROX_CHARS_PER_TOKEN = 3.0

    fun inputBudgetTokens(tuning: LocalModelTuning): Int {
        val value = tuning.normalized()
        return (value.contextWindowTokens - value.maxOutputTokens - SAFETY_TOKENS).coerceAtLeast(512)
    }

    fun estimateTokensFromChars(chars: Int): Int = ceil(chars.coerceAtLeast(0) / APPROX_CHARS_PER_TOKEN).toInt()

    fun shouldRecreate(currentApproxChars: Int, incomingChars: Int, tuning: LocalModelTuning): Boolean {
        if (!tuning.autoCompactContext) return false
        return estimateTokensFromChars(currentApproxChars + incomingChars) >= inputBudgetTokens(tuning)
    }

    fun compactContextCharBudget(tuning: LocalModelTuning): Int =
        (inputBudgetTokens(tuning) * APPROX_CHARS_PER_TOKEN * 0.55).toInt().coerceAtLeast(1200)

    fun isTokenOverflow(error: Throwable?): Boolean {
        var current = error
        repeat(8) {
            val message = current?.message.orEmpty()
            if ((message.contains("Input token ids are too long", ignoreCase = true) ||
                    message.contains("maximum number of tokens allowed", ignoreCase = true) ||
                    message.contains("token", ignoreCase = true) && message.contains("exceed", ignoreCase = true)) &&
                (message.contains("INVALID_ARGUMENT", ignoreCase = true) || message.contains("token", ignoreCase = true))) {
                return true
            }
            current = current?.cause
        }
        return false
    }
}
