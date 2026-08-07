package com.blackclaw.android.emergency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import javax.crypto.KeyGenerator
import kotlin.io.path.createTempDirectory

class EmergencyEvidenceVaultTest {
    @Test
    fun `encrypted segment round trips without plaintext leakage`() {
        val directory = createTempDirectory("evidence-vault").toFile()
        val source = directory.resolve("source.mp4").apply {
            writeBytes(ByteArray(180_000) { index -> (index * 31).toByte() })
        }
        val encrypted = directory.resolve("segment.bcenc")
        val restored = directory.resolve("restored.mp4")
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        EmergencyEvidenceVault.encryptFile(source, encrypted, key)
        EmergencyEvidenceVault.decryptFile(encrypted, restored, key)

        assertEquals(true, encrypted.length() > source.length())
        assertEquals(false, encrypted.readBytes().contentEquals(source.readBytes()))
        assertEquals(source.readBytes().toList(), restored.readBytes().toList())
        directory.deleteRecursively()
    }

    @Test
    fun `tampered evidence fails loudly instead of returning a truncated file`() {
        // This is the whole point of sealing with an authenticated cipher. The previous
        // implementation used CipherInputStream, which swallows AEADBadTagException on
        // close and reports a clean end of stream — so altered evidence came back as a
        // short but "successful" video that still passed a length check.
        val directory = createTempDirectory("evidence-tamper").toFile()
        val source = directory.resolve("source.mp4").apply {
            writeBytes(ByteArray(180_000) { index -> (index * 17).toByte() })
        }
        val encrypted = directory.resolve("segment.bcenc")
        val restored = directory.resolve("restored.mp4")
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        EmergencyEvidenceVault.encryptFile(source, encrypted, key)

        // Flip one ciphertext byte, well past the 17-byte magic and IV header.
        val bytes = encrypted.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
        encrypted.writeBytes(bytes)

        var failed = false
        try {
            EmergencyEvidenceVault.decryptFile(encrypted, restored, key)
        } catch (_: Exception) {
            failed = true
        }
        assertEquals("la evidencia alterada debe rechazarse", true, failed)
        directory.deleteRecursively()
    }

    @Test
    fun `truncated evidence is rejected rather than silently shortened`() {
        val directory = createTempDirectory("evidence-truncated").toFile()
        val source = directory.resolve("source.mp4").apply {
            writeBytes(ByteArray(120_000) { index -> (index * 7).toByte() })
        }
        val encrypted = directory.resolve("segment.bcenc")
        val restored = directory.resolve("restored.mp4")
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        EmergencyEvidenceVault.encryptFile(source, encrypted, key)
        // Drop the trailing GCM tag, which is what an interrupted write looks like.
        val bytes = encrypted.readBytes()
        encrypted.writeBytes(bytes.copyOf(bytes.size - 24))

        var failed = false
        try {
            EmergencyEvidenceVault.decryptFile(encrypted, restored, key)
        } catch (_: Exception) {
            failed = true
        }
        assertEquals("un segmento truncado debe rechazarse", true, failed)
        directory.deleteRecursively()
    }

    @Test
    fun `recognizes front camera video and timestamp`() {
        val parsed = EmergencyEvidenceVault.parseDescriptor(
            "emergency_20260718_152233_045_front.bcenc",
            fallbackTime = 1L,
        )

        assertEquals(EmergencyEvidenceVault.MediaType.VIDEO, parsed.mediaType)
        assertEquals("front", parsed.lens)
        assertEquals(
            SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).parse("20260718_152233_045")!!.time,
            parsed.capturedAt,
        )
    }

    @Test
    fun `recognizes back camera video`() {
        val parsed = EmergencyEvidenceVault.parseDescriptor(
            "emergency_20260718_152233_045_back.bcenc",
            fallbackTime = 1L,
        )

        assertEquals(EmergencyEvidenceVault.MediaType.VIDEO, parsed.mediaType)
        assertEquals("back", parsed.lens)
    }

    @Test
    fun `recognizes audio and falls back for legacy name`() {
        val parsed = EmergencyEvidenceVault.parseDescriptor("emergency_legacy.bcenc", fallbackTime = 99L)

        assertEquals(EmergencyEvidenceVault.MediaType.AUDIO, parsed.mediaType)
        assertNull(parsed.lens)
        assertEquals(99L, parsed.capturedAt)
    }
}
