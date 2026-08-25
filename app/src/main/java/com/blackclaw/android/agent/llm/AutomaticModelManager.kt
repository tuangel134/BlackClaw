package com.blackclaw.android.agent.llm

import android.content.Context
import com.blackclaw.android.agent.AgentConfig
import com.blackclaw.android.agent.CloudModel
import com.blackclaw.android.agent.CloudProvider
import com.blackclaw.android.agent.LlmProvider
import com.blackclaw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import java.io.File

/**
 * Discovers configured cloud/local models, benchmarks them with a tiny probe and
 * keeps a latency-ranked list for AUTO mode.
 *
 * This is intentionally a policy layer, not another LLM client.  It never sends
 * conversation content: the benchmark prompt is a fixed one-word response. A
 * normal app cannot know whether a provider account is valid without making a
 * request, so failures are recorded per model and used as a fast failover list.
 */
object AutomaticModelManager {

    private const val BENCHMARK_KEY = "blackclaw_auto_model_benchmarks_v1"
    private const val PROBE_PROMPT = "Responde únicamente OK."
    private const val PROBE_SYSTEM = "Responde con una sola palabra: OK."

    private val gson = Gson()
    private val resultLock = Any()

    enum class Kind { CLOUD, LOCAL }

    data class Candidate(
        val key: String,
        val displayName: String,
        val kind: Kind,
        val providerName: String = "",
        val modelName: String = "",
        val baseUrl: String = "",
        val apiKey: String = "",
        val modelPath: String = "",
    ) {
        fun toAgentConfig(template: AgentConfig): AgentConfig = if (kind == Kind.LOCAL) {
            template.copy(
                apiKey = "",
                baseUrl = modelPath,
                modelName = modelName,
                provider = LlmProvider.LOCAL,
                streaming = false,
            )
        } else {
            template.copy(
                apiKey = apiKey,
                baseUrl = baseUrl,
                modelName = modelName,
                provider = if (CloudProvider.fromName(providerName) == CloudProvider.ANTHROPIC) {
                    LlmProvider.ANTHROPIC
                } else {
                    LlmProvider.OPENAI
                },
                streaming = template.streaming,
            )
        }
    }

    data class BenchmarkResult(
        val key: String,
        val displayName: String,
        val kind: Kind,
        val success: Boolean,
        val latencyMs: Long,
        val error: String? = null,
        val testedAt: Long = System.currentTimeMillis(),
    )

    data class BenchmarkReport(
        val total: Int,
        val results: List<BenchmarkResult>,
    ) {
        val successful: List<BenchmarkResult> get() = results.filter { it.success }
        val fastest: BenchmarkResult? get() = successful.minByOrNull { it.latencyMs }
    }

