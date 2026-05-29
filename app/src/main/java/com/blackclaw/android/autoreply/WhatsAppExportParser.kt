package com.blackclaw.android.autoreply

import android.content.Context
import android.net.Uri
import com.blackclaw.android.utils.XLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/**
 * Parser for WhatsApp chat exports.
 *
 * WhatsApp exports come in two shapes:
 *  1. Plain `.txt` with one message per line, prefixed by date/time + sender.
 *  2. `.zip` archive containing `_chat.txt` plus media files (photos/voice).
 *
 * Date/time formats vary heavily by OS locale and Android version. Examples
 * we have to accept (all real, gathered from real exports):
 *
 *   [21/3/24, 10:45:13] Mamá: hola, ¿cómo estás?
 *   21/3/24, 10:45 - Mamá: hola, ¿cómo estás?
 *   3/21/24, 10:45 AM - Mamá: hello
 *   [2024-03-21, 10:45:13] Mamá: hola
 *
 * Strategy:
 *  - Use a permissive regex that matches `<date>, <time> - <sender>: <body>`
 *    and a bracketed variant `[<date>, <time>] <sender>: <body>`.
 *  - Lines that don't match are appended to the previous message (multi-line).
 *  - System messages ("Messages and calls are end-to-end encrypted") are
 *    detected by missing `:` in the body and dropped.
 */
object WhatsAppExportParser {

    private const val TAG = "WhatsAppExportParser"

    data class Message(
        val sender: String,
        val body: String,
    )

    /**
     * Parse a WhatsApp export file (txt or zip) and return a list of messages.
     * `mineHint` is the user's name as it appears in the export. It's only used
     * to relabel messages as "Yo:" / "<contact>:" in [renderForPrompt].
     */
    fun parse(context: Context, uri: Uri): List<Message> {
        val mime = context.contentResolver.getType(uri).orEmpty()
        return runCatching {
            val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
                if (mime.contains("zip", ignoreCase = true) ||
                    uri.toString().endsWith(".zip", ignoreCase = true)) {
                    extractChatTxtFromZip(stream)
                } else {
                    stream.bufferedReader(Charsets.UTF_8).readText()
                }
            } ?: ""
            parseRawText(raw)
        }.getOrElse {
            XLog.e(TAG, "Failed to parse export: ${it.message}", it)
            emptyList()
        }
    }

    private fun extractChatTxtFromZip(stream: java.io.InputStream): String {
        val sb = StringBuilder()
        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && name.endsWith(".txt", ignoreCase = true)) {
                    BufferedReader(InputStreamReader(zip, Charsets.UTF_8)).use { r ->
                        var line = r.readLine()
                        while (line != null) {
                            sb.append(line).append('\n')
                            line = r.readLine()
                        }
                    }
                    break
                }
                entry = zip.nextEntry
            }
        }
        return sb.toString()
    }

    /** Pre-compiled patterns for the supported header shapes. */
    private val BRACKETED = Regex(
        """^\[(\d{1,4}[/.\-]\d{1,2}[/.\-]\d{1,4}),?\s+(\d{1,2}:\d{2}(?::\d{2})?\s?(?:AM|PM|a\.m\.|p\.m\.)?)\]\s*([^:]+?):\s?(.*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val DASH = Regex(
        """^(\d{1,4}[/.\-]\d{1,2}[/.\-]\d{1,4}),?\s+(\d{1,2}:\d{2}(?::\d{2})?\s?(?:AM|PM|a\.m\.|p\.m\.)?)\s*-\s*([^:]+?):\s?(.*)$""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseRawText(text: String): List<Message> {
        val out = mutableListOf<Message>()
        var current: Message? = null

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trimEnd()
            if (line.isEmpty()) continue

            val match = BRACKETED.find(line) ?: DASH.find(line)
            if (match != null) {
                // Flush pending message
                current?.let { out.add(it) }
                val sender = match.groupValues[3].trim()
                val body = match.groupValues[4].trim()
                // Skip likely system messages without sender/body
                if (sender.isEmpty()) {
                    current = null
                    continue
                }
                current = Message(sender = sender, body = body)
            } else {
                // Continuation of previous message
                if (current != null) {
                    current = current.copy(body = (current.body + "\n" + line).trim())
                }
                // else: skip (probably leading "Messages and calls are end-to-end encrypted")
            }
        }
        current?.let { out.add(it) }

        // Strip media placeholders so the LLM doesn't try to mimic "<Media omitted>"
        return out.filter { msg ->
            val body = msg.body.lowercase()
            body.isNotBlank() &&
                "<media omitted>" !in body &&
                "<archivo omitido>" !in body &&
                !body.contains("multimedia omitido") &&
                !body.contains("image omitted") &&
                !body.contains("video omitted") &&
                !body.contains("audio omitted")
        }
    }

    /**
     * Build a compact "Yo: ... / <contact>: ..." block for the LLM prompt.
     *
     * - Detects the user's own messages by matching against `userNameHint`
     *   (case-insensitive, substring). Falls back to whichever name appears
     *   second-most often if no hint is given (heuristic: usually the contact
     *   name dominates).
     * - Limits total chars to ~6000 to keep prompts short. Keeps the most
     *   recent messages (they reflect the most current voice).
     */
    fun renderForPrompt(messages: List<Message>, userNameHint: String? = null, maxChars: Int = 6000): String {
        if (messages.isEmpty()) return ""
        val mineLabel = "Yo"
        // Heuristic: figure out who is "me"
        val myName = userNameHint?.takeIf { it.isNotBlank() }?.trim()
            ?: pickMineByFrequency(messages)
        val tail = mutableListOf<Message>()
        var total = 0
        // Walk backwards, take messages until we hit maxChars
        for (msg in messages.asReversed()) {
            val label = if (myName != null && msg.sender.contains(myName, ignoreCase = true)) mineLabel
                        else msg.sender
            val rendered = "$label: ${msg.body}"
            total += rendered.length + 1
            if (total > maxChars && tail.isNotEmpty()) break
            tail.add(0, Message(sender = label, body = msg.body))
        }
        return tail.joinToString("\n") { "${it.sender}: ${it.body}" }
    }

    /**
     * Best-effort: when there are exactly two distinct senders, the user is
     * usually the one with FEWER total messages (people text less than they
     * receive in a typical export). When 3+ senders, return null so the
     * caller can ask the user.
     */
    private fun pickMineByFrequency(messages: List<Message>): String? {
        val counts = messages.groupingBy { it.sender }.eachCount()
        if (counts.size != 2) return null
        return counts.entries.minByOrNull { it.value }?.key
    }

    /**
     * Stats for the UI to show after import.
     */
    data class Stats(
        val messageCount: Int,
        val senders: List<Pair<String, Int>>,
    )

    fun stats(messages: List<Message>): Stats = Stats(
        messageCount = messages.size,
        senders = messages.groupingBy { it.sender }.eachCount()
            .toList().sortedByDescending { it.second },
    )
}
