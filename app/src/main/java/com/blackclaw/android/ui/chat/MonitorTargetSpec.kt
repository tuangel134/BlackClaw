package com.blackclaw.android.ui.chat

data class MonitorTargetSpec(
    val label: String,
    val app: String,
    val tone: String = "Casual",
) {
    val displayLabel: String
        get() = "$label on $app"

    companion object {
        val supportedApps = listOf("WhatsApp", "Telegram", "Messages", "LINE", "WeChat")
    }
}

object MonitorTargetParser {
    fun fromTaskText(text: String): MonitorTargetSpec? {
        val app = extractApp(text)
        val label = extractLabel(text)
        if (label.isEmpty()) {
            return null
        }
        return MonitorTargetSpec(label = label, app = app)
    }

    private fun extractApp(text: String): String {
        val lower = text.lowercase()
        return when {
            Regex("""\btelegram\b""").containsMatchIn(lower) -> "Telegram"
            Regex("""\bwe\s*chat\b""").containsMatchIn(lower) -> "WeChat"
            Regex("""\bline\b""").containsMatchIn(lower) -> "LINE"
            Regex("""\b(messages|google messages|sms)\b""").containsMatchIn(lower) -> "Messages"
            else -> "WhatsApp"
        }
    }

    private fun extractLabel(text: String): String {
        val lower = text.lowercase()
        var cleaned = lower
        val removeWords = listOf(
            "monitoring", "monitor", "auto-reply", "auto reply", "watching", "watch",
            "on whatsapp", "on telegram", "on messages", "on google messages", "on sms", "on wechat", "on line",
            "messages", "message", "'s", "'s", "for", "from",
            "please", "can you", "start", "enable", "begin", "help me",
        )
        for (word in removeWords) {
            cleaned = cleaned.replace(word, " ")
        }
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()
        return if (cleaned.isNotEmpty()) cleaned.replaceFirstChar { it.uppercase() } else ""
    }
}
