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
 * Why both:
 *  - The `/models` endpoint lists many models. The "-free" suffix is NOT a
 *    reliable indicator — e.g. qwen3.6-plus-free and minimax-m3-free return
 *    401 with the anonymous "public" token despite the name.
 *  - So we fetch the catalog, take the plausible free candidates, then PROBE
 *    each one with a 1-token completion using `Bearer public`. A model is kept
 *    only if the probe is authorized (HTTP 200 = works, 429 = works but
 *    rate-limited). 401/402/403/404 → dropped.
 *
 * The verified list is cached in MMKV with a 24h TTL and used by the model
 * picker. A hand-verified seed list ships as the default so the app works even
 * before the first refresh / offline.
 */
object OpenCodeZenModels {

    private const val TAG = "OpenCodeZen"
    private const val BASE = "https://opencode.ai/zen/v1"
    private const val KEY_CACHE = "opencode_zen_models_v1"
    private const val KEY_CACHE_TS = "opencode_zen_models_ts_v1"
    private const val TTL_MS = 24L * 60 * 60 * 1000  // 24h

    /** Hand-verified free models (probed 2026-06) — used as seed/offline default. */
    private val SEED = listOf(
        "deepseek-v4-flash-free",
        "nemotron-3-ultra-free",
        "mimo-v2.5-free",
        "north-mini-code-free",
        "big-pickle",
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val worker = Executors.newSingleThreadExecutor()

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
            recommended = id == "deepseek-v4-flash-free",
        )
    }

    /** True if the cache is stale (or missing) and a refresh is worthwhile. */
    fun isStale(): Boolean =
        System.currentTimeMillis() - KVUtils.getLong(KEY_CACHE_TS, 0L) > TTL_MS

    /**
     * Refresh asynchronously if stale. Safe to call on app start / config open.
     * [onDone] (optional) is invoked on the worker thread with the fresh list.
     */
    fun refreshIfStale(onDone: ((List<String>) -> Unit)? = null) {
        if (!isStale()) { onDone?.invoke(modelIds()); return }
        refreshNow(onDone)
    }

    fun refreshNow(onDone: ((List<String>) -> Unit)? = null) {
        worker.submit {
            val fresh = runCatching { fetchAndVerify() }.getOrNull()
            if (!fresh.isNullOrEmpty()) {
                writeCache(fresh)
                XLog.i(TAG, "Refreshed free models: ${fresh.joinToString()}")
                onDone?.invoke(fresh)
            } else {
                XLog.w(TAG, "Refresh failed/empty; keeping ${modelIds().size} cached/seed models")
                onDone?.invoke(modelIds())
            }
        }
    }

    /** Fetch the catalog, pick candidates, probe each (in parallel), return verified ids. */
    private fun fetchAndVerify(): List<String> {
        val candidates = fetchCandidates()
        if (candidates.isEmpty()) return emptyList()
        // Probe concurrently so a refresh takes ~one timeout, not N×timeout.
        val pool = Executors.newFixedThreadPool(candidates.size.coerceAtMost(6))
        val verified = try {
            candidates.map { id -> pool.submit<Pair<String, Boolean>> { id to probeIsFree(id) } }
                .mapNotNull { runCatching { it.get(25, TimeUnit.SECONDS) }.getOrNull() }
                .filter { it.second }
                .map { it.first }
        } finally {
            pool.shutdownNow()
        }
        // If verification rejected everything (network blip), keep the seed subset.
        return verified.ifEmpty { candidates.filter { it in SEED } }
    }

    /** GET /models → ids that plausibly free (end in -free, or big-pickle). */
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
                if (id.endsWith("-free") || id == "big-pickle") ids.add(id)
            }
            return ids
        }
    }

    /**
     * Probe a model with a 1-token completion. Authorized (free) if the server
     * returns 200 (works) or 429 (works, rate-limited). 401/402/403 → not free.
     */
    private fun probeIsFree(modelId: String): Boolean {
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
                val ok = resp.code == 200 || resp.code == 429
                XLog.d(TAG, "probe $modelId -> ${resp.code} (${if (ok) "free" else "blocked"})")
                ok
            }
        } catch (e: Exception) {
            XLog.d(TAG, "probe $modelId failed: ${e.message}")
            false
        }
    }

    // ── Cache ──

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
