package com.blackclaw.android.tool.impl

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Validation for smart-home webhook definitions. Pure functions, so every rule is
 * unit-testable without a network.
 *
 * ## Why this exists
 *
 * `add_smart_device` takes `webhook_url`, `method`, `headers` and `body_template`
 * straight from the model and stores them, and `smart_home` then fires them. Nothing
 * checked any of it. Two distinct failure modes follow.
 *
 * **Persistent exfiltration.** A prompt-injected model — a malicious web page read
 * by the fetch tool, a hostile message in a channel, a poisoned document — can
 * register a "device" whose webhook points at the attacker's server, with a body
 * template that carries whatever the agent later interpolates. It survives restarts,
 * appears in `list_smart_devices` as an ordinary lamp, and fires whenever the user
 * asks to turn that lamp on. Requiring TLS at least stops the payload being readable
 * by everyone on the path as well.
 *
 * **SSRF into the device itself.** The bigger one. A webhook is a request originating
 * from inside the phone, so it reaches things the internet cannot: the app's own
 * config server on 127.0.0.1 (which serves and accepts LLM credentials), any other
 * app listening on loopback, the router's admin panel on the LAN, cloud metadata
 * services on 169.254.169.254. The model chooses the URL, so this is an
 * attacker-chosen request from a trusted network position.
 *
 * ## Why this is stricter than the LLM base URL rule
 *
 * `server.ConfigServerPolicy.isSafeLlmBaseUrl` deliberately permits http to loopback
 * and RFC1918, because a local model server is a real use case. There is no
 * equivalent case here: a smart-home webhook exists to reach a hub or a cloud
 * service. Home Assistant, IFTTT, Hubitat and friends all serve https. So this
 * policy has no loopback exemption at all, and that asymmetry is intentional rather
 * than an inconsistency.
 *
 * ## Known residual weakness
 *
 * These are checks on the URL as written. A hostname that *resolves* to a private
 * address (`hub.attacker.com` -> 127.0.0.1, or a DNS answer that changes between
 * validation and connection — classic rebinding) is not caught, because nothing here
 * performs resolution. Closing that needs enforcement at connect time, in a custom
 * OkHttp DNS or socket factory that re-checks the resolved address.
 */
object SmartHomeWebhookPolicy {

    /** Why a webhook definition was refused. */
    enum class Verdict {
        OK,

        /** Blank, unparseable, or not an http/https URL at all. */
        NOT_A_URL,

        /** Parsed, but plain http. */
        SCHEME_NOT_HTTPS,

        /** Points somewhere only reachable from inside the device or the LAN. */
        INTERNAL_ADDRESS,

        /** Raw IPv6 literal. See [inspectUrl]. */
        IP_LITERAL_NOT_ALLOWED,
    }

    val ALLOWED_METHODS = setOf("GET", "POST", "PUT")

    /**
     * Classify a webhook URL.
     *
     * Parsing uses OkHttp's own [okhttp3.HttpUrl], which is the parser that will
     * later build the request. Validating with a different parser than the one that
     * performs the call is a well-worn source of bypasses: the two disagree about
     * backslashes, userinfo, and stray delimiters, and the attacker only needs them
     * to disagree once.
     */
    fun inspectUrl(url: String): Verdict {
        val parsed = url.trim().toHttpUrlOrNull() ?: return Verdict.NOT_A_URL
        if (parsed.scheme != "https") return Verdict.SCHEME_NOT_HTTPS

        val host = normalizeHost(parsed.host)
        if (host.isEmpty()) return Verdict.NOT_A_URL

        // IPv6 literals are refused outright rather than range-checked. A public
        // certificate for a bare IPv6 literal essentially does not exist, so no
        // legitimate https webhook is addressed this way, and an approximate IPv6
        // parser would be a worse outcome than a flat refusal. Failing closed on an
        // exotic input is the cheap side of this trade.
        if (host.contains(':')) return Verdict.IP_LITERAL_NOT_ALLOWED

        if (isLoopbackHostname(host)) return Verdict.INTERNAL_ADDRESS

        val ipv4 = parseIpv4(host)
        if (ipv4 != null && isInternalIpv4(ipv4)) return Verdict.INTERNAL_ADDRESS

        return Verdict.OK
    }

    /** Convenience: the tool-facing error string, or null when the URL is acceptable. */
    fun validateUrl(url: String): String? {
        val verdict = inspectUrl(url)
        return if (verdict == Verdict.OK) null else errorMessage(verdict, url)
    }

    /** Null when acceptable. Guards against a silent fallback to POST. */
    fun validateMethod(method: String): String? {
        val normalized = method.trim().uppercase()
        if (normalized.isEmpty() || normalized in ALLOWED_METHODS) return null
        return "Método HTTP '$method' no permitido para un webhook. " +
            "Usa uno de: ${ALLOWED_METHODS.sorted().joinToString(", ")}."
    }

