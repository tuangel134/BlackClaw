package com.blackclaw.android.tool.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ExecutePlanTool.validate] — the pure plan-validation guard that
 * keeps multi-step plans bounded and non-recursive. No Android needed.
 */
class ExecutePlanToolTest {

    private val tool = ExecutePlanTool()

    private fun step(name: String) = ExecutePlanTool.Step(name, emptyMap())

    @Test
    fun validPlanPasses() {
        val plan = listOf(step("open_app"), step("wait"), step("input_text"))
        assertNull(tool.validate(plan))
    }

    @Test
    fun emptyPlanRejected() {
        assertNotNull(tool.validate(emptyList()))
    }

    @Test
    fun tooLongPlanRejected() {
        val plan = (1..7).map { step("wait") }
        val err = tool.validate(plan)
        assertNotNull(err)
        assertTrue(err!!.contains("demasiado largo"))
    }

    @Test
    fun sixStepsIsTheLimit() {
        val plan = (1..6).map { step("wait") }
        assertNull(tool.validate(plan))
    }

    @Test
    fun nestedExecutePlanRejected() {
        val plan = listOf(step("open_app"), step("execute_plan"))
        val err = tool.validate(plan)
        assertNotNull(err)
        assertTrue(err!!.contains("execute_plan"))
    }

    @Test
    fun blankToolNameRejected() {
        val plan = listOf(step("open_app"), step(""))
        assertNotNull(tool.validate(plan))
    }

    @Test
    fun stepCarriesVerificationFields() {
        val s = ExecutePlanTool.Step("open_app", emptyMap(), expect = "ok", verifyText = "Chats")
        assertEquals("ok", s.expect)
        assertEquals("Chats", s.verifyText)
    }

    @Test
    fun summarizeScreenCollapsesWhitespace() {
        val out = tool.summarizeScreen("Chats\n\n  Ana   Bob\tCarlos")
        assertEquals("Chats Ana Bob Carlos", out)
    }

    @Test
    fun summarizeScreenTruncates() {
        val out = tool.summarizeScreen("x".repeat(1000))
        assertTrue(out.length <= 300)
    }
}
