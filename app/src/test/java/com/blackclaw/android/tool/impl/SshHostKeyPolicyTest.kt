package com.blackclaw.android.tool.impl

import com.blackclaw.android.tool.impl.SshHostKeyPolicy.Verdict
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The remote shell tools authenticate with a plaintext password, so the host key
 * check is the only thing standing between "the server the user configured" and
 * "whoever answered for that IP". These tests pin that logic.
 */
class SshHostKeyPolicyTest {

    /** Build a well-formed SSH public key blob: length-prefixed algorithm name + body. */
    private fun blob(algorithm: String, body: ByteArray = byteArrayOf(1, 2, 3, 4)): ByteArray {
        val name = algorithm.toByteArray(StandardCharsets.US_ASCII)
        val out = ByteArrayOutputStream()
        out.write((name.size ushr 24) and 0xFF)
        out.write((name.size ushr 16) and 0xFF)
        out.write((name.size ushr 8) and 0xFF)
        out.write(name.size and 0xFF)
        out.write(name)
        out.write(body)
        return out.toByteArray()
    }

    // ── Key type parsing ──────────────────────────────────────────────────────

    @Test fun `reads the algorithm name from a well-formed blob`() {
        listOf("ssh-ed25519", "ssh-rsa", "ecdsa-sha2-nistp256", "rsa-sha2-512").forEach {
            assertEquals(it, SshHostKeyPolicy.keyTypeFromBlob(blob(it)))
        }
    }

    @Test fun `malformed blobs degrade instead of throwing`() {
        listOf(
            byteArrayOf(),
            byteArrayOf(1),
            byteArrayOf(0, 0, 0, 0),                          // zero-length name
            byteArrayOf(0, 0, 0, 100, 65, 66),                // length past the end
            byteArrayOf(-1, -1, -1, -1, 65),                  // absurd length
        ).forEach {
            assertEquals(
                SshHostKeyPolicy.UNKNOWN_KEY_TYPE,
                SshHostKeyPolicy.keyTypeFromBlob(it),
            )
        }
    }

    @Test fun `non-printable algorithm names are rejected`() {
        val weird = byteArrayOf(0, 0, 0, 3, 0x01, 0x02, 0x03)
        assertEquals(SshHostKeyPolicy.UNKNOWN_KEY_TYPE, SshHostKeyPolicy.keyTypeFromBlob(weird))
    }

    // ── Fingerprints ──────────────────────────────────────────────────────────

    @Test fun `fingerprint uses the OpenSSH SHA256 shape`() {
        val fp = SshHostKeyPolicy.fingerprint(blob("ssh-ed25519"))
        assertTrue(fp, fp.startsWith("ssh-ed25519 SHA256:"))
        // Unpadded base64 of a 32-byte digest is 43 chars.
        assertEquals(43, fp.substringAfter("SHA256:").length)
        assertTrue(fp, !fp.contains("="))
    }

    @Test fun `fingerprint is stable for the same key`() {
        assertEquals(
            SshHostKeyPolicy.fingerprint(blob("ssh-ed25519")),
            SshHostKeyPolicy.fingerprint(blob("ssh-ed25519")),
        )
    }

    @Test fun `different key material yields a different fingerprint`() {
        assertNotEquals(
            SshHostKeyPolicy.fingerprint(blob("ssh-ed25519", byteArrayOf(1, 2, 3, 4))),
            SshHostKeyPolicy.fingerprint(blob("ssh-ed25519", byteArrayOf(1, 2, 3, 5))),
        )
    }

    /**
     * The algorithm is part of the pinned string, so a server cannot swap an RSA key
     * in for an Ed25519 one and have it read as the same identity.
     */
    @Test fun `algorithm is part of the fingerprint`() {
        val body = byteArrayOf(9, 9, 9, 9)
        assertNotEquals(
            SshHostKeyPolicy.fingerprint(blob("ssh-ed25519", body)),
            SshHostKeyPolicy.fingerprint(blob("ssh-rsa", body)),
        )
    }

    // ── Verdicts ──────────────────────────────────────────────────────────────

    @Test fun `nothing pinned yet is first use`() {
        listOf(null, "", "   ").forEach {
            assertEquals(
                "failed for '$it'",
                Verdict.TRUST_ON_FIRST_USE,
                SshHostKeyPolicy.verdict(it, "ssh-ed25519 SHA256:AAAA"),
            )
        }
    }

    @Test fun `same key on a later connect matches`() {
        val fp = SshHostKeyPolicy.fingerprint(blob("ssh-ed25519"))
        assertEquals(Verdict.MATCH, SshHostKeyPolicy.verdict(fp, fp))
    }

    /** The attack this whole item exists to stop. */
    @Test fun `a substituted host key is a mismatch`() {
        val pinned = SshHostKeyPolicy.fingerprint(blob("ssh-ed25519", byteArrayOf(1, 1)))
        val attacker = SshHostKeyPolicy.fingerprint(blob("ssh-ed25519", byteArrayOf(2, 2)))
        assertEquals(Verdict.MISMATCH, SshHostKeyPolicy.verdict(pinned, attacker))
    }

    @Test fun `whitespace in storage does not cause a false mismatch`() {
        val fp = SshHostKeyPolicy.fingerprint(blob("ssh-rsa"))
        assertEquals(Verdict.MATCH, SshHostKeyPolicy.verdict("  $fp  ", fp))
        assertEquals(Verdict.MATCH, SshHostKeyPolicy.verdict(fp, "  $fp  "))
    }

    @Test fun `a pinned key never matches an empty presented fingerprint`() {
        val fp = SshHostKeyPolicy.fingerprint(blob("ssh-rsa"))
        assertEquals(Verdict.MISMATCH, SshHostKeyPolicy.verdict(fp, ""))
    }

    @Test fun `a truncated fingerprint does not match the full one`() {
        val fp = SshHostKeyPolicy.fingerprint(blob("ssh-rsa"))
        assertEquals(Verdict.MISMATCH, SshHostKeyPolicy.verdict(fp, fp.dropLast(1)))
    }

    // ── Messaging ─────────────────────────────────────────────────────────────

    @Test fun `mismatch message states the password was not sent and shows both keys`() {
        val message = SshHostKeyPolicy.mismatchMessage(
            host = "mi-pc",
            pinned = "ssh-ed25519 SHA256:PINNED",
            presented = "ssh-rsa SHA256:PRESENTED",
        )
        assertTrue(message, message.contains("mi-pc"))
        assertTrue(message, message.contains("SHA256:PINNED"))
        assertTrue(message, message.contains("SHA256:PRESENTED"))
        assertTrue(message, message.contains("No se envió la contraseña"))
    }

    @Test fun `first use message shows the fingerprint the user must verify`() {
        val message = SshHostKeyPolicy.firstUseMessage("mi-pc", "ssh-ed25519 SHA256:ABC")
        assertTrue(message, message.contains("SHA256:ABC"))
        assertTrue(message, message.contains("mi-pc"))
    }
}
