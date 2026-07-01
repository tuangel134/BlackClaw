package com.blackclaw.android.agent

import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import com.blackclaw.android.utils.XLog

/**
 * Tier 1: Deterministic task parser.
 * Matches user input against regex patterns to resolve tasks that can be
 * handled with a direct Android intent — zero LLM calls.
 *
 * Tier 1 deterministic parser with regex patterns.
 * Handles ~30% of tasks with 0 LLM calls, < 1 second.
 */
object TaskParser {

    private const val TAG = "TaskParser"

    data class ParseResult(
        val action: String,
        val intent: Intent?,
        val toolName: String? = null,
        val toolParams: Map<String, Any>? = null,
        val description: String = ""
    )

    /**
     * Try to parse a task into a direct action.
     * Returns null if no pattern matches (falls through to Tier 2).
     */
    fun parse(task: String, installedPackages: List<String> = emptyList()): ParseResult? {
        val lower = task.lowercase().trim()

        return matchCall(lower, task)
            ?: matchSendMessage(lower, task)
            ?: matchSms(lower, task)
            ?: matchAlarm(lower, task)
            ?: matchTimer(lower, task)
            ?: matchScreenshot(lower)
            ?: matchBackHome(lower)
            ?: matchOpenUrl(lower, task)
            ?: matchOpenSettings(lower)
            ?: matchPlayMusicEs(esn(lower), task)
            ?: matchNavigateEs(esn(lower), task)
            ?: matchRideEs(esn(lower), task)
            ?: matchOpenAppEs(esn(lower), task)
            ?: matchOpenApp(lower, task, installedPackages)
    }

    /** Strip Spanish accents so tildes/typos don't break fast-path matching. */
    private fun esn(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(when (c) {
            'á' -> 'a'; 'é' -> 'e'; 'í' -> 'i'; 'ó' -> 'o'; 'ú' -> 'u'; 'ü' -> 'u'; 'ñ' -> 'n'
            else -> c
        })
        return sb.toString()
    }

    // ── Spanish fast-paths (0 LLM calls) ──

    /** Compound tasks (2+ actions) must go to the agent, not a single fast action. */
    private fun isCompoundEs(lower: String): Boolean =
        lower.contains(" y luego ") || lower.contains(" y despues ") ||
        lower.contains(" y después ") || lower.contains(" luego ") ||
        lower.contains(" después ") || lower.contains(" despues ") ||
        lower.contains(" and ") || lower.contains(" then ")

    private val MUSIC_EXCLUDE = listOf(
        "alarma", "recordatorio", "recuerdame", "recuérdame", "avisame", "avísame",
        "temporizador", "timer", "nota", "evento", "cita", "reunion", "reunión",
        "brillo", "volumen", "wifi", "bluetooth", "linterna", "modo", "no molestar",
        "atencion", "atención",
    )

    private val PLAY_MUSIC_PATTERN = Regex(
        """^(?:reproduce|pon\s+música|pon\s+musica|ponme\s+música|ponme\s+musica|""" +
        """pon\s+la\s+canción|pon\s+la\s+cancion|pon\s+algo\s+de|pon)\s+(.+)""",
        RegexOption.IGNORE_CASE
    )

    private fun matchPlayMusicEs(lower: String, original: String): ParseResult? {
        if (isCompoundEs(lower)) return null
        if (MUSIC_EXCLUDE.any { lower.contains(it) }) return null
        val m = PLAY_MUSIC_PATTERN.find(lower) ?: return null
        var q = m.groupValues[1].trim()
        // Strip leftover leaders and play-verbs ("música de X" → "X",
        // "reproduce música" → "música", "pon algo de X" → "X").
        q = q.removePrefix("reproduce ").removePrefix("reproducir ")
            .removePrefix("pon ").removePrefix("ponme ")
            .removePrefix("música de ").removePrefix("musica de ")
            .removePrefix("la canción ").removePrefix("la cancion ")
            .removePrefix("algo de ")
            .removePrefix("música ").removePrefix("musica ").trim()
        // Generic "just play music" (no specific song/artist) → empty query so the
        // player starts/resumes playback instead of literally searching "música".
        val generic = setOf(
            "musica", "música", "music", "algo", "una cancion", "una canción",
            "cancion", "canción", "canciones", "unas canciones", "play",
            "reproduce", "reproducir", "algo de musica", "algo de música",
        )
        if (q.isBlank() || q in generic) {
            return ParseResult(
                action = "play_music", intent = null,
                toolName = "play_music", toolParams = mapOf("query" to ""),
                description = "Reproduciendo música"
            )
        }
        if (q.length < 2) return null
        return ParseResult(
            action = "play_music", intent = null,
            toolName = "play_music", toolParams = mapOf("query" to q),
            description = "Reproduciendo '$q'"
        )
    }

