package com.blackclaw.android.server

import android.content.Context
import com.blackclaw.android.BuildConfig
import com.blackclaw.android.channel.ChannelManager
import com.blackclaw.android.tool.ToolRegistry
import com.blackclaw.android.tool.ToolResult
import com.blackclaw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.blackclaw.android.utils.XLog
import fi.iki.elonen.NanoHTTPD

/**
 * Local HTTP configuration server. Serves a page for editing channel and LLM
 * settings from a desktop browser on the same machine.
 *
 * ## Security
 *
 * Binds to `127.0.0.1`, but on Android loopback is **not** isolated per app — any
 * installed app with `INTERNET` can reach it. Every API route under `/api` therefore
 * requires [sessionToken], which is generated on the device and displayed on the
 * phone screen. That is the one credential an on-device attacker cannot obtain over
 * the same channel.
 *
 * Deliberately absent: `Access-Control-Allow-Origin`. The config page is served by
 * this server, so its own requests are same-origin and need no CORS header. Sending
 * `*` (as this class used to) let any plain-HTTP page in any browser on the device
 * read the API key cross-origin.
 *
 * See [ConfigServerPolicy] for the decision logic and the reasoning behind each rule.
 */
class ConfigServer(
    private val context: Context,
    port: Int = PORT,
    /** Injected so [ConfigServerManager] can keep one token across port retries. */
    val sessionToken: String,
) : NanoHTTPD("127.0.0.1", port) {

    companion object {
        private const val TAG = "ConfigServer"
        const val PORT = 9527
        private const val MIME_HTML = "text/html"
        private const val MIME_JSON = "application/json"
        private const val HEADER_AUTH = "authorization"
    }

    private val gson = Gson()

    /** Auth failure throttling, guarded by [authLock]. */
    private val authLock = Any()
    private var authFailures = 0
    private var firstAuthFailureAtMs = 0L

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        // No CORS headers anywhere, so a preflight simply gets a bare 200 and the
        // browser then refuses the cross-origin request — which is the intent.
        if (method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
        }

        return try {
            when {
                // The HTML shell carries no secrets and is what tells the user to
                // enter the code shown on their phone, so it stays unauthenticated.
                (uri == "/" || uri == "/index.html") && method == Method.GET -> serveHtml()
                uri == "/debug.html" && method == Method.GET && BuildConfig.DEBUG -> serveDebugHtml()

                uri.startsWith("/api/") -> requireAuth(session) {
                    when {
                        uri == "/api/channels" && method == Method.GET -> handleGetChannels()
                        uri == "/api/channels" && method == Method.POST -> handlePostChannels(session)
                        uri == "/api/llm" && method == Method.GET -> handleGetLlm()
                        uri == "/api/llm" && method == Method.POST -> handlePostLlm(session)
                        uri == "/api/debug/tools" && method == Method.GET && BuildConfig.DEBUG -> handleGetTools()
                        uri == "/api/debug/execute" && method == Method.POST && BuildConfig.DEBUG -> handleExecuteTool(session)
                        uri == "/api/debug/screen-full" && method == Method.GET && BuildConfig.DEBUG -> handleGetScreenFull()
                        uri.startsWith("/api/debug/file") && method == Method.GET && BuildConfig.DEBUG -> handleServeFile(session)
                        else -> notFound()
                    }
                }

                else -> notFound()
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Server error: ${e.message}")
            // Do not echo the exception message: it can carry file paths or config
            // values, and this response is reachable by any app on the device.
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, MIME_JSON,
                """{"code":-1,"message":"internal error"}"""
            )
        }
    }

    private fun notFound(): Response = newFixedLengthResponse(
        Response.Status.NOT_FOUND, MIME_JSON, """{"code":-1,"message":"not found"}"""
    )

    /**
     * Gate every API route under `/api` on the session token, with throttling so the
     * token cannot be brute-forced by a local process hammering loopback.
     */
    private fun requireAuth(session: IHTTPSession, block: () -> Response): Response {
        val now = System.currentTimeMillis()
        synchronized(authLock) {
            if (ConfigServerPolicy.isAuthLockedOut(authFailures, firstAuthFailureAtMs, now)) {
                XLog.w(TAG, "Auth locked out after $authFailures failures")
                return newFixedLengthResponse(
                    Response.Status.TOO_MANY_REQUESTS, MIME_JSON,
                    """{"code":-1,"message":"too many failed attempts, restart the server from the phone"}"""
                )
            }
        }

        val presented = ConfigServerPolicy.extractToken(session.headers?.get(HEADER_AUTH))
        if (!ConfigServerPolicy.tokensMatch(sessionToken, presented)) {
            synchronized(authLock) {
                val (failures, firstAt) =
                    ConfigServerPolicy.registerAuthFailure(authFailures, firstAuthFailureAtMs, now)
                authFailures = failures
                firstAuthFailureAtMs = firstAt
            }
            XLog.w(TAG, "Rejected unauthenticated request to ${session.uri}")
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, MIME_JSON,
                """{"code":-1,"message":"missing or invalid access code"}"""
            )
        }

        synchronized(authLock) {
            authFailures = 0
            firstAuthFailureAtMs = 0L
        }
        return block()
    }

    private fun serveHtml(): Response {
        val inputStream = context.assets.open("web/index.html")
        val html = inputStream.bufferedReader().use { it.readText() }
        return newFixedLengthResponse(Response.Status.OK, MIME_HTML, html)
    }

    private fun handleGetChannels(): Response {
        // MASKED, never cleartext. A bot token is enough to take over the channel,
        // and POST already round-trips masked values untouched via isMaskedValue().
        val data = JsonObject().apply {
            addProperty("discordBotToken", ConfigServerPolicy.maskSecret(KVUtils.getDiscordBotToken()))
            addProperty("telegramBotToken", ConfigServerPolicy.maskSecret(KVUtils.getTelegramBotToken()))
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
            addProperty("message", "ok")
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString())
    }

    private fun handlePostChannels(session: IHTTPSession): Response {
        // NanoHTTPD requires parseBody before reading POST body
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""

        val json = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"invalid json"}"""
            )
        }

        var reinitDiscord = false
        var reinitTelegram = false

        // Discord config
        if (json.has("discordBotToken")) {
            val value = json.get("discordBotToken").asString
            if (!ConfigServerPolicy.isMaskedValue(value)) {
                KVUtils.setDiscordBotToken(value)
                reinitDiscord = true
            }
        }

        // Telegram config
        if (json.has("telegramBotToken")) {
            val value = json.get("telegramBotToken").asString
            if (!ConfigServerPolicy.isMaskedValue(value)) {
                KVUtils.setTelegramBotToken(value)
                reinitTelegram = true
            }
        }

        // Re-initialize the corresponding channel
        if (reinitDiscord) {
            ChannelManager.reinitDiscordFromStorage()
        }
        if (reinitTelegram) {
            ChannelManager.reinitTelegramFromStorage()
        }

        // Notify Settings page to refresh binding status
        if (reinitDiscord || reinitTelegram) {
            ConfigServerManager.notifyConfigChanged()
        }

        val result = JsonObject().apply {
            addProperty("code", 0)
            addProperty("message", "ok")
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString())
    }

    private fun handleGetLlm(): Response {
        // MASKED. This endpoint used to hand out the API key in cleartext.
        val data = JsonObject().apply {
            addProperty("llmApiKey", ConfigServerPolicy.maskSecret(KVUtils.getLlmApiKey()))
            addProperty("llmBaseUrl", KVUtils.getLlmBaseUrl())
            addProperty("llmModelName", KVUtils.getLlmModelName())
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
            addProperty("message", "ok")
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString())
    }

    private fun handlePostLlm(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""

        val json = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"invalid json"}"""
            )
        }

        if (json.has("llmApiKey")) {
            val value = json.get("llmApiKey").asString
            if (!ConfigServerPolicy.isMaskedValue(value)) {
                KVUtils.setLlmApiKey(value)
            }
        }
        if (json.has("llmBaseUrl")) {
            val value = json.get("llmBaseUrl").asString
            // Writing this unchecked was the worst hole in this class: repointing the
            // agent at an attacker's endpoint hands them every prompt (screen text,
            // notifications, clipboard) plus control of every tool call, turning a
            // config write into persistent device control.
            val rejection = ConfigServerPolicy.llmBaseUrlRejectionReason(value)
            if (rejection != null) {
                XLog.w(TAG, "Rejected unsafe llmBaseUrl")
                val payload = JsonObject().apply {
                    addProperty("code", -1)
                    addProperty("message", rejection)
                }
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, MIME_JSON, payload.toString()
                )
            }
            KVUtils.setLlmBaseUrl(value.trim())
        }
        if (json.has("llmModelName")) {
            val value = json.get("llmModelName").asString.trim()
            KVUtils.setLlmModelName(if (value.isEmpty()) "" else value)
        }

        ConfigServerManager.notifyConfigChanged()

        val result = JsonObject().apply {
            addProperty("code", 0)
            addProperty("message", "ok")
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString())
    }

    // ==================== Debug (DEBUG builds only) ====================
    
    private fun handleGetScreenFull(): Response {
        val service = com.blackclaw.android.service.ClawAccessibilityService.getInstance()
            ?: return newFixedLengthResponse(
                Response.Status.OK, MIME_JSON,
                """{"code":-1,"message":"Accessibility service is not running"}"""
            )
        val tree = service.screenTreeFull
        val data = JsonObject().apply {
            addProperty("success", tree != null)
            addProperty("data", tree ?: "")
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString())
    }

    private fun serveDebugHtml(): Response {
        val inputStream = context.assets.open("web/debug.html")
        val html = inputStream.bufferedReader().use { it.readText() }
        return newFixedLengthResponse(Response.Status.OK, MIME_HTML, html)
    }

    private fun handleGetTools(): Response {
        val tools = ToolRegistry.getAllTools()
        val arr = JsonArray()
        for (tool in tools) {
            val obj = JsonObject().apply {
                addProperty("name", tool.getName())
                addProperty("displayName", tool.getDisplayName())
                addProperty("description", tool.getDescription())
                val params = JsonArray()
                for (p in tool.getParameters()) {
                    params.add(JsonObject().apply {
                        addProperty("name", p.name)
                        addProperty("type", p.type)
                        addProperty("description", p.description)
                        addProperty("required", p.isRequired)
                    })
                }
                add("parameters", params)
            }
            arr.add(obj)
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", arr)
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString())
    }

    private fun handleExecuteTool(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""

        val json = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"invalid json"}"""
            )
        }

        val toolName = json.get("tool")?.asString ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, MIME_JSON,
            """{"code":-1,"message":"missing tool name"}"""
        )

        val params = mutableMapOf<String, Any>()
        try {
            json.getAsJsonObject("params")?.entrySet()?.forEach { (key, value) ->
                when {
                    value.isJsonNull -> {}
                    !value.isJsonPrimitive -> params[key] = value.toString()
                    value.asJsonPrimitive.isNumber -> params[key] = value.asNumber
                    value.asJsonPrimitive.isBoolean -> params[key] = value.asBoolean
                    else -> params[key] = value.asString
                }
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Debug param parse error: ${e.message}")
        }

        XLog.d(TAG, "Debug execute: $toolName params=$params")

        val toolResult = try {
            ToolRegistry.executeTool(toolName, params)
        } catch (e: Exception) {
            XLog.e(TAG, "Debug execute error", e)
            ToolResult.error("Exception: ${e.message}")
        }

        val data = JsonObject().apply {
            addProperty("success", toolResult.isSuccess)
            addProperty("data", toolResult.data)
            addProperty("error", toolResult.error)
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString())
    }

    private fun handleServeFile(session: IHTTPSession): Response {
        val path = session.parms["path"] ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, MIME_JSON,
            """{"code":-1,"message":"missing path param"}"""
        )
        // Confine to the cache dir using CANONICAL paths. The previous check compared
        // absolutePath prefixes, which does not resolve "..", so a path like
        // <cache>/../files/mmkv/mmkv.default passed and served the plaintext key store.
        val file = java.io.File(path)
        if (!file.isFile || !ConfigServerPolicy.isPathContained(context.cacheDir, file)) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_JSON,
                """{"code":-1,"message":"file not found or access denied"}"""
            )
        }
        val mime = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
        return newFixedLengthResponse(Response.Status.OK, mime, file.inputStream(), file.length())
    }
}
