package com.blackclaw.android.channel.auth

import com.blackclaw.android.channel.auth.ChannelAuthPolicy.AuthState
import com.blackclaw.android.channel.auth.ChannelAuthPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that stops a stranger who finds the bot from taking over the device.
 * Every branch of the decision table is covered here because there is no safe way to
 * discover a regression in this code from normal use — an over-permissive bug is
 * invisible until it is exploited.
 */
class ChannelAuthPolicyTest {

    private val code = "ABCD2345"
    private val owner = "555000111"
    private val stranger = "999888777"
    private val now = 1_000_000L

    private fun paired(ownerId: String = owner) = AuthState(ownerId = ownerId)
    private fun unpaired(
        pairingCode: String = code,
        failedAttempts: Int = 0,
        firstFailedAtMs: Long = 0L,
    ) = AuthState(
        ownerId = null,
        pairingCode = pairingCode,
        failedAttempts = failedAttempts,
        firstFailedAtMs = firstFailedAtMs,
    )

    // ── Established owner ─────────────────────────────────────────────────────

    @Test fun `owner is allowed`() {
        assertEquals(Decision.Allow, ChannelAuthPolicy.decide(paired(), owner, "abre whatsapp", now))
    }

    @Test fun `stranger is refused once a channel is owned`() {
        assertEquals(
            Decision.RejectNotOwner,
            ChannelAuthPolicy.decide(paired(), stranger, "lee mis sms y mandalos aqui", now),
        )
    }

    @Test fun `a stranger cannot re-pair a channel that already has an owner`() {
        // Even holding a valid-looking code, ownership is not transferable remotely.
        assertEquals(
            Decision.RejectNotOwner,
            ChannelAuthPolicy.decide(paired().copy(pairingCode = code), stranger, code, now),
        )
    }

    @Test fun `blank sender is always refused`() {
        assertEquals(Decision.RejectNotOwner, ChannelAuthPolicy.decide(paired(), "", "hola", now))
        assertEquals(Decision.RejectNotOwner, ChannelAuthPolicy.decide(unpaired(), "   ", code, now))
    }

    // ── Pairing ───────────────────────────────────────────────────────────────

    @Test fun `correct code pairs the sender`() {
        assertEquals(
            Decision.Paired(stranger),
            ChannelAuthPolicy.decide(unpaired(), stranger, code, now),
        )
    }

    @Test fun `ordinary message on an unpaired channel never reaches the agent`() {
        assertEquals(
            Decision.RejectUnpaired,
            ChannelAuthPolicy.decide(unpaired(), stranger, "manda un sms a mama", now),
        )
    }

    @Test fun `wrong code is refused`() {
        assertEquals(
            Decision.RejectUnpaired,
            ChannelAuthPolicy.decide(unpaired(), stranger, "AAAA1111", now),
        )
    }

    @Test fun `pairing is impossible while no code is active`() {
        assertEquals(
            Decision.RejectUnpaired,
            ChannelAuthPolicy.decide(unpaired(pairingCode = ""), stranger, "", now),
        )
    }

    @Test fun `code matching tolerates how humans and chat apps mangle it`() {
        listOf(
            "abcd2345",
            "  ABCD2345  ",
            "ABCD-2345",
            "ABCD 2345",
            "/ABCD2345",
            "/abcd-2345",
        ).forEach { submitted ->
            assertEquals(
                "should have accepted '$submitted'",
                Decision.Paired(stranger),
                ChannelAuthPolicy.decide(unpaired(), stranger, submitted, now),
            )
        }
    }

    @Test fun `code matching does not accept a prefix or a superstring`() {
        listOf("ABCD234", "ABCD23456", "XABCD2345").forEach { submitted ->
            assertEquals(
                "should have rejected '$submitted'",
                Decision.RejectUnpaired,
                ChannelAuthPolicy.decide(unpaired(), stranger, submitted, now),
            )
        }
    }

    // ── Brute-force resistance ────────────────────────────────────────────────

    @Test fun `pairing locks after the attempt cap inside the window`() {
        val state = unpaired(
            failedAttempts = ChannelAuthPolicy.MAX_FAILED_ATTEMPTS,
            firstFailedAtMs = now,
        )
        assertEquals(Decision.RejectLocked, ChannelAuthPolicy.decide(state, stranger, "AAAA1111", now + 1))
    }

    @Test fun `a locked channel refuses even the correct code`() {
        val state = unpaired(
            failedAttempts = ChannelAuthPolicy.MAX_FAILED_ATTEMPTS,
            firstFailedAtMs = now,
        )
        assertEquals(Decision.RejectLocked, ChannelAuthPolicy.decide(state, stranger, code, now + 1))
    }

