package com.blackclaw.android.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRepositoryTest {

    private fun turn(role: ConversationRepository.Role, text: String,
                     surface: ConversationRepository.Surface = ConversationRepository.Surface.CHAT,
                     trust: ConversationRepository.Trust = ConversationRepository.Trust.LOCAL,
                     thread: String = "local") = ConversationRepository.Turn(
        id = "t1", threadId = thread, surface = surface, trust = trust,
        role = role, text = text, timestampMs = System.currentTimeMillis(),
    )

    @Test fun `buildContextLines returns recent local turns with role prefix`() {
        val turns = listOf(
            turn(ConversationRepository.Role.USER, "hola"),
            turn(ConversationRepository.Role.ASSISTANT, "buenas"),
            turn(ConversationRepository.Role.USER, "pon música"),
        )
        val lines = ConversationRepository.buildContextLines(
            turns, ConversationRepository.Trust.LOCAL, "local",
            bridgeRemoteToLocal = false, maxTurns = 10, maxChars = 2000,
        )
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("Usuario"))
        assertTrue(lines[1].startsWith("BlackClaw"))
        assertTrue(lines[2].contains("pon música"))
    }

    @Test fun `buildContextLines respects maxTurns`() {
        val turns = (1..20).map {
            turn(ConversationRepository.Role.USER, "msg $it")
        }
        val lines = ConversationRepository.buildContextLines(
            turns, ConversationRepository.Trust.LOCAL, "local",
            bridgeRemoteToLocal = false, maxTurns = 5, maxChars = 5000,
        )
        assertEquals(5, lines.size)
        assertTrue(lines.last().contains("msg 20"))
        assertTrue(lines.first().contains("msg 16"))
    }

    @Test fun `buildContextLines respects maxChars budget`() {
        val turns = (1..10).map {
            turn(ConversationRepository.Role.USER, "x".repeat(200))
        }
        val lines = ConversationRepository.buildContextLines(
            turns, ConversationRepository.Trust.LOCAL, "local",
            bridgeRemoteToLocal = false, maxTurns = 10, maxChars = 500,
        )
        assertTrue(lines.size < 10)
        assertTrue(lines.sumOf { it.length } <= 500)
    }

    @Test fun `buildContextLines isolates remote threads`() {
        val turns = listOf(
            turn(ConversationRepository.Role.USER, "local msg", thread = "local"),
            turn(ConversationRepository.Role.USER, "remote msg",
                trust = ConversationRepository.Trust.REMOTE_ISOLATED, thread = "remote:alice"),
        )
        val localLines = ConversationRepository.buildContextLines(
            turns, ConversationRepository.Trust.LOCAL, "local",
            bridgeRemoteToLocal = false, maxTurns = 10, maxChars = 2000,
        )
        assertEquals(1, localLines.size)
        assertTrue(localLines[0].contains("local msg"))

        val remoteLines = ConversationRepository.buildContextLines(
            turns, ConversationRepository.Trust.REMOTE_ISOLATED, "remote:alice",
            bridgeRemoteToLocal = false, maxTurns = 10, maxChars = 2000,
        )
        assertEquals(1, remoteLines.size)
        assertTrue(remoteLines[0].contains("remote msg"))
    }

    @Test fun `buildContextLines bridges remote to local when enabled`() {
        val turns = listOf(
            turn(ConversationRepository.Role.USER, "local msg", thread = "local"),
            turn(ConversationRepository.Role.USER, "remote msg",
                trust = ConversationRepository.Trust.REMOTE_ISOLATED, thread = "remote:bob"),
        )
        val bridged = ConversationRepository.buildContextLines(
            turns, ConversationRepository.Trust.REMOTE_ISOLATED, "remote:bob",
            bridgeRemoteToLocal = true, maxTurns = 10, maxChars = 2000,
        )
        assertEquals(2, bridged.size)
    }

    @Test fun `buildContextLines includes surface label`() {
        val turns = listOf(
            turn(ConversationRepository.Role.USER, "hola", surface = ConversationRepository.Surface.QUICK_ASSIST),
        )
        val lines = ConversationRepository.buildContextLines(
            turns, ConversationRepository.Trust.LOCAL, "local",
            bridgeRemoteToLocal = false, maxTurns = 10, maxChars = 2000,
        )
        assertTrue(lines[0].contains("quick_assist"))
    }

    @Test fun `buildContextLines deduplicates identical consecutive turns`() {
        val turns = listOf(
            turn(ConversationRepository.Role.USER, "hola"),
            turn(ConversationRepository.Role.USER, "hola"),
        )
        val lines = ConversationRepository.buildContextLines(
            turns, ConversationRepository.Trust.LOCAL, "local",
            bridgeRemoteToLocal = false, maxTurns = 10, maxChars = 2000,
        )
        assertEquals(2, lines.size)
    }
}
