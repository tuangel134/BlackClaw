package com.blackclaw.android.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameControlPolicyTest {
    @Test fun normalizedCoordinatesMapAcrossScreenSizes() {
        assertEquals(0, GameControlPolicy.toPixel(0, 2400))
        assertEquals(1200, GameControlPolicy.toPixel(500, 2400))
        assertEquals(2399, GameControlPolicy.toPixel(1000, 2400))
    }

    @Test(expected = IllegalArgumentException::class)
    fun coordinatesOutsideRangeAreRejected() {
        GameControlPolicy.toPixel(1001, 1080)
    }

    @Test fun riskyGameActionsRequireConfirmation() {
        assertTrue(GameControlPolicy.requiresConfirmation("atacar al siguiente enemigo"))
        assertTrue(GameControlPolicy.requiresConfirmation("upgrade town hall"))
        assertFalse(GameControlPolicy.requiresConfirmation("mover el mapa a la izquierda"))
    }

    @Test fun frameDifferenceUsesHammingDistance() {
        val dark = GameControlPolicy.perceptualHash(IntArray(64) { if (it < 32) 0 else 255 })
        val inverse = GameControlPolicy.perceptualHash(IntArray(64) { if (it < 32) 255 else 0 })
        assertEquals(100, GameControlPolicy.changedPercent(dark, inverse))
        assertEquals(0, GameControlPolicy.changedPercent(dark, dark))
    }

    @Test fun sessionRejectsBlindStaleAndCrossAppActions() {
        GameControlSession.observe("com.supercell.clashofclans", 1L, nowMs = 1_000L)
        assertEquals(null, GameControlSession.validate("com.supercell.clashofclans", nowMs = 2_000L))
        assertTrue(GameControlSession.validate("com.supercell.clashroyale", nowMs = 2_000L)!!.contains("cambió"))
        assertTrue(GameControlSession.validate("com.supercell.clashofclans", nowMs = 40_000L)!!.contains("caducó"))
    }
}