    private val NAVIGATE_PATTERN = Regex(
        """^(?:llévame|llevame|navega\s+a|navégame\s+a|navegame\s+a|cómo\s+llego\s+a|""" +
        """como\s+llego\s+a|ruta\s+a|indícame\s+cómo\s+llegar\s+a)\s+(?:al?\s+|la\s+|los\s+)?(.+)""",
        RegexOption.IGNORE_CASE
    )

    private fun matchNavigateEs(lower: String, original: String): ParseResult? {
        if (isCompoundEs(lower)) return null
        val m = NAVIGATE_PATTERN.find(lower) ?: return null
        val dest = m.groupValues[1].trim()
        if (dest.isBlank() || dest.length < 2) return null
        val app = if (lower.contains("waze")) "waze" else "maps"
        return ParseResult(
            action = "navigate", intent = null,
            toolName = "open_app_action", toolParams = mapOf("app" to app, "query" to dest),
            description = "Navegando a $dest"
        )
    }

    private val RIDE_PATTERN = Regex(
        """(?:pídeme|pideme|pide|consígueme|consigueme|solicita)\s+un\s+""" +
        """(uber|taxi|didi|cabify|bolt|lyft)\s*(?:a|hasta|para|al|hacia)?\s*(.*)""",
        RegexOption.IGNORE_CASE
    )

    private fun matchRideEs(lower: String, original: String): ParseResult? {
        if (isCompoundEs(lower)) return null
        val m = RIDE_PATTERN.find(lower) ?: return null
        val kind = m.groupValues[1].lowercase()
        val app = if (kind == "taxi") "uber" else kind
        val dest = m.groupValues[2].trim().trim('.', ',')
        val params = if (dest.isNotBlank() && dest.length >= 2)
            mapOf("app" to app, "query" to dest) else mapOf("app" to app)
        return ParseResult(
            action = "ride", intent = null,
            toolName = "open_app_action", toolParams = params,
            description = if (dest.isNotBlank()) "Pidiendo $app a $dest" else "Abriendo $app"
        )
    }

    private val OPEN_APP_ES_PATTERN = Regex(
        """^(?:abre|abrir|ábreme|abreme|inicia|arranca)\s+(?:la\s+|el\s+)?(?:app\s+|aplicación\s+)?(.+?)(?:\s+app)?$""",
        RegexOption.IGNORE_CASE
    )

    private fun matchOpenAppEs(lower: String, original: String): ParseResult? {
        if (isCompoundEs(lower)) return null
        // "abre los ajustes/configuración" handled by matchOpenSettings already.
        if (lower.contains("ajuste") || lower.contains("configuracion") ||
            lower.contains("configuración")) return null
        val m = OPEN_APP_ES_PATTERN.find(lower) ?: return null
        val appName = m.groupValues[1].trim()
        if (appName.isBlank() || appName.length < 2) return null
        return ParseResult(
            action = "open_app", intent = null,
            toolName = "open_app", toolParams = mapOf("app_name" to appName),
            description = "Abriendo $appName"
        )
    }

    // ==================== Pattern Matchers ====================

    private val CALL_PATTERN = Regex(
        """(?:call|phone|ring|dial|打電話|打畀|致電)\s+(.+)""", RegexOption.IGNORE_CASE
    )

