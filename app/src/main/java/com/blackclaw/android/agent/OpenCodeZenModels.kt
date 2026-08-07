package com.blackclaw.android.agent

import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Keeps the list of OpenCode Zen free models fresh AND verified.
 *
 * How it works:
 *  - Zen's public `/models` catalog is authoritative for the available IDs.
 *    Its free models currently use the `-free` suffix, with `big-pickle` as
 *    the documented exception.
 *  - We select only those candidates and then PROBE each one with a 1-token
 *    completion using `Bearer public`. A model is kept only if the probe is
 *    authorized (HTTP 200 = works, 429 = works but rate-limited).
 *    401/402/403/404 → dropped.
 *
 * The verified list is cached in MMKV with a 6h TTL and used by the model
 * picker. A hand-verified seed list ships as the default so the app works even
 * before the first refresh / offline.
 *
 * Refresh triggers: app start, app resume, network recovery, Settings open,
 * and runtime 401/403 errors. This ensures new free models appear and dead
 * ones are pruned within minutes of an OpenCode catalog change.
 */
object OpenCodeZenModels {

    private const val TAG = "OpenCodeZen"
    private const val BASE = "https://opencode.ai/zen/v1"
    private const val KEY_CACHE = "opencode_zen_models_v1"
    private const val KEY_CACHE_TS = "opencode_zen_models_ts_v1"
    private const val TTL_MS = 6L * 60 * 60 * 1000  // 6h
    private const val BIG_PICKLE_ID = "big-pickle"

    private enum class ProbeState { AVAILABLE, UNAVAILABLE, UNKNOWN }

