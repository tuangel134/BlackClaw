package com.blackclaw.android.agent

import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.R
import com.blackclaw.android.agent.langchain.LangChain4jToolBridge
import com.blackclaw.android.agent.llm.LlmClient
import com.blackclaw.android.agent.llm.LlmClientFactory
import com.blackclaw.android.agent.llm.LlmResponse
import com.blackclaw.android.agent.llm.StreamingListener
import com.blackclaw.android.service.ClawAccessibilityService
import com.blackclaw.android.tool.ToolRegistry
import com.blackclaw.android.tool.impl.GetScreenInfoTool
import com.blackclaw.android.tool.ToolResult
import com.blackclaw.android.utils.XLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.agent.tool.ToolExecutionRequest
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DefaultAgentService : AgentService {

    companion object {
        private const val TAG = "AgentService"
        private val GSON = Gson()

        /**
         * Optimized system prompt for on-device LLM (Gemma 4).
         * Shorter than Cloud prompt but includes essential rules.
         * Task-only — chat is handled separately.
         */
        private const val LOCAL_TASK_PROMPT = """You are a phone assistant. You control an Android phone using tools. The user gave you a task — complete it.

## How to work
1. Call get_screen_info to see what's on screen
2. Decide which tool to use
3. Call the tool
4. Check the result, then decide next step
5. When done, call finish(summary="what you did or found")

## Tool selection guide
- Open an app → open_app(package_name="com.example.app")
- Tap something → tap_node(node_id="n3") or tap(x=500, y=300)
- Tap text inside a game / video / SurfaceView → tap_ocr(text="Play")
- Read text from a game / video / Canvas → read_screen_ocr() (works when get_screen_info is empty)
- Hammer the same spot N times (autoclicker, games, OK chains) → tap_burst(x, y, count=5, interval_ms=30)
- Pinch / zoom (maps, photos, web) → pinch(center_x, center_y, action="in"|"out", amount=400)
- Drag and drop (icons, lists, files) → drag_drop(start_x, start_y, end_x, end_y)
- Trace a path (lock pattern, signature, curved swipe) → path_trace(points="[[x1,y1],[x2,y2],...]")
- Type text → input_text(text="hello") or input_text(text="hello", node_id="n5")
- Press back/home/enter → system_key(key="back")
- Scroll to find something → scroll_to_find(text="Settings")
- Find and tap text → find_and_tap(text="Send")
- Send a message → send_message(contact="Mom", message="hi", app="WhatsApp")
- Make a phone call → make_call(contact="Mom")
- Check battery/wifi/storage/bluetooth/screen/device/time → get_device_info(category="battery")
- Read notifications → get_notifications()
- Read clipboard → clipboard(action="get")
- Write text to clipboard → clipboard(action="set", text="...")
- List installed apps → get_installed_apps()
- Take screenshot → take_screenshot()
- Wait for loading → wait(duration_ms=2000)
- What app am I in? → get_foreground_app()
- Go home / leave app → close_app() (issues HOME)
- Switch between recent apps → show_recents()
- Open URL or deep link → open_url(url="https://...") (also tel:, mailto:, geo:, sms:)
- Search the web → web_search(query="...", engine="google")
- Share text via system share sheet → share_text(text="...")
- Set volume directly → set_volume(level=50, stream="media")
- Set screen brightness → set_brightness(level=80)
- Toggle wifi/bt/airplane/dnd/location → toggle_setting(setting="wifi", state="on")
- Schedule a task or chat for later → schedule_task(text="...", when="in 30m"|"tomorrow 09:00", recurrence="once|daily|hourly|weekly|interval", interval_minutes=N)
- List or cancel scheduled tasks → list_scheduled_tasks() or cancel_scheduled_task(id="abc")
- Remember a fact long-term → remember_fact(key="name", value="Alex")
- Recall remembered facts → recall_facts(query="optional substring")
- Forget a fact → forget_fact(key="name") or forget_fact(key="all")
- Read calendar events → get_calendar_events(hours_ahead=24)
- Add a calendar event → create_calendar_event(title="...", start="tomorrow 09:00", duration_minutes=60)
- Read SMS inbox → get_sms(limit=10, from="optional")
- Send an SMS → send_sms(phone="+34...", message="...", mode="compose")
- Read recent calls → get_call_log(limit=10, type="incoming|outgoing|missed|all")
- Find a contact by name or number → find_contact(query="Mom")
- Set an alarm or timer → set_alarm(mode="alarm", hour=7, minute=30) or set_alarm(mode="timer", duration_seconds=600)
- Open the camera → open_camera(mode="photo|video")
- Speak text aloud (TTS) → speak_text(text="...", language="en-US")
- Control media playback → media_control(action="play|pause|toggle|next|previous|stop")
- Flashlight → flashlight(action="on|off|toggle")
- Vibrate → vibrate(pattern="short|medium|long|double|triple")
- Show system notification → system_notify(title="...", body="...")
- Fetch a URL (HTTPS GET) → http_fetch(url="https://...", accept="application/json")
- Evaluate math → math_eval(expression="(7*8.2)/3 + sqrt(16)")
- Run a saved user skill → run_skill(match="rutina mañana")

## Velocidad: encadena pasos cuando puedas
- Cuando ya conozcas 2-6 acciones seguidas que NO dependen de leer pantalla entre medias, mándalas en UNA sola llamada con execute_plan(steps=...). Ejemplos típicos:
  - Abrir app → wait → tap algo conocido
  - input_text → system_key("enter")
  - 3 swipes seguidos para llegar al fondo de una lista
- Pasos máximo: 6. Si CUALQUIER paso depende de leer la pantalla, NO lo metas en el plan; haz solo lo que ya sabes y luego get_screen_info.
- Para pasos críticos, añade verificación: "verify_text" (texto que debe aparecer en pantalla tras el paso) o "expect" (subcadena que debe traer el resultado del paso). Si falla, el paso se reintenta una vez y, si sigue fallando, el plan se aborta con el detalle. Úsalo p.ej. tras open_app: {"tool":"open_app","params":{...},"verify_text":"Chats"}.

## Rules
- One tool call per turn. Check screen after each action.
- If something doesn't work, try a different approach. After 3 failures, call finish and explain what went wrong.
- finish(summary) must contain the ACTUAL DATA the user asked for. "Battery is at 73%" not "I checked battery."
- Use get_device_info for battery/wifi/storage/bluetooth/screen/device/time queries. Do NOT open Settings app for these.
- Use get_notifications to read notifications. Do NOT pull down notification shade.
- Use clipboard(action="get") ONLY when the user asks about the CURRENT clipboard contents (for example "read my clipboard" or "what did I copy").
- If the user asks you to copy/search/send/summarize information FROM another source (email, browser, notes, messages, screen, etc), first go to that source and find the information there. Do NOT assume it is already on the clipboard.
- If you need the clipboard after finding the source data, use clipboard(action="set", text="...") yourself.
- Use get_installed_apps() when the user asks what apps are installed.
- Use input_text to type. Do NOT tap on autocomplete suggestions.
- Never say you cannot access the user's clipboard, notifications, or phone state when a matching tool exists. Use the tool first.
- For "remind me", "every morning", "tomorrow at 9", "schedule X" → use schedule_task instead of trying to do it now.
- When the user reveals a stable preference about themselves (name, city, work email, default browser, time zone) you may proactively call remember_fact. Reuse the same key to update.
- For volume / brightness / wifi / bluetooth / dnd / airplane → prefer set_volume / set_brightness / toggle_setting over navigating Settings.
- For "set alarm at 7", "10 minute timer" → use set_alarm. For "what's on my calendar" → get_calendar_events.
- When the user says a name (e.g. "call Mom") and you don't know the number, call find_contact first.
- For SMS prefer mode='compose' (user taps Send) unless the user explicitly asks to send silently.
- Do NOT auto-fill passwords, confirm payments, or delete data.
- The 'Ambient state' header above tells you the current time, battery level, and foreground app — use it instead of re-querying."""

        /** Maximum number of retries on LLM API call failure */
        private const val MAX_API_RETRIES = 3

        /** Rate-limit retries are separate and more generous — on the free tier
         *  these are expected and we just wait out the window the API tells us. */
        private const val MAX_RATE_LIMIT_RETRIES = 6

        /**
         * Determines whether a user prompt looks like a phone-control task
         * (should receive a pre-warmed screen snapshot) vs a conversational question.
         *
         * Uses a two-pass approach:
         *  1. Fast regex on common action verbs / device-state nouns (covers ~95% of cases).
         *  2. Explicit exclusion of pure question patterns to avoid false positives.
         */
        private val TASK_VERB_REGEX = Regex(
            """(?i)\b(open|send|tap|search|play|take|install|click|go to|navigate|turn on|turn off|""" +
            """monitor|close|swipe|scroll|compose|find|call|dial|check|read my|show me|""" +
            """set|enable|disable|download|delete|share|copy|paste|type|write|reply|forward|""" +
            """launch|start|stop|cancel|record|capture|scan|upload|attach|move|rename)\b"""
        )
        private val DEVICE_NOUN_REGEX = Regex(
            """(?i)\b(screen|notification|battery|wifi|bluetooth|storage|clipboard|""" +
            """camera|microphone|volume|brightness|airplane mode|hotspot|nfc|gps|location)\b"""
        )
        private val QUESTION_ONLY_REGEX = Regex(
            """(?i)^(what|who|where|when|why|how|is|are|can|could|would|should|do|does|did)\b"""
        )

        fun isTaskLike(prompt: String): Boolean {
            val trimmed = prompt.trim()
            // Pure questions with no action verb are chat, not tasks
            if (QUESTION_ONLY_REGEX.containsMatchIn(trimmed) &&
                !TASK_VERB_REGEX.containsMatchIn(trimmed) &&
                !DEVICE_NOUN_REGEX.containsMatchIn(trimmed)) {
                return false
            }
            return TASK_VERB_REGEX.containsMatchIn(trimmed) ||
                   DEVICE_NOUN_REGEX.containsMatchIn(trimmed)
        }

        /**
         * Opt-3: Action tools — after any of these execute we auto-attach a fresh
         * get_screen_info result so the LLM can see the updated UI without spending
         * an extra inference round (5 s) to call it manually.
         */
        private val ACTION_TOOLS = setOf(
            "phone_click_node", "phone_tap", "phone_swipe", "phone_long_press",
            "tap", "long_press", "swipe", "scroll_to_find",
            "input_text", "type_text", "system_key", "open_app",
            "dpad_up", "dpad_down", "dpad_left", "dpad_right", "dpad_center",
            "volume_up", "volume_down", "press_menu", "press_power",
            "clipboard", "send_file", "repeat_actions", "wait"
        )
        /** ms to wait for UI to settle before capturing screen after an action */
        private const val SCREEN_SETTLE_MS = 500L

        /** Whether to write raw network request/response data to sandbox cache files for debugging */
        @JvmField
        var FILE_LOGGING_ENABLED = false
        @JvmField
        var FILE_LOGGING_CACHE_DIR: File? = null
    }

    private lateinit var config: AgentConfig
    private lateinit var llmClient: LlmClient
    private var toolSpecs: List<dev.langchain4j.agent.tool.ToolSpecification> = emptyList()
    /** Full set built once at init; per-task we narrow this down. */
    private var allToolSpecs: List<dev.langchain4j.agent.tool.ToolSpecification> = emptyList()
    private var executor: ExecutorService? = null
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private var taskFuture: java.util.concurrent.Future<*>? = null

    override fun initialize(config: AgentConfig) {
        this.config = config
        this.llmClient = LlmClientFactory.create(config)
        this.allToolSpecs = LangChain4jToolBridge.buildToolSpecifications()
        this.toolSpecs = allToolSpecs
        this.executor = Executors.newSingleThreadExecutor()
        XLog.i(TAG, "Agent initialized: provider=${config.provider}, model=${config.modelName}, streaming=${config.streaming}")
    }

    override fun updateConfig(config: AgentConfig) {
        if (running.get()) {
            cancel()
            XLog.w(TAG, "Task was running during config update, cancelled")
        }
        executor?.shutdownNow()
        // Close old LlmClient before reinitializing to free engine memory
        if (::llmClient.isInitialized) {
            try {
                llmClient.close()
                XLog.i(TAG, "Old LlmClient closed before config update")
            } catch (e: Exception) {
                XLog.w(TAG, "Old LlmClient close error during config update", e)
            }
        }
        initialize(config)
        XLog.i(TAG, "Agent config updated, new model: ${config.modelName}")
    }

    override fun executeTask(userPrompt: String, callback: AgentCallback) {
        if (running.get()) {
            callback.onError(0, IllegalStateException("Agent is already running a task"), 0)
            return
        }

        running.set(true)
        cancelled.set(false)
        var terminalCallback: (() -> Unit)? = null

        val callbackProxy = object : AgentCallback {
            override fun onLoopStart(round: Int) = callback.onLoopStart(round)

            override fun onContent(round: Int, content: String) = callback.onContent(round, content)

            override fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String) {
                callback.onToolCall(round, toolId, toolName, parameters)
            }

            override fun onToolResult(round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult) {
                callback.onToolResult(round, toolId, toolName, parameters, result)
            }

            override fun onTokenUpdate(status: TokenMonitor.Status) = callback.onTokenUpdate(status)

            override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int, modelName: String?) {
                // Cross-task memory: remember what was asked + a short outcome so the
                // next task can resolve back-references ("again", "same person").
                runCatching { TaskHistoryStore.record(userPrompt, finalAnswer) }
                terminalCallback = { callback.onComplete(round, finalAnswer, totalTokens, modelName) }
            }

            override fun onError(round: Int, error: Exception, totalTokens: Int) {
                terminalCallback = { callback.onError(round, error, totalTokens) }
            }

            override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {
                terminalCallback = { callback.onSystemDialogBlocked(round, totalTokens) }
            }
        }

        taskFuture = executor?.submit {
            try {
                runAgentLoop(userPrompt, callbackProxy)
            } catch (e: Exception) {
                if (terminalCallback == null) {
                    if (cancelled.get()) {
                        XLog.i(TAG, "Agent task cancelled (interrupted)")
                        terminalCallback = {
                            callback.onComplete(0, ClawApplication.instance.getString(R.string.agent_task_cancel), 0)
                        }
                    } else {
                        XLog.e(TAG, "Agent execution error", e)
                        terminalCallback = { callback.onError(0, e, 0) }
                    }
                }
            } finally {
                // Close local engine BEFORE clearing running flag so the chat engine
                // reload (triggered by onComplete/onError) never overlaps with task engine.
                if (::llmClient.isInitialized) {
                    try {
                        llmClient.close()
                        XLog.i(TAG, "LlmClient closed after task completion")
                    } catch (e: Exception) {
                        XLog.w(TAG, "LlmClient close error after task", e)
                    }
                }
                running.set(false)
                val terminal = terminalCallback
                terminalCallback = null
                terminal?.invoke()
            }
        }
    }

    // ==================== Pre-flight Check ====================

    private fun preCheck(): String? {
        if (ClawAccessibilityService.getInstance() == null) {
            return ClawApplication.instance.getString(R.string.agent_accessibility_not_enabled)
        }
        return null
    }

    // ==================== Device Context ====================

    private fun buildDeviceContext(): String {
        val app = ClawApplication.instance
        val sb = StringBuilder()
        sb.append("\n\n## Device Info\n")
        sb.append("- Brand: ").append(Build.BRAND).append("\n")
        sb.append("- Model: ").append(Build.MODEL).append("\n")
        sb.append("- Android Version: ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")

        try {
            val wm = app
                .getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            sb.append("- Screen Resolution: ").append(dm.widthPixels).append("x").append(dm.heightPixels).append("\n")
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to get display metrics", e)
        }

        sb.append("- Registered Tools: ").append(ToolRegistry.getAllTools().size).append("\n")

        val appName = try {
            val appInfo = app.packageManager.getApplicationInfo(app.packageName, 0)
            app.packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) { "BlackClaw" }
        sb.append("\n## This App Info\n")
        sb.append("- App Name: ").append(appName).append("\n")
        sb.append("- Package Name: ").append(app.packageName).append("\n")
        sb.append("- When the user refers to 'this app' or 'the app', they mean the app above.\n")

        return sb.toString()
    }

    // ==================== LLM Call (with retry) ====================

    private fun chatWithRetry(messages: List<ChatMessage>, callback: AgentCallback, iteration: Int): LlmResponse {
        var lastException: Exception? = null
        var attempt = 0
        var rateLimitRetries = 0
        while (attempt < MAX_API_RETRIES) {
            if (cancelled.get()) throw RuntimeException(ClawApplication.instance.getString(R.string.agent_task_cancelled))
            try {
                return if (config.streaming) {
                    val textBuilder = StringBuilder()
                    llmClient.chatStreaming(messages, toolSpecs, object : StreamingListener {
                        override fun onPartialText(token: String) {
                            textBuilder.append(token)
                            callback.onContent(iteration, token)
                        }
                        override fun onComplete(response: LlmResponse) {}
                        override fun onError(error: Throwable) {}
                    })
                } else {
                    llmClient.chat(messages, toolSpecs)
                }
            } catch (e: Exception) {
                lastException = e
                val msg = e.message ?: ""
                // Do not retry on auth failure or true token exhaustion (context too big)
                if (msg.contains("401") || msg.contains("403") ||
                    (msg.contains("insufficient") && !msg.contains("rate"))) {
                    throw e
                }

                // Rate limit: Groq/OpenAI tell us exactly how long to wait
                // ("Please try again in 19.125s"). Honor that instead of a
                // blind backoff, and don't count it against the normal retry
                // budget (these are expected on the free tier).
                val rateLimitWaitMs = parseRateLimitWaitMs(msg)
                if (rateLimitWaitMs != null) {
                    rateLimitRetries++
                    if (rateLimitRetries > MAX_RATE_LIMIT_RETRIES) {
                        XLog.w(TAG, "Rate limit retries exhausted ($MAX_RATE_LIMIT_RETRIES)")
                        throw e
                    }
                    val waitMs = (rateLimitWaitMs + 500).coerceAtMost(60_000L)
                    XLog.w(TAG, "Rate limited; waiting ${waitMs}ms then retrying (rl retry $rateLimitRetries)")
                    callback.onContent(iteration,
                        "\n⏳ Límite de Groq alcanzado, esperando ${(waitMs / 1000)}s para continuar…\n")
                    // Sleep in short chunks so the user can cancel mid-wait.
                    var slept = 0L
                    while (slept < waitMs) {
                        if (cancelled.get()) throw RuntimeException(
                            ClawApplication.instance.getString(R.string.agent_task_cancelled))
                        try {
                            Thread.sleep(500)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt(); throw e
                        }
                        slept += 500
                    }
                    continue  // retry without consuming a normal attempt
                }

                attempt++
                if (attempt >= MAX_API_RETRIES) break
                val delay = (Math.pow(2.0, attempt.toDouble()) * 1000).toLong()
                XLog.w(TAG, "LLM API call failed (attempt $attempt/$MAX_API_RETRIES), retrying in ${delay}ms: $msg")
                try {
                    Thread.sleep(delay)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
        throw lastException!!
    }

    /**
     * Parses the retry-after hint from a rate-limit error message. Returns the
     * wait in milliseconds, or null if this isn't a rate-limit error.
     *
     * Handles Groq/OpenAI style: "Please try again in 19.125s" / "in 1m30s" /
     * "try again in 2.5 seconds", plus the generic 429 marker.
     */
    private fun parseRateLimitWaitMs(msg: String): Long? {
        val lower = msg.lowercase()
        val isRateLimit = lower.contains("rate_limit") || lower.contains("rate limit") ||
            lower.contains("429") || lower.contains("tokens per minute") ||
            lower.contains("requests per minute") || lower.contains("tpm")
        if (!isRateLimit) return null

        // "in 1m30s" or "in 90s" or "in 19.125s"
        val combo = Regex("""in\s+(?:(\d+)m)?\s*([\d.]+)?\s*s""").find(lower)
        if (combo != null) {
            val mins = combo.groupValues[1].toLongOrNull() ?: 0L
            val secs = combo.groupValues[2].toDoubleOrNull() ?: 0.0
            val total = mins * 60_000L + (secs * 1000).toLong()
            if (total > 0) return total
        }
        // "in 2.5 seconds"
        val secOnly = Regex("""in\s+([\d.]+)\s*seconds?""").find(lower)
        if (secOnly != null) {
            val secs = secOnly.groupValues[1].toDoubleOrNull() ?: 0.0
            if (secs > 0) return (secs * 1000).toLong()
        }
        // Rate-limited but no parseable hint → default cool-down.
        return 15_000L
    }

    // ==================== Dead Loop Detection ====================
    // NOTE: Legacy RoundFingerprint loop detection removed.
    // StuckDetector (5-signal, 3-level) is the single source of truth.

    // ==================== Context Compression ====================

    /** Protected zone: keep the most recent N rounds intact.
     *  Local models have shorter context windows → keep fewer rounds to save tokens.
     *  Cloud models: kept moderate. On tight rate limits (e.g. Groq free tier,
     *  12k TPM) fewer rounds means smaller requests, so we keep this lean (3). */
    private val KEEP_RECENT_ROUNDS: Int
        get() = if (config.provider == LlmProvider.LOCAL) 2 else 3

    /** Large-output observation tools → compressed placeholder */
    private val OBSERVATION_PLACEHOLDERS = mapOf(
        "get_screen_info" to "[screen info omitted]",
        "take_screenshot" to "[screenshot result omitted]",
        "find_node_info" to "[node find result omitted]",
        "get_installed_apps" to "[app list omitted]",
        "scroll_to_find" to "[scroll find result omitted]"
    )

    /**
     * Compress history messages before sending to save input tokens:
     * - get_screen_info: keep only the latest complete result globally
     * - Protected zone (most recent KEEP_RECENT_ROUNDS rounds): keep intact
     * - Outside protected zone: keep AI thinking as-is, compress tool results to a one-line summary
     */
    private fun compressHistoryForSend(messages: MutableList<ChatMessage>) {
        // Count total characters before compression
        val charsBefore = messages.sumOf { msg ->
            when (msg) {
                is AiMessage -> (msg.text()?.length ?: 0) + (msg.toolExecutionRequests()?.sumOf { it.arguments()?.length ?: 0 } ?: 0)
                is ToolExecutionResultMessage -> msg.text().length
                is UserMessage -> msg.singleText().length
                is SystemMessage -> msg.text().length
                else -> 0
            }
        }
        val msgCountBefore = messages.size

        // 0. Special handling for get_screen_info: regardless of tier, keep only the latest complete result globally
        val screenPlaceholder = OBSERVATION_PLACEHOLDERS["get_screen_info"]!!
        val lastScreenIdx = messages.indexOfLast {
            it is ToolExecutionResultMessage && it.toolName() == "get_screen_info"
        }
        for (i in messages.indices) {
            val msg = messages[i]
            if (msg is ToolExecutionResultMessage
                && msg.toolName() == "get_screen_info"
                && i != lastScreenIdx
                && msg.text() != screenPlaceholder
            ) {
                messages[i] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), screenPlaceholder)
            }
        }

        // 1. Find indices of all AiMessages; each represents one round
        val aiIndices = messages.indices.filter { messages[it] is AiMessage }
        if (aiIndices.size <= KEEP_RECENT_ROUNDS) return

        val totalRounds = aiIndices.size

        for (roundIdx in aiIndices.indices) {
            val roundFromEnd = totalRounds - roundIdx
            if (roundFromEnd <= KEEP_RECENT_ROUNDS) break // protected zone

            val aiIndex = aiIndices[roundIdx]

            // Collect ToolExecutionResultMessage indices for this round
            var j = aiIndex + 1
            while (j < messages.size && messages[j] is ToolExecutionResultMessage) {
                compressToolResultMessage(messages, j)
                j++
            }
        }

        // Count total characters after compression
        val charsAfter = messages.sumOf { msg ->
            when (msg) {
                is AiMessage -> (msg.text()?.length ?: 0) + (msg.toolExecutionRequests()?.sumOf { it.arguments()?.length ?: 0 } ?: 0)
                is ToolExecutionResultMessage -> msg.text().length
                is UserMessage -> msg.singleText().length
                is SystemMessage -> msg.text().length
                else -> 0
            }
        }
        val saved = charsBefore - charsAfter
        if (saved > 0) {
            XLog.i(TAG, "Context compressed: ${charsBefore}→${charsAfter} chars, saved ${saved} chars (${saved * 100 / charsBefore}%), rounds=${aiIndices.size}")
        }
    }

    /** Compress Tool Result: use placeholder for observation tools, truncate summary for others */
    private fun compressToolResultMessage(messages: MutableList<ChatMessage>, index: Int) {
        val msg = messages[index] as ToolExecutionResultMessage
        val text = msg.text()
        if (text.length <= 100) return // already short enough, no need to compress

        val placeholder = OBSERVATION_PLACEHOLDERS[msg.toolName()]
        if (placeholder != null) {
            messages[index] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), placeholder)
            return
        }

        // Other tools: parse JSON to extract a summary
        val compressed = summarizeToolResult(text)
        messages[index] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), compressed)
    }

    /** Compress ToolResult JSON into a one-line summary */
    private fun summarizeToolResult(resultJson: String): String {
        return try {
            val mapType = object : TypeToken<Map<String, Any?>>() {}.type
            val map: Map<String, Any?> = GSON.fromJson(resultJson, mapType)
            val isSuccess = map["isSuccess"] as? Boolean ?: false
            if (isSuccess) {
                val data = map["data"]?.toString() ?: "ok"
                "✓ " + if (data.length > 80) data.take(80) + "..." else data
            } else {
                val error = map["error"]?.toString() ?: "failed"
                "✗ " + if (error.length > 80) error.take(80) + "..." else error
            }
        } catch (_: Exception) {
            if (resultJson.length > 80) resultJson.take(80) + "..." else resultJson
        }
    }

    // ==================== Main Execution Loop ====================

    private fun runAgentLoop(userPrompt: String, callback: AgentCallback) {
        // Pre-flight check
        preCheck()?.let {
            callback.onError(0, RuntimeException(it), 0)
            return
        }

        val parsedPrompt = TaskPromptEnvelope.parse(userPrompt)
        val rawUserRequest = parsedPrompt.currentRequest

        // ── Progressive tool disclosure (token optimization for cloud) ──
        // Sending all ~85 tool schemas costs ~13k tokens/request and blows past
        // Groq's rate limit. Instead we PRELOAD a relevant subset (full schema)
        // and show the FULL catalog as compact text in the prompt; the model can
        // request_tool(...) to load anything else. Disabled for LOCAL models.
        val progressiveDisclosure = config.provider != LlmProvider.LOCAL
        val activeToolNames = LinkedHashSet<String>()
        var toolCatalogSection = ""
        if (progressiveDisclosure) {
            activeToolNames.addAll(ToolSelector.selectPreloadNames(rawUserRequest))
            toolSpecs = LangChain4jToolBridge.buildToolSpecifications(activeToolNames)
                .ifEmpty { allToolSpecs }
            toolCatalogSection = "\n\n" + ToolSelector.buildCatalog(activeToolNames)
            XLog.i(TAG, "runAgentLoop: preloaded ${toolSpecs.size}/${allToolSpecs.size} tools + catalog")
        } else {
            toolSpecs = allToolSpecs
        }

        // Build System Prompt — use optimized prompt for local LLM
        val basePrompt = if (config.provider == LlmProvider.LOCAL) {
            LOCAL_TASK_PROMPT
        } else {
            config.systemPrompt
        }

        val inAppSearchGuard = InAppSearchGuard.fromTask(rawUserRequest)
        val emailComposeGuard = EmailComposeGuard.fromTask(rawUserRequest)
        val directDeviceDataGuard = DirectDeviceDataGuard.fromTask(rawUserRequest)

        // For local LLM, inject matching playbook into system prompt
        val playbookSection = if (config.provider == LlmProvider.LOCAL) {
            val matched = PlaybookManager.match(rawUserRequest)
            if (matched != null) {
                XLog.i(TAG, "Playbook matched: ${matched.id} for '$rawUserRequest'")
                "\n\n## Playbook: ${matched.name}\nFollow these steps exactly:\n\n${matched.body}"
            } else ""
        } else ""

        val fullSystemPrompt = buildString {
            append(basePrompt)
            append(playbookSection)
            append(inAppSearchGuard.buildPromptSection())
            append(emailComposeGuard.buildPromptSection())
            append(directDeviceDataGuard.buildPromptSection())
            append(buildDeviceContext())
            append(AmbientContext.asPromptSection())
            append(TaskHistoryStore.asPromptSnippet())
            append(toolCatalogSection)
        }

        // Each task starts with a fresh tool cache so we never serve stale state.
        ToolRegistry.getInstance().clearCache()

        val messages = mutableListOf<ChatMessage>()
        messages.add(SystemMessage.from(fullSystemPrompt))

        val promptForModel = if (parsedPrompt.hasChatHistory || parsedPrompt.hasBackgroundState) {
            buildString {
                append("You are continuing an existing chatroom. Use the provided context when the current request refers to earlier messages or asks about current background activity.\n\n")
                parsedPrompt.backgroundState?.trim()?.takeIf { it.isNotEmpty() }?.let { state ->
                    append("Current background status:\n")
                    append(state)
                    append("\n\n")
                }
                parsedPrompt.chatHistory?.trim()?.takeIf { it.isNotEmpty() }?.let { history ->
                    append("Chatroom so far:\n")
                    append(history)
                    append("\n\n")
                }
                append("Current user request:\n")
                append(rawUserRequest)
            }
        } else {
            rawUserRequest
        }

        // Opt-2: Pre-warm — only attach screen info for task-like prompts.
        // Chat/questions should NOT see screen data (it confuses the LLM into using tools).
        val looksLikeTask = isTaskLike(rawUserRequest)

        val enrichedPrompt = if (looksLikeTask) {
            try {
                val screenTool = ToolRegistry.getInstance().getTool("get_screen_info")
                if (screenTool != null) {
                    val screenResult = screenTool.execute(emptyMap())
                    if (screenResult.isSuccess && !screenResult.data.isNullOrBlank()) {
                        XLog.i(TAG, "runAgentLoop: pre-warm screen attached (${screenResult.data!!.length} chars)")
                        "$promptForModel\n\nCurrent screen:\n${screenResult.data}"
                    } else promptForModel
                } else promptForModel
            } catch (e: Exception) { promptForModel }
        } else {
            XLog.i(TAG, "runAgentLoop: chat-like prompt, skipping pre-warm screen")
            promptForModel
        }
        messages.add(UserMessage.from(enrichedPrompt))

        var iterations = 0
        var totalTokens = 0
        var actualModelName: String? = null  // Track the real model name from API response
        val maxIterations = config.maxIterations
        var lastScreenHash = 0
        var previousScreenTexts: Set<String> = emptySet()
        val tokenMonitor = TokenMonitor(config.modelName)
        val stuckDetector = StuckDetector()
        val taskBudget = TaskBudget.fromSettings()
        var softLimitWarned = false
        var consecutiveNoToolCalls = 0

        while (iterations < maxIterations && !cancelled.get()) {
            iterations++
            callback.onLoopStart(iterations)

            // Compress history messages before sending to save tokens
            compressHistoryForSend(messages)

            // LLM call (with retry)
            val llmResponse: LlmResponse
            try {
                llmResponse = chatWithRetry(messages, callback, iterations)
            } catch (e: Exception) {
                XLog.e(TAG, "LLM API call failed after retries", e)
                callback.onError(iterations, RuntimeException(ClawApplication.instance.getString(R.string.agent_api_call_failed, e.message)), totalTokens)
                return
            }

            if (cancelled.get()) {
                callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens, actualModelName)
                return
            }

            // Capture actual model name from first API response
            if (actualModelName == null && !llmResponse.modelName.isNullOrEmpty()) {
                actualModelName = llmResponse.modelName
                XLog.d(TAG, "runAgentLoop: actual model from API = $actualModelName")
            }
            // Accumulate token usage
            llmResponse.tokenUsage?.totalTokenCount()?.let { totalTokens += it }
            tokenMonitor.record(
                step = iterations,
                inputTokens = llmResponse.tokenUsage?.inputTokenCount(),
                outputTokens = llmResponse.tokenUsage?.outputTokenCount(),
                totalTokenCount = llmResponse.tokenUsage?.totalTokenCount()
            )
            callback.onTokenUpdate(tokenMonitor.getStatus())

            // Budget check
            val tokenStatus = tokenMonitor.getStatus()
            when (taskBudget.check(tokenStatus.totalTokens, tokenStatus.estimatedCostUsd)) {
                TaskBudget.Status.HARD_LIMIT -> {
                    XLog.w(TAG, "Budget HARD LIMIT reached at step $iterations: ${tokenStatus.formattedTokens} (${tokenStatus.formattedCost})")
                    callback.onComplete(
                        iterations,
                        "Task stopped: budget limit reached (${tokenStatus.formattedTokens} tokens, ${tokenStatus.formattedCost}). " +
                        "Increase budget in Settings if needed.",
                        totalTokens,
                        actualModelName
                    )
                    return
                }
                TaskBudget.Status.SOFT_LIMIT -> {
                    if (!softLimitWarned) {
                        softLimitWarned = true
                        XLog.i(TAG, "Budget SOFT LIMIT at step $iterations: ${tokenStatus.formattedTokens}")
                        messages.add(UserMessage.from(
                            "[System Notice] You are using ${tokenStatus.formattedTokens} tokens (${tokenStatus.formattedCost}), " +
                            "approaching the budget limit. Finish the task efficiently. " +
                            "If you cannot complete it soon, call finish with a partial summary."
                        ))
                    }
                }
                TaskBudget.Status.OK -> { /* continue normally */ }
            }

            // DEBUG: log raw LLM response for tool calling diagnosis
            XLog.i(TAG, "runAgentLoop iter=$iterations response.text=${llmResponse.text?.take(500)}")
            XLog.i(TAG, "runAgentLoop iter=$iterations hasToolCalls=${llmResponse.hasToolExecutionRequests()} toolCallCount=${llmResponse.toolExecutionRequests?.size ?: 0}")

            // Add AI message to history (must construct AiMessage)
            val aiMessage = if (llmResponse.hasToolExecutionRequests()) {
                if (llmResponse.text.isNullOrEmpty()) {
                    AiMessage.from(llmResponse.toolExecutionRequests)
                } else {
                    AiMessage.from(llmResponse.text, llmResponse.toolExecutionRequests)
                }
            } else {
                AiMessage.from(llmResponse.text ?: "")
            }
            messages.add(aiMessage)

            // Push thinking content in non-streaming mode
            if (!config.streaming && !llmResponse.text.isNullOrEmpty()) {
                val suppressHallucinatedCompletion =
                    !llmResponse.hasToolExecutionRequests() &&
                        (inAppSearchGuard.shouldBlockTextOnlyCompletion() ||
                            emailComposeGuard.shouldBlockTextOnlyCompletion())
                if (!suppressHallucinatedCompletion) {
                    callback.onContent(iterations, llmResponse.text)
                }
            }

            // No tool calls in this response — LLM chose to respond with text only.
            // Respect that. If there's text, it's the answer. Done.
            if (!llmResponse.hasToolExecutionRequests()) {
                val responseText = llmResponse.text ?: ""
                if (responseText.isNotEmpty()) {
                    if (inAppSearchGuard.shouldBlockTextOnlyCompletion()) {
                        val correction = inAppSearchGuard.buildCompletionCorrection()
                        XLog.i(TAG, "InAppSearchGuard blocked text-only completion for '$userPrompt'")
                        messages.add(UserMessage.from(correction))
                        continue
                    }
                    if (directDeviceDataGuard.shouldBlockTextOnlyCompletion()) {
                        val correction = directDeviceDataGuard.buildCompletionCorrection()
                        XLog.i(TAG, "DirectDeviceDataGuard blocked text-only completion for '$userPrompt'")
                        messages.add(UserMessage.from(correction))
                        continue
                    }
                    if (emailComposeGuard.shouldBlockTextOnlyCompletion()) {
                        val correction = emailComposeGuard.buildCompletionCorrection()
                        XLog.i(TAG, "EmailComposeGuard blocked text-only completion for '$userPrompt'")
                        messages.add(UserMessage.from(correction))
                        continue
                    }
                    XLog.i(TAG, "runAgentLoop: text-only response, completing")
                    callback.onComplete(iterations, responseText, totalTokens, actualModelName)
                    return
                }
                // Empty response with no tools — something went wrong, finish
                XLog.w(TAG, "runAgentLoop: empty response with no tools, finishing")
                callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_completed), totalTokens, actualModelName)
                continue
            }

            // Reset counter when LLM does use tools
            consecutiveNoToolCalls = 0

            // Execute tool calls
            for (toolRequest in llmResponse.toolExecutionRequests) {
                if (cancelled.get()) {
                    callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens, actualModelName)
                    return
                }

                val toolName = toolRequest.name() ?: ""
                val displayName = ToolRegistry.getInstance().getDisplayName(toolName)
                val toolArgs = toolRequest.arguments() ?: "{}"

                // Parse parameters
                val mapType = object : TypeToken<Map<String, Any>>() {}.type
                var params: Map<String, Any>? = try {
                    GSON.fromJson(toolArgs, mapType)
                } catch (e: Exception) {
                    XLog.w(TAG, "Failed to parse tool args for $toolName: $toolArgs", e)
                    HashMap()
                }
                if (params == null) params = HashMap()

                val blockedFinish = if (toolName == "finish") {
                    val screenInfo = try {
                        ToolRegistry.getInstance()
                            .getTool("get_screen_info")
                            ?.execute(emptyMap())
                            ?.takeIf { it.isSuccess }
                            ?.data
                    } catch (_: Exception) {
                        null
                    }
                    directDeviceDataGuard.maybeBlockFinish()
                        ?: inAppSearchGuard.maybeBlockFinish(screenInfo)
                        ?: emailComposeGuard.maybeBlockFinish(screenInfo)
                } else null
                if (blockedFinish != null) {
                    val blockedResult = ToolResult.error(blockedFinish)
                    XLog.i(TAG, "Task guard blocked premature finish for '$userPrompt'")
                    callback.onToolCall(iterations, toolName, displayName, toolArgs)
                    callback.onToolResult(iterations, toolName, displayName, params.toString(), blockedResult)
                    messages.add(ToolExecutionResultMessage.from(toolRequest, GSON.toJson(blockedResult)))
                    messages.add(UserMessage.from(blockedFinish))
                    continue
                }

                callback.onToolCall(iterations, toolName, displayName, toolArgs)
                directDeviceDataGuard.recordToolAttempt(toolName)
                emailComposeGuard.recordToolAttempt(toolName)

                // Soft destructive-action guard. We never silently block; we surface
                // an error result so the LLM can self-correct or request confirmation
                // through user-visible text instead of executing the dangerous call.
                val risk = ActionGuard.assess(toolName, params)
                if (risk == ActionGuard.Risk.DESTRUCTIVE) {
                    val reason = ActionGuard.describe(risk, toolName)
                    XLog.w(TAG, "ActionGuard blocked $toolName: $reason")
                    val blocked = ToolResult.error(
                        "Refused: this action looks destructive ($reason). " +
                        "Confirm with the user in plain text first, then retry only if they agree."
                    )
                    callback.onToolResult(iterations, toolName, displayName, params.toString(), blocked)
                    messages.add(ToolExecutionResultMessage.from(toolRequest, GSON.toJson(blocked)))
                    continue
                }

                val result = ToolRegistry.getInstance().executeTool(toolName, params)
                val paramsString = if (params.isEmpty()) "" else params.toString()
                callback.onToolResult(iterations, toolName, displayName, paramsString, result)
                if (result.isSuccess) {
                    inAppSearchGuard.recordSuccessfulTool(toolName, params)
                    emailComposeGuard.recordSuccessfulTool(toolName)
                }

                // Progressive disclosure: when the model loads tools via
                // request_tool, add their full schemas to the active set so the
                // next chatWithRetry call exposes them.
                if (toolName == "request_tool" && result.isSuccess && progressiveDisclosure) {
                    val requested = (params["names"]?.toString() ?: "")
                        .split(",", " ", ";").map { it.trim() }.filter { it.isNotEmpty() }
                    val newlyAdded = requested.filter {
                        ToolRegistry.getInstance().getTool(it) != null && activeToolNames.add(it)
                    }
                    if (newlyAdded.isNotEmpty()) {
                        toolSpecs = LangChain4jToolBridge.buildToolSpecifications(activeToolNames)
                        XLog.i(TAG, "request_tool unlocked ${newlyAdded.joinToString()} → ${toolSpecs.size} active tools")
                    }
                }

                // System dialog blocking detected → notify user and stop task
                if (!result.isSuccess && result.error == GetScreenInfoTool.SYSTEM_DIALOG_BLOCKED) {
                    XLog.w(TAG, "System dialog blocked, notifying user and stopping task")
                    callback.onSystemDialogBlocked(iterations, totalTokens)
                    return
                }

                // finish tool → task complete
                if (toolName == "finish" && result.isSuccess) {
                    val finishData = result.data
                    callback.onComplete(iterations, finishData ?: ClawApplication.instance.getString(R.string.agent_task_completed), totalTokens, actualModelName)
                    return
                }

                // Opt-3: Auto-attach fresh screen state after action tools.
                // LLM sees updated UI in the same tool result → can decide next step
                // immediately without spending an extra 5 s inference round on get_screen_info.
                val combinedResultData: String = if (toolName in ACTION_TOOLS) {
                    try {
                        Thread.sleep(SCREEN_SETTLE_MS) // let UI animate/settle
                        val screenTool = ToolRegistry.getInstance().getTool("get_screen_info")
                        val screenAfter = screenTool?.execute(emptyMap())
                        if (screenAfter != null && screenAfter.isSuccess && !screenAfter.data.isNullOrBlank()) {
                            // Update lastScreenHash for loop detection
                            lastScreenHash = screenAfter.data!!.hashCode()
                            XLog.i(TAG, "Opt3: auto-attached screen after $toolName (${screenAfter.data!!.length} chars)")
                            // Screen diff: extract text lines and compare with previous
                            val currentTexts = screenAfter.data!!.lines()
                                .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                            val added = currentTexts - previousScreenTexts
                            val removed = previousScreenTexts - currentTexts
                            previousScreenTexts = currentTexts
                            val diffSection = buildString {
                                if (added.isNotEmpty()) append("\nNew on screen: ${added.take(10).joinToString(", ")}")
                                if (removed.isNotEmpty()) append("\nGone from screen: ${removed.take(10).joinToString(", ")}")
                            }
                            val baseData = result.data ?: ""
                            val enrichedData = "$baseData\n\nScreen after action:\n${screenAfter.data}$diffSection"
                            val enriched = if (result.isSuccess) ToolResult.success(enrichedData)
                                           else ToolResult.error(result.error ?: "")
                            GSON.toJson(enriched)
                        } else {
                            XLog.w(TAG, "Opt3: get_screen_info failed after $toolName: ${screenAfter?.error}")
                            GSON.toJson(result)
                        }
                    } catch (e: Exception) {
                        XLog.w(TAG, "Opt3: exception fetching screen after $toolName", e)
                        GSON.toJson(result)
                    }
                } else {
                    // Record fingerprint for dead-loop detection (non-action tools path)
                    if (toolName == "get_screen_info" && result.isSuccess && result.data != null) {
                        lastScreenHash = result.data.hashCode()
                    }
                    GSON.toJson(result)
                }

                // Add tool result to messages
                messages.add(ToolExecutionResultMessage.from(toolRequest, combinedResultData))
                XLog.d(TAG, "displayName:$displayName toolName:$toolName")
            }

            // Stuck detection (5-signal, 3-level recovery)
            val lastAction = llmResponse.toolExecutionRequests?.firstOrNull()?.let {
                "${it.name()}:${it.arguments()?.take(50)}"
            } ?: ""
            val screenDiffCount = (previousScreenTexts as? Set<*>)?.size ?: 0
            val toolError = llmResponse.toolExecutionRequests?.firstOrNull()?.let { req ->
                val result = ToolRegistry.getInstance().getTool(req.name() ?: "")
                null // error tracked per-tool above; simplified here
            }
            val detection = stuckDetector.record(lastAction, lastScreenHash, screenDiffCount, null)
            if (detection != null) {
                when (detection.level) {
                    StuckDetector.RecoveryLevel.AUTO_KILL -> {
                        XLog.w(TAG, "StuckDetector AUTO_KILL at iteration $iterations: ${detection.signal.description}")
                        val status = tokenMonitor.getStatus()
                        callback.onComplete(
                            iterations,
                            "Task stopped: agent was stuck (${detection.signal.description}). " +
                            "Used ${status.formattedTokens} tokens (${status.formattedCost}).",
                            totalTokens,
                            actualModelName
                        )
                        return
                    }
                    else -> {
                        XLog.w(TAG, "StuckDetector ${detection.level} at iteration $iterations: ${detection.signal.description}")
                        messages.add(UserMessage.from(detection.recoveryHint))
                    }
                }
            }
            XLog.d(TAG, "Round:$iterations total=$totalTokens thisRound=${llmResponse.tokenUsage?.totalTokenCount()}")
        }

        if (cancelled.get()) {
            callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens, actualModelName)
        } else {
            callback.onError(iterations, RuntimeException(ClawApplication.instance.getString(R.string.agent_max_iterations, maxIterations)), totalTokens)
        }
    }

    override fun cancel() {
        cancelled.set(true)
        if (config.provider == LlmProvider.LOCAL) {
            // LiteRT native sendMessage is not interrupt-safe; let the current round yield
            // naturally, then surface Task cancelled after the client closes cleanly.
            XLog.i(TAG, "cancel: LOCAL task marked cancelled; waiting for current LiteRT round to finish safely")
            return
        }
        // Cloud/network-backed tasks can be aborted safely via thread interruption.
        taskFuture?.cancel(true)
        XLog.i(TAG, "cancel: flag set + thread interrupted")
    }

    override fun shutdown() {
        cancel()
        executor?.shutdownNow()
        if (::llmClient.isInitialized) {
            try {
                llmClient.close()
                XLog.i(TAG, "LlmClient closed on shutdown")
            } catch (e: Exception) {
                XLog.w(TAG, "LlmClient close error on shutdown", e)
            }
        }
    }

    override fun isRunning(): Boolean = running.get()
}