    private fun matchCall(lower: String, original: String): ParseResult? {
        val match = CALL_PATTERN.find(lower) ?: return null
        val target = match.groupValues[1].trim()
        // Check if target looks like a phone number
        val numberMatch = Regex("""[\d\s\-+()]{7,}""").find(target)
        return if (numberMatch != null) {
            val number = numberMatch.value.replace(Regex("""[\s\-()]"""), "")
            ParseResult(
                action = "call",
                intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")),
                description = "Dialing $number"
            )
        } else {
            // Contact name — fall through to agent loop which can open Phone,
            // search for the contact, and dial. "call" is in looksLikeTask
            // so agent loop will get screen context.
            null
        }
    }

    private val SEND_MESSAGE_PATTERN = Regex(
        """(?:send|message|text)\s+(.+?)\s+to\s+(.+?)(?:\s+on\s+([\p{L}\p{N} ._-]+))?$""",
        RegexOption.IGNORE_CASE
    )

    private fun matchSendMessage(lower: String, original: String): ParseResult? {
        if (lower.contains("email")) return null
        val match = SEND_MESSAGE_PATTERN.find(original.trim()) ?: return null
        val message = match.groupValues[1].trim().trim('"', '\'')
        val contact = match.groupValues[2].trim().trim('"', '\'')
        val app = canonicalMessagingApp(match.groupValues.getOrNull(3))

        if (message.isBlank() || contact.isBlank()) return null
        if (contact.contains("@")) return null

        val contextual = setOf("that", "this", "it", "them", "above", "summary", "token")
        val messageTokens = message.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (messageTokens.any { it in contextual }) return null

        return ParseResult(
            action = "send_message",
            intent = null,
            toolName = "send_message",
            toolParams = mapOf(
                "contact" to contact,
                "message" to message,
                "app" to app,
            ),
            description = "Sending '$message' to $contact via $app"
        )
    }

    private fun canonicalMessagingApp(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return "WhatsApp"
        return when (value.lowercase()) {
            "wa", "whatsapp", "whats app" -> "WhatsApp"
            "telegram", "tg" -> "Telegram"
            "sms", "message", "messages", "android messages", "google messages" -> "Messages"
            else -> value
        }
    }

    private val SMS_PATTERN = Regex(
        """(?:sms|text|message|send.*(?:sms|text)|發短訊|發信息)\s+(?:to\s+)?(.+)""", RegexOption.IGNORE_CASE
    )

    private fun matchSms(lower: String, original: String): ParseResult? {
        val match = SMS_PATTERN.find(lower) ?: return null
        val target = match.groupValues[1].trim()
        val numberMatch = Regex("""[\d\s\-+()]{7,}""").find(target)
        return if (numberMatch != null) {
            val number = numberMatch.value.replace(Regex("""[\s\-()]"""), "")
            ParseResult(
                action = "sms",
                intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")),
                description = "Opening SMS to $number"
            )
        } else null
    }

    private val ALARM_PATTERN = Regex(
        """(?:set|create)?\s*(?:alarm|鬧鐘|叫醒|wake\s*(?:me\s*)?up)\s*(?:at|for)?\s*(\d{1,2})[:\s]?(\d{2})?\s*(am|pm)?""",
        RegexOption.IGNORE_CASE
    )

