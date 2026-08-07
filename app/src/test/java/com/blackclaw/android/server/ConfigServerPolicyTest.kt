package com.blackclaw.android.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The local config server hands out and accepts LLM credentials over loopback,
 * which on Android is reachable by every installed app. These tests pin the rules
 * that make that safe.
 */
class ConfigServerPolicyTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ── Token matching ────────────────────────────────────────────────────────

    @Test fun `matching token is accepted`() {
        assertTrue(ConfigServerPolicy.tokensMatch("A7KP2M9XQRT4", "A7KP2M9XQRT4"))
    }

    @Test fun `token comparison ignores formatting the user is likely to paste`() {
        listOf(
            "a7kp2m9xqrt4",
            "A7KP-2M9X-QRT4",
            "  A7KP2M9XQRT4  ",
            "a7kp-2m9x-qrt4",
        ).forEach {
            assertTrue("should have accepted '$it'", ConfigServerPolicy.tokensMatch("A7KP2M9XQRT4", it))
        }
    }

    @Test fun `wrong token is refused`() {
        assertFalse(ConfigServerPolicy.tokensMatch("A7KP2M9XQRT4", "A7KP2M9XQRT5"))
    }

    @Test fun `a prefix of the token is refused`() {
        assertFalse(ConfigServerPolicy.tokensMatch("A7KP2M9XQRT4", "A7KP2M9XQRT"))
        assertFalse(ConfigServerPolicy.tokensMatch("A7KP2M9XQRT4", "A7KP"))
    }

    @Test fun `null and empty are refused`() {
        assertFalse(ConfigServerPolicy.tokensMatch("A7KP2M9XQRT4", null))
        assertFalse(ConfigServerPolicy.tokensMatch("A7KP2M9XQRT4", ""))
    }

    @Test fun `an empty expected token never authorizes anything`() {
        // Guards against the server being reachable before a code was generated.
        assertFalse(ConfigServerPolicy.tokensMatch("", ""))
        assertFalse(ConfigServerPolicy.tokensMatch("", "anything"))
        assertFalse(ConfigServerPolicy.tokensMatch("", null))
    }

    // ── Header parsing ────────────────────────────────────────────────────────

    @Test fun `bearer scheme is parsed case-insensitively`() {
        assertEquals("ABC123", ConfigServerPolicy.extractToken("Bearer ABC123"))
        assertEquals("ABC123", ConfigServerPolicy.extractToken("bearer ABC123"))
        assertEquals("ABC123", ConfigServerPolicy.extractToken("BEARER abc123"))
    }

    @Test fun `a bare token is accepted so a hand-written client still works`() {
        assertEquals("ABC123", ConfigServerPolicy.extractToken("ABC123"))
        assertEquals("ABC123", ConfigServerPolicy.extractToken("abc-123"))
    }

    @Test fun `missing or blank header yields null`() {
        assertNull(ConfigServerPolicy.extractToken(null))
        assertNull(ConfigServerPolicy.extractToken(""))
        assertNull(ConfigServerPolicy.extractToken("   "))
        assertNull(ConfigServerPolicy.extractToken("Bearer "))
    }

    // ── Token generation ──────────────────────────────────────────────────────

    @Test fun `generated tokens use the declared length and alphabet`() {
        var counter = 0
        val token = ConfigServerPolicy.generateToken { bound -> (counter++ * 5) % bound }
        assertEquals(ConfigServerPolicy.TOKEN_LENGTH, token.length)
        token.forEach { assertTrue("unexpected char '$it'", it in ConfigServerPolicy.TOKEN_ALPHABET) }
    }

    @Test fun `token alphabet excludes visually ambiguous characters`() {
        listOf('0', 'O', '1', 'I', 'L').forEach {
            assertFalse("alphabet must not contain '$it'", it in ConfigServerPolicy.TOKEN_ALPHABET)
        }
    }

    @Test fun `a generated token round-trips through display formatting`() {
        var counter = 7
        val token = ConfigServerPolicy.generateToken { bound -> (counter++ * 3) % bound }
        val shown = ConfigServerPolicy.formatTokenForDisplay(token)
        assertTrue(shown.contains('-'))
        assertTrue(ConfigServerPolicy.tokensMatch(token, shown))
    }

    // ── Secret redaction ──────────────────────────────────────────────────────

    @Test fun `mask reveals only the last four characters`() {
        val secret = "sk-1234567890abcdef" // 19 chars
        val masked = ConfigServerPolicy.maskSecret(secret)
        assertEquals(secret.length, masked.length)
        assertEquals("cdef", masked.takeLast(4))
        assertEquals("*".repeat(secret.length - 4), masked.dropLast(4))
        assertEquals("", ConfigServerPolicy.maskSecret(""))
    }

    @Test fun `short secrets are fully masked rather than echoed`() {
        // The old implementation returned <=4 char secrets verbatim.
        assertEquals("****", ConfigServerPolicy.maskSecret("abcd"))
        assertEquals("**", ConfigServerPolicy.maskSecret("ab"))
    }

    @Test fun `mask output is recognised as masked so a round-trip preserves the secret`() {
        val masked = ConfigServerPolicy.maskSecret("sk-1234567890abcdef")
        assertTrue(ConfigServerPolicy.isMaskedValue(masked))
        assertFalse(ConfigServerPolicy.isMaskedValue("sk-a-genuinely-new-key"))
    }

    // ── LLM base URL ──────────────────────────────────────────────────────────

    @Test fun `https is accepted for any host`() {
        listOf(
            "https://api.openai.com/v1",
            "https://api.anthropic.com",
            "https://generativelanguage.googleapis.com/v1beta",
        ).forEach { assertTrue(it, ConfigServerPolicy.isSafeLlmBaseUrl(it)) }
    }

    @Test fun `http is accepted only for loopback`() {
        // Loopback is what network_security_config exempts, and on-device model
        // runtimes live there.
        listOf(
            "http://127.0.0.1:11434",
            "http://127.0.0.1:1234/v1",
            "http://localhost:1234/v1",
        ).forEach { assertTrue(it, ConfigServerPolicy.isSafeLlmBaseUrl(it)) }
    }

    @Test fun `http to a LAN address is refused so the app agrees with the platform`() {
        // Deliberately tightened: network_security_config now denies cleartext and
        // cannot express an address range, so accepting these here would green-light
        // a config the platform then refuses with an unexplainable socket error.
        listOf(
            "http://192.168.1.50:8080/v1",
            "http://10.0.0.5:5000",
            "http://172.16.4.9:8000",
            "http://172.31.255.255:8000",
            "http://169.254.1.1/v1",
        ).forEach { assertFalse(it, ConfigServerPolicy.isSafeLlmBaseUrl(it)) }
    }

    @Test fun `http to an internet host is refused`() {
        // This is the attack: repointing the agent at an attacker's endpoint sends
        // them the API key plus every prompt in cleartext.
        listOf(
            "http://evil.example.com/v1",
            "http://api.openai.com/v1",
            "http://8.8.8.8/v1",
        ).forEach { assertFalse(it, ConfigServerPolicy.isSafeLlmBaseUrl(it)) }
    }

    @Test fun `rejection explains what to do instead of just failing`() {
        val lan = ConfigServerPolicy.llmBaseUrlRejectionReason("http://192.168.1.50:11434")
        assertTrue(lan != null && lan.contains("https"))
        assertNull(ConfigServerPolicy.llmBaseUrlRejectionReason("https://api.openai.com/v1"))
        assertNull(ConfigServerPolicy.llmBaseUrlRejectionReason(""))
    }

    @Test fun `loopback classification is not fooled by a similar prefix`() {
        assertTrue(ConfigServerPolicy.isLoopbackHost("127.0.0.1"))
        assertTrue(ConfigServerPolicy.isLoopbackHost("127.1.2.3"))
        assertFalse(ConfigServerPolicy.isLoopbackHost("128.0.0.1"))
        assertFalse(ConfigServerPolicy.isLoopbackHost("1270.0.0.1"))
        assertFalse(ConfigServerPolicy.isLoopbackHost("example.com"))
    }

    @Test fun `non-http schemes are refused`() {
        listOf(
            "file:///data/data/com.blackclaw.android/files",
            "ftp://example.com",
            "javascript:alert(1)",
            "content://settings/secure",
        ).forEach { assertFalse(it, ConfigServerPolicy.isSafeLlmBaseUrl(it)) }
    }

    @Test fun `garbage and hostless input is refused`() {
        listOf("not a url", "https://", "http://", "://missing", "https:///v1")
            .forEach { assertFalse(it, ConfigServerPolicy.isSafeLlmBaseUrl(it)) }
    }

    @Test fun `clearing the field is allowed`() {
        assertTrue(ConfigServerPolicy.isSafeLlmBaseUrl(""))
        assertTrue(ConfigServerPolicy.isSafeLlmBaseUrl("   "))
    }

    @Test fun `private host classification handles boundaries`() {
        assertTrue(ConfigServerPolicy.isLoopbackOrPrivateHost("127.0.0.1"))
        assertTrue(ConfigServerPolicy.isLoopbackOrPrivateHost("169.254.1.1"))
        assertFalse(ConfigServerPolicy.isLoopbackOrPrivateHost("1.2.3.4"))
        assertFalse(ConfigServerPolicy.isLoopbackOrPrivateHost("999.1.1.1"))
        assertFalse(ConfigServerPolicy.isLoopbackOrPrivateHost("192.168.1"))
        assertFalse(ConfigServerPolicy.isLoopbackOrPrivateHost("example.com"))
    }

    // ── Path containment ──────────────────────────────────────────────────────

    @Test fun `a file inside the root is served`() {
        val root = temp.newFolder("cache")
        val inside = java.io.File(root, "shot.png").apply { writeText("x") }
        assertTrue(ConfigServerPolicy.isPathContained(root, inside))
    }

    @Test fun `dot-dot escape is refused`() {
        // The old check compared absolutePath prefixes, which does not resolve "..",
        // so <cache>/../files/mmkv/mmkv.default passed and leaked the key store.
        val root = temp.newFolder("cache")
        val sibling = temp.newFolder("files")
        java.io.File(sibling, "mmkv.default").writeText("secret")
        val escape = java.io.File(root, "../files/mmkv.default")
        assertFalse(ConfigServerPolicy.isPathContained(root, escape))
    }

    @Test fun `a sibling directory with a shared name prefix is refused`() {
        val root = temp.newFolder("cache")
        val evil = temp.newFolder("cache_evil")
        val file = java.io.File(evil, "x.png").apply { writeText("x") }
        assertFalse(ConfigServerPolicy.isPathContained(root, file))
    }

    @Test fun `the root directory itself is not servable`() {
        val root = temp.newFolder("cache")
        assertFalse(ConfigServerPolicy.isPathContained(root, root))
    }

    // ── Auth throttling ───────────────────────────────────────────────────────

    @Test fun `lockout engages at the cap inside the window`() {
        val now = 1_000_000L
        assertFalse(ConfigServerPolicy.isAuthLockedOut(ConfigServerPolicy.MAX_AUTH_FAILURES - 1, now, now + 1))
        assertTrue(ConfigServerPolicy.isAuthLockedOut(ConfigServerPolicy.MAX_AUTH_FAILURES, now, now + 1))
    }

    @Test fun `lockout expires with the window`() {
        val now = 1_000_000L
        val after = now + ConfigServerPolicy.AUTH_LOCKOUT_WINDOW_MS + 1
        assertFalse(ConfigServerPolicy.isAuthLockedOut(ConfigServerPolicy.MAX_AUTH_FAILURES, now, after))
    }

    @Test fun `failures accumulate inside the window and reset after it`() {
        val now = 1_000_000L
        var (failures, firstAt) = ConfigServerPolicy.registerAuthFailure(0, 0L, now)
        assertEquals(1, failures)
        assertEquals(now, firstAt)

        val next = ConfigServerPolicy.registerAuthFailure(failures, firstAt, now + 10)
        assertEquals(2, next.first)
        assertEquals(now, next.second) // window start is preserved

        val late = ConfigServerPolicy.registerAuthFailure(
            next.first, next.second, now + ConfigServerPolicy.AUTH_LOCKOUT_WINDOW_MS + 1,
        )
        assertEquals(1, late.first) // window restarted
    }
}
