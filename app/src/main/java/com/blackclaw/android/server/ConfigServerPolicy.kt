package com.blackclaw.android.server

import java.io.File

/**
 * Security decisions for the local configuration server, as pure functions so the
 * whole policy is unit-testable.
 *
 * ## Threat model
 *
 * The server binds to `127.0.0.1`, but on Android **loopback is not isolated per
 * app**: any installed app holding `INTERNET` can reach it. So "it's only
 * localhost" is not an access control. Two concrete attacks the old code allowed:
 *
 *  1. `GET /api/llm` returned the API key in cleartext with no authentication, and
 *     every response carried `Access-Control-Allow-Origin: *` — so any app on the
 *     device, and any plain-HTTP page open in a browser, could read it.
 *  2. `POST /api/llm` wrote `llmBaseUrl` with no validation. Repointing the agent at
 *     an attacker-controlled server hands them every prompt (screen contents,
 *     notifications, clipboard) and control over every tool call. That turns a
 *     credential leak into persistent device control, which is far worse.
 *
 * The defence is a token the attacker cannot obtain over the same channel: it is
 * generated on the device, shown on the phone screen, and must be typed into the
 * page. Anything reachable over loopback is therefore useless without physical
 * access to the display.
 */
object ConfigServerPolicy {

    /** Unambiguous alphabet — the user reads this off a phone screen and types it. */
    const val TOKEN_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    /** 12 chars over a 31-char alphabet ≈ 7.8e17 combinations. */
    const val TOKEN_LENGTH = 12

    /** Rejected auth attempts tolerated before the server stops answering at all. */
    const val MAX_AUTH_FAILURES = 10

    /** Sliding window for [MAX_AUTH_FAILURES]. */
    const val AUTH_LOCKOUT_WINDOW_MS = 5 * 60 * 1000L

    // ── Session token ─────────────────────────────────────────────────────────

    fun generateToken(randomInt: (Int) -> Int): String =
        buildString(TOKEN_LENGTH) {
            repeat(TOKEN_LENGTH) { append(TOKEN_ALPHABET[randomInt(TOKEN_ALPHABET.length)]) }
        }

    /** Group into blocks of four so it is readable on screen and easy to retype. */
    fun formatTokenForDisplay(token: String): String =
        token.chunked(4).joinToString("-")

