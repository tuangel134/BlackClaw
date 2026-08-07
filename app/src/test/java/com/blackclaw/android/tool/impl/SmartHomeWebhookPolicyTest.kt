package com.blackclaw.android.tool.impl

import com.blackclaw.android.tool.impl.SmartHomeWebhookPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `add_smart_device` takes its webhook straight from the model and the app fires it
 * later, from inside the device. These tests pin the rules that stop that becoming a
 * persistent exfiltration channel or an SSRF into loopback.
 */
class SmartHomeWebhookPolicyTest {

    private fun assertRejected(url: String, expected: Verdict) {
        assertEquals("should have rejected '$url'", expected, SmartHomeWebhookPolicy.inspectUrl(url))
        assertTrue("expected an error message for '$url'", !SmartHomeWebhookPolicy.validateUrl(url).isNullOrBlank())
    }

    private fun assertAccepted(url: String) {
        assertEquals("should have accepted '$url'", Verdict.OK, SmartHomeWebhookPolicy.inspectUrl(url))
        assertNull(SmartHomeWebhookPolicy.validateUrl(url))
    }

    // ── Scheme ────────────────────────────────────────────────────────────────

    @Test fun `https to a public host is accepted`() {
        listOf(
            "https://maker.ifttt.com/trigger/lamp/with/key/abc",
            "https://my-hub.example.com:8123/api/webhook/xyz",
            "https://hooks.example.org/a?b=c#d",
            "https://203.0.113.10/api/webhook",
        ).forEach { assertAccepted(it) }
    }

    @Test fun `plain http is refused`() {
        assertRejected("http://maker.ifttt.com/trigger/lamp", Verdict.SCHEME_NOT_HTTPS)
    }

    @Test fun `non-http schemes are refused`() {
        listOf(
            "file:///data/data/com.blackclaw.android/shared_prefs/secrets.xml",
            "content://com.android.contacts/data",
            "ftp://example.com/x",
            "javascript:alert(1)",
            "ws://example.com/socket",
        ).forEach { assertRejected(it, Verdict.NOT_A_URL) }
    }

    @Test fun `blank and malformed input is refused`() {
        listOf("", "   ", "not a url", "https://", "://example.com").forEach {
            assertRejected(it, Verdict.NOT_A_URL)
        }
    }

    // ── Loopback and private ranges ───────────────────────────────────────────

    /**
     * The config server listens on 127.0.0.1 and serves/accepts LLM credentials, so
     * this is the highest-value SSRF target on the device.
     */
    @Test fun `loopback is refused`() {
        listOf(
            "https://127.0.0.1/api/llm",
            "https://127.0.0.1:8080/api/llm",
            "https://localhost/api/llm",
            "https://LOCALHOST/api/llm",
            "https://localhost.localdomain/x",
            "https://anything.localhost/x",
            "https://127.255.255.254/x",
        ).forEach { assertRejected(it, Verdict.INTERNAL_ADDRESS) }
    }

    @Test fun `RFC1918 ranges are refused`() {
        listOf(
            "https://10.0.0.1/x",
            "https://10.255.255.255/x",
            "https://192.168.1.1/x",
            "https://172.16.0.1/x",
            "https://172.31.255.255/x",
        ).forEach { assertRejected(it, Verdict.INTERNAL_ADDRESS) }
    }

    @Test fun `link-local including the cloud metadata address is refused`() {
        listOf(
            "https://169.254.1.1/x",
            "https://169.254.169.254/latest/meta-data/",
        ).forEach { assertRejected(it, Verdict.INTERNAL_ADDRESS) }
    }

    @Test fun `other non-routable ranges are refused`() {
        listOf(
            "https://0.0.0.0/x",           // "this host"
            "https://100.64.0.1/x",        // carrier-grade NAT
            "https://224.0.0.1/x",         // multicast
            "https://255.255.255.255/x",   // broadcast
        ).forEach { assertRejected(it, Verdict.INTERNAL_ADDRESS) }
    }

    /** 172.15 and 172.32 sit just outside RFC1918 and must not be over-blocked. */
    @Test fun `public addresses adjacent to private ranges stay allowed`() {
        listOf(
            "https://172.15.0.1/x",
            "https://172.32.0.1/x",
            "https://11.0.0.1/x",
            "https://192.167.1.1/x",
            "https://192.169.1.1/x",
            "https://100.63.255.255/x",
            "https://100.128.0.1/x",
            "https://169.253.1.1/x",
        ).forEach { assertAccepted(it) }
    }

    // ── Numeric encoding bypasses ─────────────────────────────────────────────

    /**
     * The whole point of parsing inet_aton forms. A dotted-quad-only check lets every
     * one of these through, and they all reach 127.0.0.1.
     */
    @Test fun `alternate spellings of loopback are refused`() {
        listOf(
            "https://2130706433/x",     // decimal
            "https://0x7f000001/x",     // hex
            "https://017700000001/x",   // octal
            "https://127.1/x",          // 2-part
            "https://127.0.1/x",        // 3-part
            "https://0177.0.0.1/x",     // octal first octet
            "https://0x7f.0.0.1/x",     // hex first octet
        ).forEach { assertRejected(it, Verdict.INTERNAL_ADDRESS) }
    }