    private fun matchAlarm(lower: String, original: String): ParseResult? {
        val match = ALARM_PATTERN.find(lower) ?: return null
        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        val ampm = match.groupValues[3].lowercase()
        if (ampm == "pm" && hour < 12) hour += 12
        if (ampm == "am" && hour == 12) hour = 0

        return ParseResult(
            action = "alarm",
            intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            },
            description = "Setting alarm for ${String.format("%02d:%02d", hour, minute)}"
        )
    }

    private val TIMER_PATTERN = Regex(
        """(?:set|start)?\s*(?:timer|countdown|計時)\s*(?:for)?\s*(\d+)\s*(second|sec|minute|min|hour|hr|s|m|h)""",
        RegexOption.IGNORE_CASE
    )

    private fun matchTimer(lower: String, original: String): ParseResult? {
        val match = TIMER_PATTERN.find(lower) ?: return null
        val amount = match.groupValues[1].toIntOrNull() ?: return null
        val unit = match.groupValues[2].lowercase()
        val seconds = when {
            unit.startsWith("h") -> amount * 3600
            unit.startsWith("m") -> amount * 60
            else -> amount
        }

        return ParseResult(
            action = "timer",
            intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            },
            description = "Setting timer for $amount ${match.groupValues[2]}"
        )
    }

    private fun matchScreenshot(lower: String): ParseResult? {
        if (!lower.contains("screenshot") && !lower.contains("screencap") &&
            !lower.contains("截圖") && !lower.contains("影相")) return null
        return ParseResult(
            action = "screenshot",
            intent = null,
            toolName = "take_screenshot",
            toolParams = emptyMap(),
            description = "Taking a screenshot"
        )
    }

    private fun matchBackHome(lower: String): ParseResult? {
        return when {
            lower == "go back" || lower == "back" || lower == "返回" ->
                ParseResult("back", null, "system_key", mapOf("key" to "back"), "Going back")
            lower == "go home" || lower == "home" || lower == "返回主頁" ->
                ParseResult("home", null, "system_key", mapOf("key" to "home"), "Going home")
            else -> null
        }
    }

    private val URL_PATTERN = Regex(
        """(?:open|go\s*to|visit|navigate\s*to|打開)\s+(https?://\S+)""", RegexOption.IGNORE_CASE
    )

    private fun matchOpenUrl(lower: String, original: String): ParseResult? {
        val match = URL_PATTERN.find(original) ?: return null
        val url = match.groupValues[1].trim()
        return ParseResult(
            action = "open_url",
            intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)),
            description = "Opening $url"
        )
    }

    private val SETTINGS_KEYWORDS = mapOf(
        "wifi" to "android.settings.WIFI_SETTINGS",
        "bluetooth" to "android.settings.BLUETOOTH_SETTINGS",
        "display" to "android.settings.DISPLAY_SETTINGS",
        "brightness" to "android.settings.DISPLAY_SETTINGS",
        "sound" to "android.settings.SOUND_SETTINGS",
        "volume" to "android.settings.SOUND_SETTINGS",
        "battery" to "android.intent.action.POWER_USAGE_SUMMARY",
        "storage" to "android.settings.INTERNAL_STORAGE_SETTINGS",
        "location" to "android.settings.LOCATION_SOURCE_SETTINGS",
        "airplane" to "android.settings.AIRPLANE_MODE_SETTINGS",
        "notification" to "android.settings.APP_NOTIFICATION_SETTINGS",
        "accessibility" to "android.settings.ACCESSIBILITY_SETTINGS",
    )

    private fun matchOpenSettings(lower: String): ParseResult? {
        if (!lower.contains("settings") && !lower.contains("設定")) return null

        // Check for specific settings keywords
        for ((keyword, action) in SETTINGS_KEYWORDS) {
            if (lower.contains(keyword)) {
                return ParseResult(
                    action = "open_settings",
                    intent = Intent(action),
                    description = "Opening $keyword settings"
                )
            }
        }

        // Generic "open settings"
        if (lower.matches(Regex(".*(?:open|go to|打開)\\s*(?:the\\s*)?settings.*"))) {
            return ParseResult(
                action = "open_settings",
                intent = Intent(android.provider.Settings.ACTION_SETTINGS),
                description = "Opening Settings"
            )
        }

        return null
    }

    private val OPEN_APP_PATTERN = Regex(
        """(?:open|launch|start|打開|開)\s+(?:the\s+)?(.+?)(?:\s+app)?$""", RegexOption.IGNORE_CASE
    )

    private fun matchOpenApp(lower: String, original: String, installedPackages: List<String>): ParseResult? {
        val match = OPEN_APP_PATTERN.find(lower) ?: return null
        val appName = match.groupValues[1].trim()

        // Don't match if the task has more complexity (e.g., "open YouTube and search for cats")
        if (lower.contains(" and ") || lower.contains(" then ") || lower.contains("，然後")) return null

        return ParseResult(
            action = "open_app",
            intent = null,
            toolName = "open_app",
            toolParams = mapOf("app_name" to appName),
            description = "Opening $appName"
        )
    }
}
