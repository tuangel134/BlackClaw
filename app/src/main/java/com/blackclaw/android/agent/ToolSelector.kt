package com.blackclaw.android.agent

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolRegistry
import com.blackclaw.android.utils.XLog

/**
 * Progressive tool disclosure — the key to fitting inside Groq's 12k tokens/min
 * limit WITHOUT hiding capabilities from the model.
 *
 * The problem: sending all ~85 full tool schemas costs ~13k tokens per request.
 * Function-calling APIs only let the model invoke tools whose full schema is in
 * the request's `tools` array, so we can't just "describe them in text" and hope.
 *
 * The solution (3 layers):
 *   1. CATALOG — a compact one-line-per-tool list injected into the system
 *      prompt (~1.5k tokens). The model SEES every tool that exists, so it's
 *      never blind. It just doesn't get the full parameter schema for all.
 *   2. PRELOAD — we ship the full schema for a relevant subset only: CORE
 *      (navigation/finish) + keyword-matched tools for the task (~15 tools).
 *      Most tasks are fully handled here in one round.
 *   3. request_tool(names) — a meta-tool. If the model wants a tool from the
 *      catalog that isn't preloaded, it calls request_tool("weather") and we
 *      inject that tool's full schema on the next round. The MODEL decides what
 *      it needs; the app doesn't guess. One extra round only when preload missed.
 *
 * This keeps a typical request near ~4-6k tokens while preserving the model's
 * full agency over the toolset.
 */
object ToolSelector {

    private const val TAG = "ToolSelector"

    /** Always preloaded with full schema — the agent's basic loop + termination. */
    val CORE = setOf(
        "get_screen_info", "find_node_info", "tap", "tap_node", "input_text",
        "system_key", "swipe", "scroll_to_find", "find_and_tap", "open_app",
        "wait", "finish", "get_foreground_app", "close_app",
        // request_tool is the unlock mechanism — must always be available.
        "request_tool",
    )

    private val CATEGORIES: List<Category> = listOf(
        Category(
            triggers = listOf("mensaje", "whatsapp", "telegram", "responde", "contesta",
                "message", "reply", "send", "envia", "manda", "escribe a", "auto"),
            tools = listOf("send_message", "auto_reply", "find_contact", "get_notifications"),
        ),
        Category(
            triggers = listOf("sms", "texto", "mensaje de texto", "llama", "llamada",
                "call", "telefono", "teléfono", "marca"),
            tools = listOf("send_sms", "get_sms", "make_call", "get_call_log", "find_contact"),
        ),
        Category(
            triggers = listOf("contacto", "contact", "agenda"),
            tools = listOf("find_contact"),
        ),
        Category(
            triggers = listOf("calendario", "evento", "cita", "calendar", "event", "reunion", "reunión"),
            tools = listOf("get_calendar_events", "create_calendar_event"),
        ),
        Category(
            triggers = listOf("alarma", "alarm", "recordatorio", "reminder", "despierta", "timer", "temporizador"),
            tools = listOf("set_alarm", "schedule_task", "list_scheduled_tasks", "cancel_scheduled_task"),
        ),
        Category(
            triggers = listOf("cron", "programa", "schedule", "cada dia", "cada día", "diariamente",
                "periodic", "repite", "automatiza"),
            tools = listOf("schedule_task", "list_scheduled_tasks", "cancel_scheduled_task"),
        ),
        Category(
            triggers = listOf("volumen", "volume", "brillo", "brightness", "wifi", "bluetooth",
                "linterna", "flashlight", "vibra", "vibrate", "silencio", "ajuste", "setting"),
            tools = listOf("set_volume", "set_brightness", "toggle_setting", "flashlight", "vibrate"),
        ),
        Category(
            triggers = listOf("foto", "camara", "cámara", "camera", "selfie", "graba", "video"),
            tools = listOf("open_camera"),
        ),
        Category(
            triggers = listOf("musica", "música", "reproduce", "play", "pausa", "media", "cancion",
                "canción", "spotify", "siguiente", "anterior"),
            tools = listOf("media_control"),
        ),
        Category(
            triggers = listOf("habla", "di ", "lee en voz", "speak", "voz", "pronuncia", "tts"),
            tools = listOf("speak_text"),
        ),
        Category(
            triggers = listOf("clima", "tiempo", "weather", "temperatura", "lluvia", "pronostico", "pronóstico"),
            tools = listOf("weather", "get_location", "sun_info"),
        ),
        Category(
            triggers = listOf("ubicacion", "ubicación", "donde estoy", "location", "gps", "coordenadas"),
            tools = listOf("get_location"),
        ),
        Category(
            triggers = listOf("internet", "red", "network", "velocidad", "speed", "ping", "ip",
                "conexion", "conexión", "conectividad"),
            tools = listOf("network_speed", "ping_host", "public_ip", "connectivity_check"),
        ),
        Category(
            triggers = listOf("busca en internet", "buscar", "search", "google", "investiga", "web"),
            tools = listOf("web_search", "open_url", "http_fetch"),
        ),
        Category(
            triggers = listOf("traduce", "translate", "idioma", "traducción", "traduccion"),
            tools = listOf("translate"),
        ),
        Category(
            triggers = listOf("convierte", "convert", "unidad", "unit", "moneda", "currency",
                "divisa", "cambio", "color", "calcula", "math", "suma", "resta"),
            tools = listOf("unit_convert", "currency_convert", "color_convert", "math_eval", "count_text"),
        ),
        Category(
            triggers = listOf("archivo", "file", "guarda", "escribe archivo", "lee archivo",
                "save", "qr", "codigo qr", "código qr", "base64", "hash", "json", "regex"),
            tools = listOf("write_file", "read_file", "qr_generate", "base64", "hash_text",
                "json_query", "regex_extract", "url_encode"),
        ),
        Category(
            triggers = listOf("app", "aplicacion", "aplicación", "instala", "desinstala", "uninstall",
                "install", "fuerza", "force", "mata", "kill", "cierra", "acceso directo", "shortcut"),
            tools = listOf("app_info", "uninstall_app", "open_app_settings", "create_shortcut",
                "app_shortcuts", "force_stop_app", "get_installed_apps"),
        ),
        Category(
            triggers = listOf("bateria", "batería", "battery", "memoria", "memory", "ram",
                "dispositivo", "device", "sistema", "info"),
            tools = listOf("power_info", "memory_info", "get_device_info"),
        ),
        Category(
            triggers = listOf("notifica", "notification", "avisa", "notify"),
            tools = listOf("system_notify", "get_notifications"),
        ),
        Category(
            triggers = listOf("comparte", "share", "envia archivo", "envía archivo", "manda archivo"),
            tools = listOf("share_text", "send_file"),
        ),
        Category(
            triggers = listOf("portapapeles", "clipboard", "copia", "pega", "copy", "paste"),
            tools = listOf("clipboard"),
        ),
        Category(
            triggers = listOf("juego", "game", "rapido", "rápido", "shell", "adb", "comando",
                "tap rapido", "dispara", "auto click", "autoclick"),
            tools = listOf("shell_exec", "fast_tap", "fast_swipe", "tap_burst", "force_stop_app",
                "read_screen_ocr", "tap_ocr", "execute_plan"),
        ),
        Category(
            triggers = listOf("pantalla", "screen", "ocr", "lee la pantalla", "captura", "screenshot"),
            tools = listOf("read_screen_ocr", "tap_ocr", "take_screenshot"),
        ),
        Category(
            triggers = listOf("gesto", "pellizca", "pinch", "zoom", "arrastra", "drag", "traza", "patron", "patrón"),
            tools = listOf("pinch", "drag_drop", "path_trace", "long_press", "tap_burst"),
        ),
        Category(
            triggers = listOf("recuerda", "remember", "memoria", "nota", "apunta", "olvida", "forget"),
            tools = listOf("remember_fact", "recall_facts", "forget_fact",
                "kb_write", "kb_read", "kb_search", "kb_append", "kb_add_todo"),
        ),
        Category(
            triggers = listOf("skill", "habilidad", "ejecuta skill"),
            tools = listOf("run_skill"),
        ),
    )

