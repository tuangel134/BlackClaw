package com.blackclaw.android.tool.impl

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Lightweight HTTP GET tool. Lets the agent fetch a public URL and read
 * the response body. Useful for weather APIs, public JSON endpoints,
 * webhooks, or quick page peeks.
 *
 * Hard limits:
 *  - GET only (no POST/PUT/DELETE for safety)
 *  - Max 64 KB response body
 *  - 8s connect / read timeout
 *  - Refuses non-https unless explicit allow_http=true
 */
class HttpFetchTool : BaseTool() {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    override fun getName() = "http_fetch"
    override fun getDisplayName() = "HTTP GET"
    override fun getDescriptionEN() =
        "Fetch a URL with HTTP GET and return up to 64 KB of the response body. " +
        "HTTPS only by default. Use for weather APIs, exchange rates, public JSON, etc."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("url", "string", "URL to fetch (https://… preferred).", true),
        ToolParameter("accept", "string",
            "Optional Accept header (e.g. 'application/json'). Default '*/*'.", false),
        ToolParameter("allow_http", "boolean", "Allow http:// (insecure). Default false.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val url = requireString(params, "url").trim()
        if (url.isEmpty()) return ToolResult.error("url cannot be empty")
        val allowHttp = optionalBoolean(params, "allow_http", false)
        if (!url.startsWith("https://") && !(allowHttp && url.startsWith("http://"))) {
            return ToolResult.error("Use https:// or set allow_http=true")
        }
        val accept = optionalString(params, "accept", "*/*")
        val req = Request.Builder().url(url).header("Accept", accept).get().build()
        return try {
            client.newCall(req).execute().use { resp ->
                val code = resp.code
                val body = resp.body?.byteStream()?.use { stream ->
                    val limit = 64 * 1024
                    val buf = ByteArray(limit)
                    val n = stream.read(buf, 0, limit)
                    if (n <= 0) "" else String(buf, 0, n, Charsets.UTF_8)
                } ?: ""
                if (!resp.isSuccessful) {
                    ToolResult.error("HTTP $code: ${body.take(200)}")
                } else {
                    val ct = resp.header("Content-Type") ?: "?"
                    val pretty = tryPrettifyJson(body)
                    ToolResult.success("[$code $ct] ${pretty.take(8000)}")
                }
            }
        } catch (e: Exception) {
            ToolResult.error("HTTP fetch failed: ${e.message}")
        }
    }

    private fun tryPrettifyJson(body: String): String {
        val trimmed = body.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return runCatching { JSONObject(trimmed).toString(2) }.getOrDefault(body)
        }
        return body
    }
}