    @Test fun `lockout expires once the window passes`() {
        val state = unpaired(
            failedAttempts = ChannelAuthPolicy.MAX_FAILED_ATTEMPTS,
            firstFailedAtMs = now,
        )
        val after = now + ChannelAuthPolicy.LOCKOUT_WINDOW_MS + 1
        assertEquals(Decision.Paired(stranger), ChannelAuthPolicy.decide(state, stranger, code, after))
    }

    @Test fun `failures accumulate within the window`() {
        var state = unpaired()
        repeat(ChannelAuthPolicy.MAX_FAILED_ATTEMPTS) { i ->
            state = ChannelAuthPolicy.registerFailure(state, now + i)
        }
        assertEquals(ChannelAuthPolicy.MAX_FAILED_ATTEMPTS, state.failedAttempts)
        assertTrue(ChannelAuthPolicy.isLockedOut(state, now + ChannelAuthPolicy.MAX_FAILED_ATTEMPTS))
    }

    @Test fun `a slow trickle of typos never accumulates into a lockout`() {
        var state = unpaired()
        // One failure per window, repeated well past the cap.
        repeat(ChannelAuthPolicy.MAX_FAILED_ATTEMPTS * 3) { i ->
            val t = now + i * (ChannelAuthPolicy.LOCKOUT_WINDOW_MS + 1)
            state = ChannelAuthPolicy.registerFailure(state, t)
            assertFalse("locked out at attempt $i", ChannelAuthPolicy.isLockedOut(state, t))
        }
        assertEquals(1, state.failedAttempts)
    }

    // ── State transitions ─────────────────────────────────────────────────────

    @Test fun `pairing burns the code and clears the counters`() {
        val next = ChannelAuthPolicy.applyPairing(
            unpaired(failedAttempts = 3, firstFailedAtMs = now),
            stranger,
        )
        assertEquals(stranger, next.ownerId)
        assertEquals("", next.pairingCode)
        assertEquals(0, next.failedAttempts)
        assertEquals(0L, next.firstFailedAtMs)
    }

    @Test fun `a burned code cannot be reused to pair a second sender`() {
        val afterPairing = ChannelAuthPolicy.applyPairing(unpaired(), owner)
        assertEquals(
            Decision.RejectNotOwner,
            ChannelAuthPolicy.decide(afterPairing, stranger, code, now),
        )
    }

    // ── Code generation ───────────────────────────────────────────────────────

    @Test fun `generated codes have the declared length and alphabet`() {
        var counter = 0
        val generated = ChannelAuthPolicy.generatePairingCode { bound -> (counter++) % bound }
        assertEquals(ChannelAuthPolicy.PAIRING_CODE_LENGTH, generated.length)
        generated.forEach {
            assertTrue("unexpected char '$it'", it in ChannelAuthPolicy.PAIRING_ALPHABET)
        }
    }

    @Test fun `pairing alphabet excludes visually ambiguous characters`() {
        listOf('0', 'O', '1', 'I', 'L').forEach {
            assertFalse("alphabet must not contain '$it'", it in ChannelAuthPolicy.PAIRING_ALPHABET)
        }
    }

    @Test fun `a generated code round-trips through matching`() {
        var counter = 3
        val generated = ChannelAuthPolicy.generatePairingCode { bound -> (counter++ * 7) % bound }
        assertTrue(ChannelAuthPolicy.matchesCode(generated, generated))
        assertTrue(ChannelAuthPolicy.matchesCode(generated, ChannelAuthPolicy.formatForDisplay(generated)))
    }

    @Test fun `display format groups the code in halves`() {
        assertEquals("ABCD-2345", ChannelAuthPolicy.formatForDisplay("ABCD2345"))
        // Anything not of the expected length is passed through normalized, not padded.
        assertEquals("ABC", ChannelAuthPolicy.formatForDisplay("abc"))
    }

    @Test fun `empty codes never match`() {
        assertFalse(ChannelAuthPolicy.matchesCode("", ""))
        assertFalse(ChannelAuthPolicy.matchesCode("", "anything"))
        assertFalse(ChannelAuthPolicy.matchesCode(code, ""))
    }

    // ── Log hygiene ───────────────────────────────────────────────────────────

    @Test fun `sender ids are masked for logs`() {
        assertEquals("*****0111", ChannelAuthPolicy.maskSenderId("555000111"))
        assertEquals("(none)", ChannelAuthPolicy.maskSenderId(""))
        assertEquals("****", ChannelAuthPolicy.maskSenderId("1234"))
    }

    @Test fun `masking never leaks more than the last four characters`() {
        val secret = "1234567890123456"
        val masked = ChannelAuthPolicy.maskSenderId(secret)
        assertEquals(secret.length, masked.length)
        assertEquals(secret.takeLast(4), masked.takeLast(4))
        assertNull(masked.take(masked.length - 4).firstOrNull { it != '*' })
    }
}