    /** Returns every model that can actually be attempted with current settings. */
    fun discover(context: Context): List<Candidate> {
        val defaultProvider = KVUtils.getDefaultCloudProvider()
            .ifBlank { KVUtils.getLlmProvider() }
            .uppercase()
        val globalKey = KVUtils.getLlmApiKey()
        val candidates = linkedMapOf<String, Candidate>()
        val modelConfig = ModelConfigRepository.snapshot()

        CloudProvider.entries
            .filter { it != CloudProvider.CUSTOM }
            .forEach { provider ->
                val providerKey = KVUtils.getApiKeyForProvider(provider.name)
                    .ifBlank { if (provider.name == defaultProvider) globalKey else "" }
                val available = provider == CloudProvider.OPENCODE_ZEN || providerKey.isNotBlank()
                if (!available) return@forEach

                val models: List<CloudModel> = if (provider == CloudProvider.OPENCODE_ZEN) {
                    com.blackclaw.android.agent.OpenCodeZenModels.models()
                } else {
                    provider.models
                }
                models.forEach { model ->
                    val key = cloudKey(provider.name, model.id)
                    candidates[key] = Candidate(
                        key = key,
                        displayName = "${provider.displayName} · ${model.displayName}",
                        kind = Kind.CLOUD,
                        providerName = provider.name,
                        modelName = model.id,
                        baseUrl = provider.defaultBaseUrl,
                        apiKey = if (provider == CloudProvider.OPENCODE_ZEN) "public" else providerKey,
                    )
                }
            }

        // Custom endpoints expose only the model explicitly configured by the user.
        val customProvider = CloudProvider.fromName(defaultProvider)
        if (customProvider == CloudProvider.CUSTOM) {
            val model = KVUtils.getDefaultCloudModel().ifBlank { KVUtils.getLlmModelName() }
            val baseUrl = KVUtils.getDefaultCloudBaseUrl().ifBlank { KVUtils.getLlmBaseUrl() }
            val key = KVUtils.getApiKeyForProvider("CUSTOM").ifBlank { globalKey }
            if (model.isNotBlank() && baseUrl.isNotBlank() && key.isNotBlank()) {
                val candidateKey = cloudKey("CUSTOM", model)
                candidates[candidateKey] = Candidate(
                    key = candidateKey,
                    displayName = "Custom · $model",
                    kind = Kind.CLOUD,
                    providerName = "CUSTOM",
                    modelName = model,
                    baseUrl = baseUrl,
                    apiKey = key,
                )
            }
        }

        // Preserve a configured endpoint even when it is a legacy/custom URL
        // that does not match the built-in provider catalog.
        val configuredCloud = if (modelConfig.activeMode == ActiveModelMode.CLOUD) {
            modelConfig.activeCloud
        } else {
            modelConfig.defaultCloud
        }
        if (configuredCloud.isConfigured && configuredCloud.resolvedBaseUrl.isNotBlank()) {
            val configuredProvider = configuredCloud.provider
            val customUrl = configuredProvider != CloudProvider.CUSTOM &&
                configuredCloud.resolvedBaseUrl.trimEnd('/') != configuredProvider.defaultBaseUrl.trimEnd('/')
            val candidateProviderName = if (customUrl) CloudProvider.CUSTOM.name else configuredCloud.providerName
            val candidateDisplayName = if (customUrl) {
                "${CloudProvider.CUSTOM.displayName} · ${configuredCloud.modelName}"
            } else {
                "${configuredProvider.displayName} · ${configuredCloud.modelName}"
            }
            val candidateKey = cloudKey(candidateProviderName, configuredCloud.modelName)
            candidates[candidateKey] = Candidate(
                key = candidateKey,
                displayName = candidateDisplayName,
                kind = Kind.CLOUD,
                providerName = candidateProviderName,
                modelName = configuredCloud.modelName,
                baseUrl = configuredCloud.resolvedBaseUrl,
                apiKey = configuredCloud.resolvedApiKey,
            )
        }

        // Include the active/linked model even when it was imported from another
        // app and therefore is not part of LocalModelManager's built-in catalog.
        val configuredLocal = modelConfig.local
        if (configuredLocal.modelPath.isNotBlank() && File(configuredLocal.modelPath).isFile) {
            val canonicalPath = runCatching { File(configuredLocal.modelPath).canonicalPath }
                .getOrDefault(configuredLocal.modelPath)
            val key = "LOCAL:$canonicalPath"
            candidates[key] = Candidate(
                key = key,
                displayName = "Local · ${configuredLocal.displayName.ifBlank { File(canonicalPath).nameWithoutExtension }}",
                kind = Kind.LOCAL,
                modelName = configuredLocal.modelId.ifBlank { File(canonicalPath).nameWithoutExtension },
                modelPath = canonicalPath,
            )
        }

        // Include every compatible downloaded model, not only the selected one.
        LocalModelManager.catalog(context)
            .filter { it.isDownloaded && it.model.url.isNotBlank() }
            .forEach { entry ->
                val path = entry.path ?: LocalModelManager.getModelPath(context, entry.model) ?: return@forEach
                val canonicalPath = runCatching { File(path).canonicalPath }.getOrDefault(path)
                val key = "LOCAL:$canonicalPath"
                candidates[key] = Candidate(
                    key = key,
                    displayName = "Local · ${entry.model.displayName}",
                    kind = Kind.LOCAL,
                    modelName = entry.model.id,
                    modelPath = canonicalPath,
                )
            }

        // Edge Gallery and other apps may place a compatible LiteRT-LM file in
        // shared Downloads/Documents. Private app sandboxes remain inaccessible
        // on Android; those files must still be imported through the picker.
        ExternalModelDiscovery.discoverVisibleModels(context)
            .filter { it.isCompatible }
            .forEach { discovered ->
                val canonicalPath = runCatching { discovered.file.canonicalPath }
                    .getOrDefault(discovered.file.absolutePath)
                val key = "LOCAL:$canonicalPath"
                candidates[key] = Candidate(
                    key = key,
                    displayName = "Local · ${discovered.file.nameWithoutExtension}",
                    kind = Kind.LOCAL,
                    modelName = discovered.file.nameWithoutExtension,
                    modelPath = canonicalPath,
                )
            }

        return candidates.values.toList()
    }