    fun errorMessage(verdict: Verdict, url: String): String = when (verdict) {
        Verdict.OK -> ""
        Verdict.NOT_A_URL ->
            "El webhook '$url' no es una URL https válida. Debe empezar por https:// e " +
                "incluir un host."
        Verdict.SCHEME_NOT_HTTPS ->
            "El webhook debe usar https, no http. Sin TLS, la petición y sus cabeceras " +
                "(incluidos tokens de tu hub) viajan en claro y cualquiera en la red puede " +
                "leerlas o modificarlas. URL rechazada: $url"
        Verdict.INTERNAL_ADDRESS ->
            "El webhook apunta a una dirección interna del dispositivo o de la red local " +
                "($url). Un webhook de domótica no tiene ninguna razón para llamar a " +
                "localhost ni a la LAN, y permitirlo dejaría alcanzar servicios internos " +
                "del propio teléfono. Usa la URL pública https de tu hub o servicio."
        Verdict.IP_LITERAL_NOT_ALLOWED ->
            "El webhook usa una dirección IPv6 literal ($url), que no se acepta. Usa el " +
                "nombre de host https del servicio."
    }

    // ── Host classification ───────────────────────────────────────────────────

    /** Lowercase, drop one trailing dot, strip IPv6 brackets. */
    fun normalizeHost(host: String): String {
        var h = host.trim().lowercase()
        if (h.endsWith(".")) h = h.dropLast(1)
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length - 1)
        return h
    }

    /** Names that mean "this device" without being numeric. */
    fun isLoopbackHostname(host: String): Boolean =
        host == "localhost" ||
            host.endsWith(".localhost") || // reserved by RFC 6761
            host == "localhost.localdomain" ||
            host == "ip6-localhost" ||
            host == "ip6-loopback"

    /**
     * Parse an IPv4 address the way `inet_aton` does, which is the way the C
     * resolver underneath actually does.
     *
     * This matters because `127.0.0.1` is only the obvious spelling. `2130706433`,
     * `0x7f000001`, `0177.0.0.1` and `127.1` all reach the same host, and a check
     * that only understands dotted quads waves all of them through. Hand-rolled
     * rather than delegated to [java.net.InetAddress] because the JDK and Android
     * have tightened their numeric parsing at different times, and a security check
     * that behaves differently in tests than in production is not a check.
     *
     * @return the address as an unsigned 32-bit value, or null when [host] is not a
     *   numeric IPv4 address in any of these forms.
     */
    fun parseIpv4(host: String): Long? {
        if (host.isEmpty()) return null
        val parts = host.split('.')
        if (parts.size > 4) return null
        val values = ArrayList<Long>(parts.size)
        for (part in parts) {
            values.add(parseIpv4Part(part) ?: return null)
        }
        // Each leading part is one byte; the final part absorbs whatever is left, so
        // "127.1" is 127.0.0.1 and "2130706433" is the whole 32-bit value.
        val leading = values.size - 1
        val tailMax = 1L shl ((4 - leading) * 8)
        if (values[leading] >= tailMax) return null
        if (values.take(leading).any { it > 255L }) return null
        var result = 0L
        for (i in 0 until leading) result = result or (values[i] shl ((3 - i) * 8))
        return result or values[leading]
    }

    private fun parseIpv4Part(part: String): Long? {
        if (part.isEmpty()) return null
        val (digits, radix) = when {
            part.length > 2 && (part.startsWith("0x") || part.startsWith("0X")) ->
                part.substring(2) to 16
            part.length > 1 && part[0] == '0' -> part.substring(1) to 8
            else -> part to 10
        }
        if (digits.isEmpty()) return null
        val value = digits.toLongOrNull(radix) ?: return null
        return if (value in 0..0xFFFFFFFFL) value else null
    }

    /**
     * Ranges a smart-home webhook has no business reaching. Anything not routable
     * from the public internet is refused, so an SSRF attempt cannot be dressed up
     * as an ordinary device.
     */
    fun isInternalIpv4(address: Long): Boolean {
        val a = ((address shr 24) and 0xFF).toInt()
        val b = ((address shr 16) and 0xFF).toInt()
        return when {
            a == 0 -> true                          // 0.0.0.0/8 "this host"
            a == 127 -> true                        // loopback
            a == 10 -> true                         // RFC1918
            a == 172 && b in 16..31 -> true         // RFC1918
            a == 192 && b == 168 -> true            // RFC1918
            a == 169 && b == 254 -> true            // link-local, incl. cloud metadata
            a == 100 && b in 64..127 -> true        // RFC6598 carrier-grade NAT
            a == 192 && b == 0 -> true              // 192.0.0.0/24 protocol assignments
            a == 198 && b in 18..19 -> true         // RFC2544 benchmarking
            a in 224..239 -> true                   // multicast
            a >= 240 -> true                        // reserved / broadcast
            else -> false
        }
    }
}
