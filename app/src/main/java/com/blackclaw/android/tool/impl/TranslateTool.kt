package com.blackclaw.android.tool.impl

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Translation via the unofficial Google Translate web endpoint.
 * No API key required and works offline-of-account, but Google rate-limits.
 *
 * The agent already has translation reasoning in cloud mode; this tool is here
 * for the local model + offline-cloud users. If the endpoint fails or rate-limits,
 * we return a clear error so the LLM can fall back to its own reasoning.
 */
class TranslateTool : BaseTool() {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    override fun getName() = "translate"
    override fun getDisplayName() = "Traducir"
    override fun getDescriptionEN() =
        "Translate text. target is a language code like 'en', 'es', 'fr', 'de', 'ja'. " +
        "Source is auto-detected if omitted."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("text", "string", "Text to translate.", true),
        ToolParameter("target", "string", "Target language code (e.g. en, es, fr).", true),
        ToolParameter("source", "string", "Optional source code; default auto.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val text = requireString(params, "text")
        val target = requireString(params, "target").lowercase()
        val source = optionalString(params, "source", "auto").lowercase()
        if (text.isBlank()) return ToolResult.error("text cannot be empty")
        val q = URLEncoder.encode(text, "UTF-8")
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$source&tl=$target&dt=t&q=$q"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 BlackClaw")
            .get()
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ToolResult.error("HTTP ${resp.code}")
                val body = resp.body?.string() ?: return ToolResult.error("respuesta vacía")
                val arr = JSONArray(body)
                val sentences = arr.optJSONArray(0) ?: return ToolResult.error("formato inesperado")
                val out = buildString {
                    for (i in 0 until sentences.length()) {
                        val seg = sentences.optJSONArray(i)?.optString(0) ?: continue
                        append(seg)
                    }
                }
                ToolResult.success(out)
            }
        } catch (e: Exception) {
            ToolResult.error("Traducción falló: ${e.message}")
        }
    }
}