    /**
     * Rank candidates using the most recent successful benchmark. Unknown models
     * remain available after known-good ones so a newly downloaded model can still
     * be tried without displacing a proven fast model.
     */
    fun ranked(context: Context, preferredKey: String? = null): List<Candidate> {
        val candidates = discover(context)
        if (candidates.isEmpty()) return emptyList()
        val online = AutomaticModelResolver.isInternetValidated(context)
        val records = readResults().associateBy { it.key }
        val localAvailable = candidates.any { it.kind == Kind.LOCAL }
        val effective = if (!online && localAvailable) {
            candidates.filter { it.kind == Kind.LOCAL }
        } else {
            candidates
        }

        return effective.sortedWith(
            compareBy<Candidate> {
                // Online AUTO should try cloud first; offline AUTO must never
                // waste a round trip before using a downloaded model.
                if (!online && it.kind == Kind.CLOUD) 1 else 0
            }.thenBy {
                val record = records[it.key]
                when {
                    record?.success == true -> 0
                    it.key == preferredKey -> 1
                    record != null -> 3
                    else -> 2
                }
            }.thenBy { records[it.key]?.latencyMs ?: Long.MAX_VALUE }
                .thenBy { it.key }
        )
    }

    fun candidateKeyFor(config: AgentConfig): String? {
        return candidateForConfig(config)?.key
    }

    /**
     * Converts the currently selected config into a candidate even when it is
     * a custom OpenAI-compatible endpoint that is not in the built-in catalog.
     * AUTO must never silently discard that configured model while building its
     * fallback list.
     */
    fun candidateForConfig(config: AgentConfig): Candidate? {
        if (config.provider == LlmProvider.LOCAL) {
            val path = config.baseUrl.trim()
            if (path.isBlank()) return null
            val canonicalPath = runCatching { File(path).canonicalPath }.getOrDefault(path)
            return Candidate(
                key = "LOCAL:$canonicalPath",
                displayName = "Local · ${config.modelName.ifBlank { File(canonicalPath).nameWithoutExtension }}",
                kind = Kind.LOCAL,
                modelName = config.modelName.ifBlank { File(canonicalPath).nameWithoutExtension },
                modelPath = canonicalPath,
            )
        }

        val normalizedBase = config.baseUrl.trimEnd('/')
        val providerByUrl = CloudProvider.entries.firstOrNull {
            it != CloudProvider.CUSTOM && it.defaultBaseUrl.trimEnd('/') == normalizedBase
        }
        val providerByModel = CloudProvider.findProviderForModel(config.modelName)
        val provider = providerByUrl ?: providerByModel?.takeIf {
            normalizedBase.isBlank() || it.defaultBaseUrl.trimEnd('/') == normalizedBase
        }
        val providerName = provider?.name ?: CloudProvider.CUSTOM.name
        val displayProvider = provider?.displayName ?: CloudProvider.CUSTOM.displayName
        val model = config.modelName.ifBlank { "configured" }
        return Candidate(
            key = cloudKey(providerName, model),
            displayName = "$displayProvider · $model",
            kind = Kind.CLOUD,
            providerName = providerName,
            modelName = model,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
        )
    }

    fun readResults(): List<BenchmarkResult> {
        return synchronized(resultLock) {
            val raw = KVUtils.getString(BENCHMARK_KEY, "")
            if (raw.isBlank()) return@synchronized emptyList()
            runCatching {
                val type = object : TypeToken<List<BenchmarkResult>>() {}.type
                gson.fromJson<List<BenchmarkResult>>(raw, type).orEmpty()
            }.getOrDefault(emptyList())
        }
    }

