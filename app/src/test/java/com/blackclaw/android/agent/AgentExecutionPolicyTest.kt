package com.blackclaw.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentExecutionPolicyTest {
    @Test fun `action tools are identified`() {
        assertTrue(AgentExecutionPolicy.isActionTool("tap"))
        assertTrue(AgentExecutionPolicy.isActionTool("open_app"))
        assertFalse(AgentExecutionPolicy.isActionTool("get_device_info"))
    }

    @Test fun `settle times preserve fast default and navigation buckets`() {
        assertEquals(250L, AgentExecutionPolicy.settleTimeForTool("input_text"))
        assertEquals(800L, AgentExecutionPolicy.settleTimeForTool("open_app"))
        assertEquals(400L, AgentExecutionPolicy.settleTimeForTool("tap"))
    }
}