    /**
     * The tools to PRELOAD (full schema) for [task]: CORE + keyword matches.
     * Bounded by [maxTools]. The model can request anything else from the
     * catalog via request_tool.
     */
    fun selectPreloadNames(task: String, maxTools: Int = 22): Set<String> {
        val available = ToolRegistry.getInstance().getAllTools().map { it.getName() }.toSet()
        val lower = task.lowercase()

        val selected = LinkedHashSet<String>()
        CORE.forEach { if (it in available) selected.add(it) }

        for (cat in CATEGORIES) {
            if (cat.triggers.any { lower.contains(it) }) {
                cat.tools.forEach { if (it in available) selected.add(it) }
            }
        }

        if (selected.size <= maxTools) return selected
        // Overshoot: keep CORE then fill up to the cap.
        return LinkedHashSet<String>().apply {
            CORE.forEach { if (it in available) add(it) }
            for (n in selected) { if (size >= maxTools) break; add(n) }
        }
    }

    /**
     * Compact catalog of EVERY registered tool, one line each:
     *   `weather — clima actual y pronóstico por GPS o ciudad`
     * Injected into the system prompt so the model always knows what exists.
     * Tools already preloaded are marked so the model knows it can call them now.
     */
    fun buildCatalog(preloaded: Set<String>): String {
        val sb = StringBuilder()
        sb.append("## Tool catalog (all available tools)\n")
        sb.append("Tools marked [ready] are loaded — call them directly. For any OTHER tool, ")
        sb.append("first call request_tool(names=\"toolname\") to load its full schema, then call it.\n\n")
        for (tool in ToolRegistry.getInstance().getAllTools()) {
            val name = tool.getName()
            if (name == "request_tool") continue
            val mark = if (name in preloaded) " [ready]" else ""
            sb.append("- ").append(name).append(mark).append(" — ").append(tool.getBrief()).append('\n')
        }
        return sb.toString()
    }

    private data class Category(val triggers: List<String>, val tools: List<String>)
}
