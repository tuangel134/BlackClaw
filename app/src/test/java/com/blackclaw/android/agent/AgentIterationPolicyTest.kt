package com.blackclaw.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentIterationPolicyTest {
    @Test
    fun `default window allows two automatic continuations`() {
        assertEquals(60, AgentIterationPolicy.window(60))
        assertEquals(180, AgentIterationPolicy.hardLimit(60))
        assertTrue(AgentIterationPolicy.isCheckpoint(60, 60))
        assertTrue(AgentIterationPolicy.isCheckpoint(120, 60))
        assertFalse(AgentIterationPolicy.isCheckpoint(180, 60))
    }

    @Test
    fun `invalid configuration is normalized and capped`() {
        assertEquals(1, AgentIterationPolicy.window(0))
        assertEquals(3, AgentIterationPolicy.hardLimit(0))
        assertEquals(240, AgentIterationPolicy.hardLimit(200))
    }
}