    /** Hand-verified free models (probed 2026-06) — used as seed/offline default.
     *  Note: the `deepseek-v4-flash-free` alias currently hangs on the provider,
     *  so we lead with `big-pickle`, which routes to the SAME model
     *  (its responses report model="deepseek-v4-flash") and answers reliably. */
    private val SEED = listOf(
        "big-pickle",              // = DeepSeek V4 Flash (working alias)
        "nemotron-3-ultra-free",
        "mimo-v2.5-free",
        "north-mini-code-free",
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val worker = Executors.newSingleThreadExecutor()
    private val refreshLock = Any()
    private var refreshing = false
    private val refreshCallbacks = mutableListOf<(RefreshResult) -> Unit>()

    /** The outcome of a catalog refresh. [ids] is always safe to render. */
    data class RefreshResult(
        val ids: List<String>,
        val updated: Boolean,
    )

    /**
     * The current best-known free model id list: cache if fresh, else seed.
     * Always returns something usable (never empty).
     */
    fun modelIds(): List<String> {
        val cached = readCache()
        return if (cached.isNotEmpty()) cached else SEED
    }

    /** Build CloudModel cards from the current id list, for the picker UI. */
    fun models(): List<CloudModel> = modelIds().map { id ->
        CloudModel(
            id = id,
            displayName = prettyName(id),
            inputPricePerM = 0.0,
            outputPricePerM = 0.0,
            tier = tierFor(id),
            contextSize = 128_000,
            recommended = id == "big-pickle",
        )
    }

    /** True if the cache is stale (or missing) and a refresh is worthwhile. */
    fun isStale(): Boolean =
        System.currentTimeMillis() - KVUtils.getLong(KEY_CACHE_TS, 0L) > TTL_MS

    /**
     * Refresh asynchronously if stale. Safe to call on app start / config open.
     * [onDone] (optional) is invoked on the worker thread with the fresh list.
     */
    fun refreshIfStale(onDone: ((RefreshResult) -> Unit)? = null) {
        if (!isStale()) {
            onDone?.invoke(RefreshResult(modelIds(), updated = false))
            return
        }
        refreshNow(onDone)
    }

    /**
     * Force a fresh catalog request, regardless of the cache TTL. Concurrent callers
     * join the same request and all receive its final result instead of being told
     * that stale data is fresh.
     */
    fun refreshNow(onDone: ((RefreshResult) -> Unit)? = null) {
        synchronized(refreshLock) {
            onDone?.let(refreshCallbacks::add)
            if (refreshing) return
            refreshing = true
        }
        worker.submit {
            val fresh = runCatching { fetchAndVerify() }.getOrNull()
            val result = if (!fresh.isNullOrEmpty()) {
                writeCache(fresh)
                maybeAutoSwitchActive(fresh)
                XLog.i(TAG, "Refreshed free models: ${fresh.joinToString()}")
                RefreshResult(fresh, updated = true)
            } else {
                XLog.w(TAG, "Refresh failed/empty; keeping ${modelIds().size} cached/seed models")
                RefreshResult(modelIds(), updated = false)
            }
            val callbacks = synchronized(refreshLock) {
                refreshing = false
                val pending = refreshCallbacks.toList()
                refreshCallbacks.clear()
                pending
            }
            callbacks.forEach { callback -> runCatching { callback(result) } }
        }
    }

    /** Called when network becomes available — refresh if stale. */
    fun refreshOnNetwork() {
        if (isStale()) refreshNow()
    }

    /** Fetch the catalog, pick candidates, probe each (in parallel), return verified ids. */
    private fun fetchAndVerify(): List<String> {
        val candidates = fetchCandidates()
        if (candidates.isEmpty()) return emptyList()
        // Probe concurrently so a refresh takes ~one timeout, not N×timeout.
        val pool = Executors.newFixedThreadPool(candidates.size.coerceAtMost(10))
        val probeResults = try {
            candidates.map { id -> pool.submit<Pair<String, ProbeState>> { id to probeFreeAccess(id) } }
                .mapNotNull { runCatching { it.get(25, TimeUnit.SECONDS) }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
        // A 5xx/timeout is an outage, not evidence that a free model was retired.
        // Preserve such candidates when at least one probe proves the catalog is
        // reachable; otherwise leave the last known cache untouched.
        if (probeResults.none { it.second == ProbeState.AVAILABLE }) return emptyList()
        val byId = probeResults.toMap()
        return candidates.filter { byId[it] != ProbeState.UNAVAILABLE }
    }

    /**
     * GET /models → candidate IDs to probe. Do not cap the full catalog before
     * filtering: Zen places free entries after many paid models, and the old
     * `ids.take(40)` meant that a catalog refresh never reached any free model.
     */
    private fun fetchCandidates(): List<String> {
        val req = Request.Builder()
            .url("$BASE/models")
            .header("Authorization", "Bearer public")
            .header("Accept", "application/json")
            .get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val arr = JSONObject(body).optJSONArray("data") ?: return emptyList()
            val ids = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val id = arr.getJSONObject(i).optString("id")
                if (id.isNotBlank()) ids.add(id)
            }
            return selectFreeCandidates(ids)
        }
    }

    /**
     * Zen currently labels all public free IDs with `-free`, except Big Pickle.
     * Keep this pure so catalog changes can be regression-tested without Android
     * storage or network dependencies. Big Pickle stays first as the preferred
     * general-purpose fallback.
     */
    internal fun selectFreeCandidates(catalogIds: List<String>): List<String> {
        val free = catalogIds
            .asSequence()
            .map(String::trim)
            .map(String::lowercase)
            .filter(String::isNotEmpty)
            .filter { it.endsWith("-free") || it == BIG_PICKLE_ID }
            .distinct()
            .toList()
        return free.sortedWith(compareBy<String> { it != BIG_PICKLE_ID }.thenBy { it })
    }

    /**
     * Probe a model with a 1-token completion. 200/429 prove public access;
     * 401/402/403/404 prove it is unavailable. Network faults and 5xx responses
     * are unknown so an OpenCode incident never prunes a working cached model.
     */
    private fun probeFreeAccess(modelId: String): ProbeState {
        val payload = JSONObject().apply {
            put("model", modelId)
            put("max_tokens", 1)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user"); put("content", "hi")
            }))
        }.toString()
        val req = Request.Builder()
            .url("$BASE/chat/completions")
            .header("Authorization", "Bearer public")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val state = when (resp.code) {
                    200, 429 -> ProbeState.AVAILABLE
                    401, 402, 403, 404 -> ProbeState.UNAVAILABLE
                    else -> ProbeState.UNKNOWN
                }
                XLog.d(TAG, "probe $modelId -> ${resp.code} ($state)")
                state
            }
        } catch (e: Exception) {
            XLog.d(TAG, "probe $modelId failed: ${e.message}")
            ProbeState.UNKNOWN
        }
    }

    // ── Cache ──

    /**
     * If the user's ACTIVE model is an OpenCode Zen free model that just dropped
     * out of the verified list (stopped being free), auto-switch to a still-free
     * one so they're never stuck on a dead model. No-op otherwise.
     */
    private fun maybeAutoSwitchActive(fresh: List<String>) {
        val provider = KVUtils.getLlmProvider()
        if (!provider.equals("OPENCODE_ZEN", ignoreCase = true)) return
        val active = KVUtils.getLlmModelName()
        if (active.isBlank() || active in fresh) return
        val replacement = fresh.firstOrNull { it == BIG_PICKLE_ID }
            ?: fresh.firstOrNull { it == "deepseek-v4-flash-free" }
            ?: fresh.firstOrNull() ?: return
        // Keep the active *and* default cloud selection in sync. Writing only the
        // active key made a stale free model return after switching to local and back.
        com.blackclaw.android.agent.llm.ModelConfigRepository.activateCloudSelection(
            modelId = replacement,
            explicitProviderName = CloudProvider.OPENCODE_ZEN.name,
            explicitBaseUrl = BASE,
        )
        KVUtils.sync()
        XLog.i(TAG, "Active free model '$active' no longer free — auto-switched to '$replacement'")
    }

    private fun readCache(): List<String> {
        val raw = KVUtils.getString(KEY_CACHE, "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun writeCache(ids: List<String>) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        KVUtils.putString(KEY_CACHE, arr.toString())
        KVUtils.putLong(KEY_CACHE_TS, System.currentTimeMillis())
        KVUtils.sync()
    }

    // ── Presentation helpers ──

    private fun prettyName(id: String): String {
        // big-pickle is OpenCode Zen's reliable alias for DeepSeek V4 Flash
        // (its completions report model="deepseek-v4-flash").
        if (id == BIG_PICKLE_ID) return "DeepSeek V4 Flash (Big Pickle)"
        val base = id.removeSuffix("-free")
        val nice = base.split("-", ".").joinToString(" ") {
            it.replaceFirstChar { c -> c.uppercase() }
        }
        return "$nice (Gratis)"
    }

    private fun tierFor(id: String): ModelTier = when {
        id.contains("ultra") || id.contains("pro") -> ModelTier.PRO
        id.contains("deepseek") || id.contains("big-pickle") || id.contains("plus") -> ModelTier.SMART
        else -> ModelTier.FAST
    }
}