    @Test fun `alternate spellings of RFC1918 are refused`() {
        listOf(
            "https://167772161/x",   // 10.0.0.1
            "https://3232235777/x",  // 192.168.1.1
            "https://192.168.257/x", // 192.168.1.1 via 3-part
        ).forEach { assertRejected(it, Verdict.INTERNAL_ADDRESS) }
    }

    @Test fun `trailing dot does not evade the check`() {
        assertRejected("https://localhost./x", Verdict.INTERNAL_ADDRESS)
        assertRejected("https://127.0.0.1./x", Verdict.INTERNAL_ADDRESS)
    }

    /**
     * A userinfo section is a classic way to make a URL read as one host and connect
     * to another. What matters is the host the request actually goes to.
     */
    @Test fun `userinfo cannot disguise a loopback target`() {
        assertRejected("https://maker.ifttt.com@127.0.0.1/x", Verdict.INTERNAL_ADDRESS)
        assertRejected("https://user:pass@localhost/x", Verdict.INTERNAL_ADDRESS)
    }

    @Test fun `a public host is still public when loopback appears in the userinfo`() {
        assertAccepted("https://127.0.0.1@maker.ifttt.com/x")
    }

    @Test fun `IPv6 literals are refused`() {
        listOf(
            "https://[::1]/x",
            "https://[fe80::1]/x",
            "https://[2001:db8::1]/x",
        ).forEach { assertRejected(it, Verdict.IP_LITERAL_NOT_ALLOWED) }
    }

    /**
     * OkHttp canonicalises IPv4-mapped IPv6 down to a dotted quad, so this arrives at
     * the range check rather than the literal refusal — and gets caught as loopback,
     * which is the more precise answer. Recorded because it is exactly the kind of
     * normalisation difference that would have been a bypass had validation used a
     * different parser from the one issuing the request.
     */
    @Test fun `IPv4-mapped IPv6 loopback is caught as loopback`() {
        assertRejected("https://[::ffff:127.0.0.1]/x", Verdict.INTERNAL_ADDRESS)
    }

    @Test fun `hostnames that merely contain digits are not treated as addresses`() {
        listOf(
            "https://10.example.com/x",
            "https://192-168-1-1.example.com/x",
            "https://hub10.example.com/x",
        ).forEach { assertAccepted(it) }
    }

    // ── Raw parser units ──────────────────────────────────────────────────────

    @Test fun `parseIpv4 handles every inet_aton form`() {
        assertEquals(0x7F000001L, SmartHomeWebhookPolicy.parseIpv4("127.0.0.1"))
        assertEquals(0x7F000001L, SmartHomeWebhookPolicy.parseIpv4("2130706433"))
        assertEquals(0x7F000001L, SmartHomeWebhookPolicy.parseIpv4("0x7f000001"))
        assertEquals(0x7F000001L, SmartHomeWebhookPolicy.parseIpv4("127.1"))
        assertEquals(0x7F000001L, SmartHomeWebhookPolicy.parseIpv4("127.0.1"))
        assertEquals(0xC0A80101L, SmartHomeWebhookPolicy.parseIpv4("192.168.1.1"))
    }

    @Test fun `parseIpv4 rejects things that are not addresses`() {
        listOf(
            "", "example.com", "1.2.3.4.5", "256.0.0.1", "127.0.0.256",
            "1.2.3.-1", "0x", "..", "127..1", "99999999999999",
        ).forEach {
            assertNull("should not have parsed '$it'", SmartHomeWebhookPolicy.parseIpv4(it))
        }
    }

    // ── Method ────────────────────────────────────────────────────────────────

    @Test fun `documented methods are accepted`() {
        listOf("GET", "POST", "PUT", "get", " put ", "").forEach {
            assertNull("should have accepted '$it'", SmartHomeWebhookPolicy.validateMethod(it))
        }
    }

    @Test fun `undocumented methods are refused instead of silently becoming POST`() {
        listOf("DELETE", "PATCH", "TRACE", "CONNECT", "nonsense").forEach {
            assertTrue(
                "should have refused '$it'",
                !SmartHomeWebhookPolicy.validateMethod(it).isNullOrBlank(),
            )
        }
    }

    // ── Messaging ─────────────────────────────────────────────────────────────

    @Test fun `error messages name the offending url so the model can correct itself`() {
        val message = SmartHomeWebhookPolicy.validateUrl("http://127.0.0.1/api/llm")
        assertTrue(message, message!!.contains("127.0.0.1"))
    }

    @Test fun `an accepted url produces no error`() {
        assertEquals("", SmartHomeWebhookPolicy.errorMessage(Verdict.OK, "https://example.com"))
    }
}