    /** Run one tiny request per candidate. Call from a worker thread. */
    fun benchmark(
        context: Context,
        template: AgentConfig,
        onProgress: (completed: Int, total: Int, result: BenchmarkResult) -> Unit = { _, _, _ -> },
    ): BenchmarkReport {
        val candidates = discover(context)
        if (candidates.isEmpty()) return BenchmarkReport(0, emptyList())
        val results = mutableListOf<BenchmarkResult>()

        candidates.forEachIndexed { index, candidate ->
            val started = System.nanoTime()
            var client: LlmClient? = null
            val result = try {
                if (candidate.kind == Kind.LOCAL) {
                    val local = LocalModelRuntime.runSingleShot(
                        context = context,
                        modelPath = candidate.modelPath,
                        systemPrompt = PROBE_SYSTEM,
                        prompt = PROBE_PROMPT,
                        temperature = 0.0,
                    )
                    if (local.text.isNullOrBlank()) error("Respuesta vacía")
                } else {
                    client = LlmClientFactory.createSingle(
                        candidate.toAgentConfig(template),
                        timeoutMs = LlmClientFactory.BENCHMARK_REQUEST_TIMEOUT_MS,
                    )
                    val response = client.chat(
                        listOf(SystemMessage.from(PROBE_SYSTEM), UserMessage.from(PROBE_PROMPT)),
                        emptyList(),
                    )
                    if (response.text.isNullOrBlank() && !response.hasToolExecutionRequests()) {
                        error("Respuesta vacía")
                    }
                }
                BenchmarkResult(
                    key = candidate.key,
                    displayName = candidate.displayName,
                    kind = candidate.kind,
                    success = true,
                    latencyMs = elapsedMs(started),
                )
            } catch (error: Exception) {
                BenchmarkResult(
                    key = candidate.key,
                    displayName = candidate.displayName,
                    kind = candidate.kind,
                    success = false,
                    latencyMs = elapsedMs(started),
                    error = error.message?.take(160) ?: error.javaClass.simpleName,
                )
            } finally {
                runCatching { client?.close() }
                // LiteRT-LM exposes one shared session. Reset after a benchmark so
                // the active chat/task can load its model cleanly on return.
                if (candidate.kind == Kind.LOCAL) runCatching { LocalModelRuntime.resetSharedEngine() }
            }
            results += result
            writeResult(result)
            onProgress(index + 1, candidates.size, result)
        }
        return BenchmarkReport(candidates.size, results)
    }

    fun fastestSummary(context: Context): String {
        val records = readResults().filter { it.success }
        val ranked = ranked(context)
        val fastest = ranked.firstOrNull { candidate ->
            records.any { record -> record.key == candidate.key }
        }
        val latency = records.firstOrNull { it.key == fastest?.key }?.latencyMs
        return if (fastest != null && latency != null) {
            "AUTO · ${fastest.displayName} · ${latency} ms"
        } else {
            "AUTO · toca Probar modelos para medir velocidad"
        }
    }

    fun recordRuntimeSuccess(candidate: Candidate, latencyMs: Long) {
        writeResult(
            BenchmarkResult(
                key = candidate.key,
                displayName = candidate.displayName,
                kind = candidate.kind,
                success = true,
                latencyMs = latencyMs,
            )
        )
    }

    fun recordRuntimeFailure(candidate: Candidate, latencyMs: Long, error: Throwable?) {
        writeResult(
            BenchmarkResult(
                key = candidate.key,
                displayName = candidate.displayName,
                kind = candidate.kind,
                success = false,
                latencyMs = latencyMs,
                error = error?.message?.take(160) ?: error?.javaClass?.simpleName,
            )
        )
    }

    private fun writeResult(result: BenchmarkResult) {
        synchronized(resultLock) {
            val merged = (readResults().filterNot { it.key == result.key } + result)
                .sortedByDescending { it.testedAt }
                .take(100)
            KVUtils.putString(BENCHMARK_KEY, gson.toJson(merged))
            KVUtils.sync()
        }
    }

    private fun elapsedMs(started: Long): Long =
        ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)

    private fun cloudKey(provider: String, model: String): String =
        "CLOUD:${provider.uppercase()}:$model"
}