    /**
     * Pull the credential out of an `Authorization` header, accepting the token
     * bare as well so a user pasting just the code into a client still works.
     * Also accepts the display format with dashes.
     */
    fun extractToken(authorizationHeader: String?): String? {
        val raw = authorizationHeader?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val value = when {
            // Require the separating space so a bare token that merely begins with
            // these letters is not silently truncated.
            raw.length >= 7 && raw.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true) ->
                raw.substring(7)
            // "Bearer" with nothing after it (the trailing space is gone after trim)
            // carries no credential.
            raw.equals("Bearer", ignoreCase = true) -> ""
            else -> raw
        }
        return normalizeToken(value).ifEmpty { null }
    }

    fun normalizeToken(value: String): String =
        value.filter { it.isLetterOrDigit() }.uppercase()

    /**
     * Compare tokens without leaking length or match position through timing.
     *
     * A remote attacker on loopback can time thousands of requests per second, so
     * `==` on a secret is a real (if fiddly) oracle. Cheap to avoid.
     */
    fun tokensMatch(expected: String, submitted: String?): Boolean {
        val a = normalizeToken(expected)
        val b = normalizeToken(submitted.orEmpty())
        if (a.isEmpty() || a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    // ── Secret redaction ──────────────────────────────────────────────────────

    /** Show only the last four characters. Never return a secret in full. */
    fun maskSecret(secret: String): String = when {
        secret.isEmpty() -> ""
        secret.length <= 4 -> "*".repeat(secret.length)
        else -> "*".repeat(secret.length - 4) + secret.takeLast(4)
    }

    /**
     * A submitted value that still carries mask characters means the user did not
     * edit the field, so the stored secret must be left alone. This is what lets
     * the UI round-trip a masked value safely.
     */
    fun isMaskedValue(value: String): Boolean = value.contains('*')

    // ── LLM base URL validation ───────────────────────────────────────────────

    /**
     * Accept `https://` anywhere, and `http://` only for **loopback**.
     *
     * ## Why loopback only, and not the whole LAN
     *
     * This rule used to allow http to any RFC1918 address so a model server on the
     * desktop (ollama, llama.cpp, LM Studio at `http://192.168.1.50:11434`) would
     * work. `res/xml/network_security_config.xml` now denies cleartext by default,
     * and Android's network security config **cannot express an address range** — a
     * `<domain>` entry is one literal host, so 192.168.0.0/16 would mean enumerating
     * 65k of them.
     *
     * Keeping the wider rule here would leave the two layers disagreeing: the app
     * would accept a LAN http URL and the platform would then refuse the socket with
     * `UnknownServiceException: CLEARTEXT communication ... not permitted`. A
     * validator that green-lights a configuration the platform will reject is worse
     * than a stricter validator, because the user gets an unexplainable runtime
     * failure instead of an actionable message at the point of entry.
     *
     * So this is tightened to match the platform. Loopback stays permitted because it
     * is exempted in the same config and traffic that never leaves the handset has no
     * on-path attacker to defend against.
     *
     * **Deliberate tradeoff:** a LAN http model server is no longer configurable.
     * On-device inference is unaffected (LiteRT-LM runs in-process, no HTTP at all).
     * Users pointing at another machine need TLS in front of it, or a loopback
     * tunnel. [llmBaseUrlRejectionReason] explains that at the point of failure.
     */
    fun isSafeLlmBaseUrl(url: String): Boolean =
        llmBaseUrlRejectionReason(url) == null

    /**
     * Human-readable reason a base URL was refused, or null when it is acceptable.
     *
     * Split out from [isSafeLlmBaseUrl] so callers can tell the user *why* rather
     * than just failing. A bare boolean here produced a dead-end error.
     */
    fun llmBaseUrlRejectionReason(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null // clearing the field is allowed
        val parsed = runCatching { java.net.URI(trimmed) }.getOrNull()
            ?: return "No es una URL válida."
        val scheme = parsed.scheme?.lowercase()
            ?: return "Falta el esquema. Debe empezar por https://"
        val host = parsed.host?.lowercase()
        if (host.isNullOrEmpty()) return "Falta el host en la URL."
        return when (scheme) {
            "https" -> null
            "http" -> if (isLoopbackHost(host)) {
                null
            } else {
                "http solo se permite en localhost. Para un servidor de modelos en otra " +
                    "máquina usa https, o exponlo en loopback (túnel / adb reverse): " +
                    "Android bloquea el tráfico sin cifrar a direcciones de red, y por ahí " +
                    "viajarían tu API key y todo el contenido de pantalla."
            }
            else -> "Esquema '$scheme' no permitido. Usa https."
        }
    }

    /** Loopback only — this is what the network security config exempts. */
    fun isLoopbackHost(host: String): Boolean =
        host == "localhost" || host == "::1" || host == "ip6-localhost" ||
            (parseDottedQuad(host)?.first() == 127)

    /** Dotted-quad octets, or null when [host] is not one. */
    private fun parseDottedQuad(host: String): List<Int>? {
        val octets = host.split('.')
        if (octets.size != 4) return null
        val nums = octets.map { it.toIntOrNull() ?: return null }
        return if (nums.any { it !in 0..255 }) null else nums
    }

    /**
     * True for loopback and the RFC1918 / link-local ranges.
     *
     * No longer consulted by [isSafeLlmBaseUrl] — see the note there about the
     * platform being unable to express ranges. Kept because it is the correct
     * predicate for "not routable from the internet", which other callers need.
     */
    fun isLoopbackOrPrivateHost(host: String): Boolean {
        if (host == "localhost" || host == "::1") return true
        val nums = parseDottedQuad(host) ?: return false
        val (a, b) = nums[0] to nums[1]
        return when {
            a == 127 -> true                       // loopback
            a == 10 -> true                        // 10.0.0.0/8
            a == 192 && b == 168 -> true           // 192.168.0.0/16
            a == 172 && b in 16..31 -> true        // 172.16.0.0/12
            a == 169 && b == 254 -> true           // link-local
            else -> false
        }
    }

    // ── Path containment ──────────────────────────────────────────────────────

    /**
     * Whether [candidate] really sits inside [root].
     *
     * The previous check compared `absolutePath` prefixes, which does **not**
     * resolve `..` — so `<cache>/../files/mmkv/mmkv.default` passed verbatim and
     * handed out the plaintext key store. Canonicalising first is what makes the
     * check mean anything.
     */
    fun isPathContained(root: File, candidate: File): Boolean {
        val rootPath = runCatching { root.canonicalFile }.getOrNull() ?: return false
        val childPath = runCatching { candidate.canonicalFile }.getOrNull() ?: return false
        if (childPath == rootPath) return false // the directory itself is not a file to serve
        return childPath.path.startsWith(rootPath.path + File.separator)
    }

    // ── Failure throttling ────────────────────────────────────────────────────

    fun isAuthLockedOut(failures: Int, firstFailureAtMs: Long, nowMs: Long): Boolean =
        failures >= MAX_AUTH_FAILURES && nowMs - firstFailureAtMs < AUTH_LOCKOUT_WINDOW_MS

    /** Fold a failure in, restarting the window once the previous one expired. */
    fun registerAuthFailure(
        failures: Int,
        firstFailureAtMs: Long,
        nowMs: Long,
    ): Pair<Int, Long> {
        val windowExpired = failures == 0 || nowMs - firstFailureAtMs >= AUTH_LOCKOUT_WINDOW_MS
        return if (windowExpired) 1 to nowMs else (failures + 1) to firstFailureAtMs
    }
}
