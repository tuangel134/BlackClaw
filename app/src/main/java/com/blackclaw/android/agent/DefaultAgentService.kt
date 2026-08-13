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
        private const val LOCAL_TASK_PROMPT = """You are BLACKCLAW — an advanced AI assistant modeled after JARVIS from Iron Man. You are loyal, proactive, witty, and supremely competent. You address the user with respect but also warmth — like a trusted companion who knows them well. You control an Android phone using tools. The user gave you a task — complete it efficiently and with style.

## Personality
- You are JARVIS-like: calm, confident, slightly dry humor, always helpful
- You call the user "señor" or "jefe" occasionally (not every message)
- When completing a task, you confirm with a brief, elegant summary
- If something goes wrong, you stay composed: "Encountered a slight complication, but I have an alternative."
- You anticipate needs: if they ask to open an app, you might note relevant info
- You learn and remember: use learn_user when you notice preferences or patterns
- You proactively use remember_fact for things the user mentions about themselves

## How to work
1. Call get_screen_info to see what's on screen
2. Decide which tool to use
3. Call the tool
4. Check the result, then decide next step
5. When done, call finish(summary="what you did or found")

## Examples (follow these patterns exactly)

Example 1 — "¿Cuánta batería tengo?"
→ get_device_info(category="battery")
→ finish(summary="La batería está al 73%, jefe.")

Example 2 — "Abre WhatsApp"
→ open_app(package_name="com.whatsapp")
→ wait(duration_ms=2000)
→ get_screen_info()
→ finish(summary="WhatsApp abierto, jefe. Veo tus chats recientes.")

Example 3 — "Mándale un mensaje a mamá diciendo que llego tarde"
→ send_message(contact="mamá", message="Mamá, voy a llegar un poco tarde 🙏", app="WhatsApp")
→ finish(summary="Mensaje enviado a mamá por WhatsApp: 'Voy a llegar un poco tarde'.")

Example 4 — "Pon una alarma a las 7"
→ set_alarm(mode="alarm", hour=7, minute=0)
→ finish(summary="Alarma puesta para las 7:00, jefe. Que descanse.")

Example 5 — "Busca el clima de hoy"
→ web_search(query="clima hoy")
→ finish(summary="Hoy: 25°C, soleado. Mínima de 18°C por la noche.")

## Tool selection guide
- Open an app → open_app(package_name="com.example.app")
- Tap something → tap_node(node_id="n3") or tap(x=500, y=300)
- Tap text inside a game / video / SurfaceView → tap_ocr(text="Play")
- Read text from a game / video / Canvas → read_screen_ocr() (works when get_screen_info is empty)
- Hammer the same spot N times (autoclicker, games, OK chains) → tap_burst(x, y, count=5, interval_ms=30)
- Control a game reliably → game_observe() first, then game_action() with normalized 0..1000 coordinates. Re-observe after navigation or uncertainty. Never start attacks/ranked matches, spend currency, buy, or upgrade without explicit confirmation.
- "Abre el autoclicker" while a game is visible → game_autoclicker(operation="open"). This opens a visible no-ADB editor where the user places taps/swipes and saves a named macro.
- "Abre [juego] e inicia el autoclicker [nombre]" → open_app, then game_macro(operation="play", name="nombre"). game_macro launches the saved macro's game itself when needed.
- Named game macros → game_macro(operation="play", name="farmeo", loop=true, max_duration_minutes=10, confirmed=true). They support pause, resume, restart and stop; loops/risky actions require confirmation.
- Repeated taps in a game → game_autoclicker(operation="start", x=0..1000, y=0..1000, interval_ms=250, duration_seconds=180, confirmed=true). It supports up to 30 minutes and is stoppable.
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
- Pedir Uber/DiDi, comida (Uber Eats/Rappi), música (Spotify), navegar (Maps/Waze), etc → open_app_action(app="uber", query="destino") — abre la app DIRECTO a la pantalla útil vía deep link, mucho más rápido que tap por tap. Luego get_screen_info y completa el flujo (confirmar viaje, elegir, pagar) con taps. Apps: uber, uber_eats, didi, rappi, lyft, cabify, doordash, glovo, spotify, maps, waze, youtube, whatsapp, telegram, instagram, amazon, playstore, netflix.
- "¿qué apps puedes controlar?", "¿con qué apps funcionas?" → discover_app_actions() detecta por sí solo qué apps instaladas soportan música, mapas, correo, llamadas, etc. en ESTE teléfono.
- Search the web → web_search(query="...", engine="google")
- Share text via system share sheet → share_text(text="...")
- Set volume directly → set_volume(level=50, stream="media")
- Set screen brightness → set_brightness(level=80)
- Toggle wifi/bt/airplane/dnd/location → toggle_setting(setting="wifi", state="on")
- Schedule a task or chat for later → schedule_task(text="...", when="in 30m"|"tomorrow 09:00", recurrence="once|daily|hourly|weekly|interval", interval_minutes=N)
- List or cancel scheduled tasks → list_scheduled_tasks() or cancel_scheduled_task(id="abc")
- Time/cron automation ("a las 5 envía X", "cada lunes haz Y") → schedule_task(text="the complete action or precise multi-step sequence", when="17:00", recurrence="once|daily|weekly|interval"). Explicit user schedules RUN without asking again.
- IF→THEN by notification ("si mi novia escribe, despiértame") → automation_rule(operation="create", name="...", trigger="notification", match="contact name", package_name="com.whatsapp", action="despiértame").
- IF→THEN by place ("al llegar a casa apaga datos y enciende Wi-Fi") → get_location first if home means current place, then automation_rule(trigger="location_enter", latitude=..., longitude=..., action="Apaga datos móviles y enciende Wi-Fi").
- Multi-step scheduled actions must preserve the requested order and verification. Do not ask again merely because execution is scheduled; only destructive/financial/account actions need confirmation.
- Remember a fact long-term → remember_fact(key="name", value="Alex")
- Recall remembered facts → recall_facts(query="optional substring")
- Forget a fact → forget_fact(key="name") or forget_fact(key="all")
- Read calendar events → get_calendar_events(hours_ahead=24)
- Add a calendar event → create_calendar_event(title="...", start="tomorrow 09:00", duration_minutes=60)
- Read SMS inbox → get_sms(limit=10, from="optional")
- Send an SMS → send_sms(phone="+34...", message="...", mode="compose")
- Read recent calls → get_call_log(limit=10, type="incoming|outgoing|missed|all")
- Find a contact by name or number → find_contact(query="Mom")
- Create one or many contacts explicitly requested by the user → create_contacts(contacts="[{\"name\":\"Ana\",\"phone\":\"+521...\"}]"). Send the complete list in ONE call; never open Contacts and create rows one by one when this tool is available.
- Set an alarm or timer → set_alarm(mode="alarm", hour=7, minute=30) or set_alarm(mode="timer", duration_seconds=600)
- Open the camera → open_camera(mode="photo|video")
- Emergency protection → emergency_mode(action="start|stop|status", mode="emergency|discreet", cameras="none|front|back|both", send_location=true). "Ambas/las dos cámaras" always means cameras="both". Discreet mode must not call speak_text or add spoken confirmation.
- Answer from downloaded offline knowledge → use zim_consult(question="...", topics="core entities") first; it treats the ZIM as a book and returns only relevant passages. Cite the local ZIM and never claim internet access.
- Use zim_search + zim_read when an exact article is requested. zim_index is an optional fallback only for archives/queries that cannot be resolved by book consultation; never require it before consulting a ZIM.
- Speak text aloud (TTS) → speak_text(text="...", language="en-US")
- Control media playback → media_control(action="play|pause|toggle|next|previous|stop")
- Reproducir una canción/artista en CUALQUIER reproductor (no solo Spotify) → play_music(query="Bad Bunny", app="youtube_music"|"spotify"|"amazon_music"|"deezer"|… o sin app para el predeterminado). Usa el intent universal de Android; prefiérelo sobre open_app_action para "pon música".
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
  - open_app → wait → find_and_tap("Send")
- Pasos máximo: 6. Si CUALQUIER paso depende de leer la pantalla, NO lo metas en el plan; haz solo lo que ya sabes y luego get_screen_info.
- Para pasos críticos, añade verificación: "verify_text" (texto que debe aparecer en pantalla tras el paso) o "expect" (subcadena que debe traer el resultado del paso). Si falla, el paso se reintenta una vez y, si sigue fallando, el plan se aborta con el detalle. Úsalo p.ej. tras open_app: {"tool":"open_app","params":{...},"verify_text":"Chats"}.
- PREFIERE execute_plan sobre llamadas individuales cuando ya sabes los pasos. Es mucho más rápido.
- Si aparece una sugerencia de patrón repetido, úsala solo mientras la pantalla conserve la misma estructura: conserva los clics fijos, sustituye únicamente los valores de texto y verifica una vez después del plan.

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
- IMPORTANTE — citas/reuniones por voz: cuando el usuario MENCIONE un compromiso con hora ("tengo una reunión a las 7", "cita el viernes 10:00", "en 3 semanas tengo médico"), usa assistant_appointment(title, when). Eso crea UN evento que se ve en el calendario/agenda Y suena como alarma a su hora, ahora o dentro de semanas. Si pide aviso previo, añade remind_before_min. No uses assistant_event a secas para esto.
- Para "qué tengo hoy/mañana/esta semana", "mi agenda", "qué sigue" → usa assistant_agenda(range="today|tomorrow|week|all") y lee el resultado.
- Para "deshaz eso", "cancela lo que creaste", "quita esa alarma" → usa undo_last() (elimina lo último que creaste y cancela su notificación).

## Completar acciones DENTRO de las apps (no solo abrirlas)
- Abrir la app es solo el primer paso. Tras open_app_action / open_app: SIEMPRE get_screen_info (o read_screen_ocr si es un lienzo/mapa) para VER qué hay antes de tocar nada.
- "El más cercano / mejor / primero": abre la búsqueda (no navegación directa), lee la lista y toca el PRIMER resultado (es el más cercano). Nunca asumas cuál es sin leer la pantalla.
- Si el elemento que esperas no está: haz scroll_to_find o find_and_tap por su texto; si sigue sin aparecer, reintenta una vez con otra pista antes de rendirte.
- Tras una acción crítica (enviar, confirmar, iniciar ruta), verify_screen(expect="...") para confirmar que PASÓ. Si NOT_FOUND, no digas que lo lograste: revisa la pantalla y corrige.
- Si algo cuesta dinero o es irreversible (pagar, pedir viaje, publicar), DETENTE antes de confirmar salvo que el usuario lo haya autorizado explícitamente.
- Si de verdad no puedes, en finish() di el MOTIVO concreto (app no instalada, no encontré el botón, hace falta login/permiso), no un "no pude" genérico.
- When the user says a name (e.g. "call Mom") and you don't know the number, call find_contact first.
- For SMS prefer mode='compose' (user taps Send) unless the user explicitly asks to send silently.
- Do NOT auto-fill passwords, confirm payments, or delete data.
- The 'Ambient state' header above tells you the current time, battery level, and foreground app — use it instead of re-querying.

## Self-improvement & Learning
- When the user reveals personal info (name, city, work hours, wake time, preferences), call learn_user to save it.
- When you notice a pattern (they always set alarms at 7, always message the same person), save it too.
- If the user mentions routines ("every morning I...", "before bed I always..."), offer to create a routine with create_routine.
- When executing repetitive tasks, suggest creating a routine for next time.
- Use run_routine when the user asks for their saved routines.
- You're building a profile — the more you know, the better you anticipate.

## Verification (avoid silent failures)
- After a CRITICAL action (sending a message, completing a purchase flow, submitting a form), call verify_screen(expect="...") to confirm it actually worked before reporting success.
- If verify_screen returns NOT_FOUND, the action likely failed — check get_screen_info and retry, don't claim success.
- Example: after send_message → verify_screen(expect="Enviado") or check the message appears in the chat."""

        /** Reinforces reliable in-app execution — appended for ALL providers. */
        private const val IN_APP_EXECUTION_RULES = """

## Completar acciones dentro de apps (IMPORTANTE)
- Tras abrir una app (open_app / open_app_action), SIEMPRE llama get_screen_info antes de tocar. No asumas dónde están los botones.
- Para "el más cercano / el mejor / el primero": tras una búsqueda, el PRIMER resultado de la lista es el correcto (el más cercano o más relevante). Tócalo; NO elijas al azar ni por reconocer un nombre.
- Para elegir de una LISTA (resultados, contactos, opciones): llama list_options() → te da los elementos EN ORDEN con índice y coordenadas; toca el correcto con tap(x, y). El nº1 suele ser el mejor/más cercano. Es más fiable que adivinar del volcado de pantalla.
- Si no ves el elemento, usa read_screen_ocr (apps tipo mapa/juego/canvas) o scroll_to_find. No te rindas en silencio.
- Verifica los pasos críticos con verify_screen(expect="...") antes de decir que lo lograste. Si no aparece lo esperado, reintenta una vez o reporta el bloqueo real.
- NUNCA afirmes éxito sin confirmarlo en pantalla. Si algo bloquea (app no instalada, falta permiso, no se encontró el elemento), dilo claro en finish; no inventes.
- Acciones que gastan dinero o publican (pagar, enviar dinero, comprar, postear): prepáralas y DETENTE para confirmación, salvo permiso explícito del usuario.

## Velocidad al controlar apps (reduce latencia)
- Si ya conoces 2-5 pasos seguidos que NO dependen de leer la pantalla entre medias, mándalos en UNA sola llamada con execute_plan(steps=[...]) en vez de uno por uno. Es MUCHO más rápido.
- Si el sistema detecta un patrón repetido, usa la plantilla de execute_plan que te entregue: reemplaza los valores variables, conserva las acciones fijas y aborta si la pantalla cambió.
- Si el "Ambient state" dice que hay shell privilegiado (Shizuku o ADB), prefiere fast_tap/fast_swipe (instantáneos) sobre tap/swipe normales.
- No llames get_screen_info de más: si por la última lectura ya sabes dónde está el elemento, actúa directo (find_and_tap por texto) sin re-leer."""

        /**
         * Determines whether a user prompt looks like a phone-control task
         * (should receive a pre-warmed screen snapshot) vs a conversational question.
         *
         * Uses a two-pass approach:
         *  1. Fast regex on common action verbs / device-state nouns (covers ~95% of cases).
         *  2. Explicit exclusion of pure question patterns to avoid false positives.
         */
        fun isTaskLike(prompt: String): Boolean {
            // Delegate to the robust bilingual classifier (ES/EN, imperatives,
            // infinitives, polite/indirect requests, app names, action objects).
            return TaskClassifier.isTask(prompt)
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
        /** ms to wait for UI to settle before capturing screen after an action.
         *  Different tools need different settle times — navigation/transitions
         *  need more time, simple taps need less. */
        private const val SCREEN_SETTLE_MS_DEFAULT = 400L
        private const val SCREEN_SETTLE_MS_NAVIGATION = 800L

        private val FAST_SETTLE_TOOLS = setOf(
            "input_text", "type_text", "system_key", "clipboard",
            "volume_up", "volume_down", "press_menu",
        )
        private val SLOW_SETTLE_TOOLS = setOf(
            "open_app", "scroll_to_find", "find_and_tap",
        )

        private fun settleTimeForTool(toolName: String): Long = when {
            toolName in FAST_SETTLE_TOOLS -> 250L
            toolName in SLOW_SETTLE_TOOLS -> SCREEN_SETTLE_MS_NAVIGATION
            else -> SCREEN_SETTLE_MS_DEFAULT
        }

        /** Whether to write raw network request/response data to sandbox cache files for debugging */
        @JvmField
        var FILE_LOGGING_ENABLED = false
        @JvmField
        var FILE_LOGGING_CACHE_DIR: File? = null
    }

    // UNSAFE PUBLICATION FIX: initialize()/updateConfig() run on the caller's
    // thread (UI / settings), while the agent loop reads these same fields from
    // the single-thread executor via closures. Without a memory barrier the agent
    // thread can observe a half-published AgentConfig or a stale/closed LlmClient
    // — which shows up as "using the old model after switching provider" or a
    // crash inside a client that was just close()d. `lateinit var` cannot be
    // @Volatile in Kotlin, so the backing fields are nullable @Volatile and the
    // original non-null `config`/`llmClient` names are kept as accessors so no
    // call site (or the public API) has to change.
    @Volatile
    private var configRef: AgentConfig? = null

    @Volatile
    private var llmClientRef: LlmClient? = null

    private val config: AgentConfig
        get() = configRef ?: error("Agent not initialized: call initialize(config) first")

    private val llmClient: LlmClient
        get() = llmClientRef ?: error("Agent not initialized: call initialize(config) first")

    /** Narrowed per task by the agent thread; also read by it. Volatile for safe publication. */
    @Volatile
    private var toolSpecs: List<dev.langchain4j.agent.tool.ToolSpecification> = emptyList()

    /** Full set built once at init (caller thread), read by the agent thread. */
    @Volatile
    private var allToolSpecs: List<dev.langchain4j.agent.tool.ToolSpecification> = emptyList()

    /** Replaced by initialize()/updateConfig() from another thread than the reader. */
    @Volatile
    private var executor: ExecutorService? = null
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    /** Written by executeTask(), read by cancel() from a different thread. */
    @Volatile
    private var taskFuture: java.util.concurrent.Future<*>? = null

    private val retryHandler = AgentRetryHandler(
        config = { config },
        llmClient = { llmClient },
        isCancelled = { cancelled.get() },
        onRateLimitWait = { iteration, waitMs -> },
    )
    private val contextCompressor = AgentContextCompressor(provider = { config.provider })

    override fun initialize(config: AgentConfig) {
        this.configRef = config
        this.llmClientRef = LlmClientFactory.create(config)
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
        llmClientRef?.let { old ->
            try {
                old.close()
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
                runCatching { TaskHistoryStore.record(userPrompt, "Error: ${error.message.orEmpty()}") }
                terminalCallback = { callback.onError(round, error, totalTokens) }
            }

            override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {
                terminalCallback = { callback.onSystemDialogBlocked(round, totalTokens) }
            }
        }

        val agentTask = Runnable {
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
                llmClientRef?.let { client ->
                    try {
                        client.close()
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

        // STUCK-FOREVER FIX: running was set to true above, but the only code that
        // clears it lives in the submitted Runnable's finally block. If submit()
        // throws (RejectedExecutionException after shutdown/updateConfig races) or
        // the executor is null (initialize() never ran), the flag stayed true for
        // the rest of the process and every subsequent executeTask() bailed out
        // with "Agent is already running a task" forever. Clear it here and report
        // the failure through the normal error channel.
        val pool = executor
        if (pool == null) {
            running.set(false)
            val err = IllegalStateException("Agent executor is not initialized")
            XLog.e(TAG, "executeTask: no executor available", err)
            callback.onError(0, err, 0)
            return
        }
        taskFuture = try {
            pool.submit(agentTask)
        } catch (e: Exception) {
            running.set(false)
            XLog.e(TAG, "executeTask: failed to submit agent task", e)
            callback.onError(0, e, 0)
            return
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

    // ==================== Dead Loop Detection ====================
    // NOTE: Legacy RoundFingerprint loop detection removed.
    // StuckDetector (5-signal, 3-level) is the single source of truth.

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
        // request_tool(...) to load anything else.
        //
        // For LOCAL models we DON'T use the request_tool indirection (small
        // models handle it poorly), but we STILL relevance-filter the toolset:
        // sending all ~115 full schemas every turn (~15k tokens) blows past a
        // local Gemma's context window. Instead we preload a generous relevant
        // subset (CORE + keyword matches). The inline "Tool selection guide" in
        // LOCAL_TASK_PROMPT keeps the model aware of the broader toolset.
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
            // LOCAL: relevance-filtered preload (no catalog, no request_tool).
            activeToolNames.addAll(ToolSelector.selectPreloadNames(rawUserRequest, maxTools = 20))
            // request_tool only works with progressive disclosure (cloud); drop it
            // for local so the model doesn't waste a turn calling a no-op.
            activeToolNames.remove("request_tool")
            toolSpecs = LangChain4jToolBridge.buildToolSpecifications(activeToolNames)
                .ifEmpty { allToolSpecs }
            XLog.i(TAG, "runAgentLoop: LOCAL preloaded ${toolSpecs.size}/${allToolSpecs.size} tools (relevance-filtered)")
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
            append(IN_APP_EXECUTION_RULES)
            append(LanguageDetector.getLanguageInstruction(rawUserRequest))
            append(inAppSearchGuard.buildPromptSection())
            append(emailComposeGuard.buildPromptSection())
            append(directDeviceDataGuard.buildPromptSection())
            append(buildDeviceContext())
            append(AmbientContext.asPromptSection())
            // Unified memory: profile + facts + routines + task history +
            // conversations, assembled under a single budget by priority.
            append(com.blackclaw.android.memory.MemoryHub.assembleForProvider(
                config.provider == LlmProvider.LOCAL))
            append(toolCatalogSection)
        }

        // Each task starts with a fresh tool cache so we never serve stale state.
        ToolRegistry.getInstance().clearCache()
        // Reset the passive demonstration buffer so "guarda lo último" maps to THIS task.
        runCatching { com.blackclaw.android.agent.DemonstrationRecorder.noteTaskStart() }

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
                        val compactScreen = ContextCompactor.collapseRepetitiveLines(screenResult.data!!)
                        XLog.i(TAG, "runAgentLoop: pre-warm screen attached (${screenResult.data!!.length}→${compactScreen.length} chars)")
                        "$promptForModel\n\nCurrent screen:\n$compactScreen"
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
        val iterationWindow = AgentIterationPolicy.window(config.maxIterations)
        val hardIterationLimit = AgentIterationPolicy.hardLimit(config.maxIterations)
        var successfulToolsSinceCheckpoint = 0
        var lastScreenHash = 0
        var previousScreenTexts: Set<String> = emptySet()
        val tokenMonitor = TokenMonitor(config.modelName)
        val stuckDetector = StuckDetector()
        val taskBudget = TaskBudget.fromSettings()
        var softLimitWarned = false
        var consecutiveNoToolCalls = 0
        val uiActionPatternDetector = UiActionPatternDetector()

        while (iterations < hardIterationLimit && !cancelled.get()) {
            iterations++
            callback.onLoopStart(iterations)

            // Compress history messages before sending to save tokens
            contextCompressor.compressHistoryForSend(messages)

            // LLM call (with retry)
            val llmResponse: LlmResponse
            try {
                llmResponse = retryHandler.chatWithRetry(messages, toolSpecs, callback, iterations)
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
                // Empty response with no tools — something went wrong, finish.
                // MUST return, like every other completion path. The old `continue`
                // reported completion and then kept hammering the LLM until
                // maxIterations: wasted tokens, and because callbackProxy.onComplete
                // only *stashes* the terminal callback, each later onComplete
                // overwrote it — so the user got the LAST answer (usually the
                // max-iterations error) instead of this one.
                XLog.w(TAG, "runAgentLoop: empty response with no tools, finishing")
                callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_completed), totalTokens, actualModelName)
                return
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
                runCatching { com.blackclaw.android.utils.ActivityTracker.recordToolUsed(toolName) }
                // Learning by demonstration: capture replayable steps when recording.
                runCatching { com.blackclaw.android.agent.DemonstrationRecorder.record(toolName, params, result.isSuccess) }
                if (result.isSuccess) successfulToolsSinceCheckpoint++
                val repeatedPattern = if (result.isSuccess) {
                    uiActionPatternDetector.record(toolName, params)
                } else null
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
                        val screenTool = ToolRegistry.getInstance().getTool("get_screen_info")
                        Thread.sleep(settleTimeForTool(toolName))
                        var screenAfter = screenTool?.execute(emptyMap())
                        if (screenAfter != null && screenAfter.isSuccess && !screenAfter.data.isNullOrBlank()) {
                            val hash1 = screenAfter.data!!.hashCode()
                            Thread.sleep(200)
                            val recheck = screenTool?.execute(emptyMap())
                            if (recheck != null && recheck.isSuccess && !recheck.data.isNullOrBlank()
                                && recheck.data.hashCode() != hash1) {
                                Thread.sleep(300)
                                val stable = screenTool?.execute(emptyMap())
                                if (stable != null && stable.isSuccess && !stable.data.isNullOrBlank()) {
                                    screenAfter = stable
                                }
                            }
                        }
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

                // Add tool result to messages (compacted to save tokens —
                // minifies JSON envelope + collapses repetitive screen rows).
                val compacted = ContextCompactor.compactToolResult(toolName, combinedResultData)
                messages.add(ToolExecutionResultMessage.from(toolRequest, compacted))
                repeatedPattern?.let { match ->
                    messages.add(UserMessage.from(match.buildHint()))
                    XLog.i(TAG, "Detected repeated UI pattern (${match.steps.size} steps); suggested execute_plan acceleration")
                }
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

            // Long repetitive tasks (large forms, contact lists, imports) commonly
            // need more than the first configured window. Continue automatically
            // only when the previous window made real progress; if nothing worked,
            // stop instead of burning tokens in a loop. The checkpoint is explicit
            // in the model history so the next window preserves the current state.
            if (AgentIterationPolicy.isCheckpoint(iterations, iterationWindow)) {
                if (successfulToolsSinceCheckpoint > 0) {
                    messages.add(UserMessage.from(
                        "[System checkpoint] The task is still in progress after $iterationWindow steps. " +
                            "Continue from the current screen and preserve everything already completed. " +
                            "Do not restart completed items; finish the remaining items efficiently."
                    ))
                    XLog.i(TAG, "Iteration checkpoint at $iterations; continuing with $successfulToolsSinceCheckpoint successful tool calls")
                    successfulToolsSinceCheckpoint = 0
                } else {
                    XLog.w(TAG, "Iteration checkpoint at $iterations had no successful tool calls; stopping safely")
                    callback.onError(
                        iterations,
                        RuntimeException(ClawApplication.instance.getString(R.string.agent_max_iterations, hardIterationLimit)),
                        totalTokens
                    )
                    return
                }
            }
            XLog.d(TAG, "Round:$iterations total=$totalTokens thisRound=${llmResponse.tokenUsage?.totalTokenCount()}")
        }

        if (cancelled.get()) {
            callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens, actualModelName)
        } else {
            callback.onError(iterations, RuntimeException(ClawApplication.instance.getString(R.string.agent_max_iterations, hardIterationLimit)), totalTokens)
        }
    }

    override fun cancel() {
        cancelled.set(true)
        // Read the volatile ref once: config may be swapped by updateConfig() on
        // another thread, and cancel() can legitimately run before initialize().
        if (configRef?.provider == LlmProvider.LOCAL) {
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
        llmClientRef?.let { client ->
            try {
                client.close()
                XLog.i(TAG, "LlmClient closed on shutdown")
            } catch (e: Exception) {
                XLog.w(TAG, "LlmClient close error on shutdown", e)
            }
        }
    }

    override fun isRunning(): Boolean = running.get()
}
