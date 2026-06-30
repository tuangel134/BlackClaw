package com.blackclaw.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure compaction logic in [ContextCompactor].
 */
class ContextCompactorTest {

    @Test
    fun shortResultIsUnchanged() {
        val short = """{"isSuccess":true,"data":"ok"}"""
        assertEquals(short, ContextCompactor.compactToolResult("tap", short))
    }

    @Test
    fun errorResultBecomesCompactPrefix() {
        val err = """{"isSuccess":false,"error":"${"x".repeat(250)}"}"""
        val out = ContextCompactor.compactToolResult("open_app", err)
        assertTrue(out.startsWith("ERR:"))
        assertTrue(out.length < err.length)
    }

    @Test
    fun successEnvelopeIsStripped() {
        val payload = "Battery is at 73% and charging. ".repeat(10)
        val json = """{"isSuccess":true,"data":"$payload"}"""
        val out = ContextCompactor.compactToolResult("get_device_info", json)
        // The {"isSuccess"...} wrapper should be gone
        assertFalse(out.contains("isSuccess"))
        assertTrue(out.contains("Battery"))
    }

    @Test
    fun repetitiveListIsCollapsed() {
        // 20 structurally-similar contact rows
        val rows = (1..20).joinToString("\n") { "[n$it] \"Contacto $it\" tap ($it,$it)" }
        val collapsed = ContextCompactor.collapseRepetitiveLines(rows)
        assertTrue("should mark omitted rows", collapsed.contains("similares omitidas"))
        assertTrue("should be shorter", collapsed.length < rows.length)
        // Head and tail rows preserved
        assertTrue(collapsed.contains("[n1]"))
        assertTrue(collapsed.contains("[n20]"))
    }

    @Test
    fun shortListIsNotCollapsed() {
        val rows = (1..4).joinToString("\n") { "[n$it] \"Item $it\" tap ($it,$it)" }
        val out = ContextCompactor.collapseRepetitiveLines(rows)
        assertFalse(out.contains("omitidas"))
        assertEquals(rows, out)
    }

    @Test
    fun differentShapesAreNotCollapsedTogether() {
        // Mix of tappable and editable rows + a header — distinct signatures
        val text = buildString {
            append("[n1] \"Header\" (0,0)\n")
            repeat(10) { append("[n${it + 2}] \"row$it\" tap (0,$it)\n") }
            append("[n20] \"field\" edit (0,99)")
        }
        val out = ContextCompactor.collapseRepetitiveLines(text)
        // The tappable run collapses, but header and edit field survive
        assertTrue(out.contains("Header"))
        assertTrue(out.contains("edit"))
    }

    @Test
    fun nonScreenDataIsNotTreatedAsTree() {
        val json = """{"isSuccess":true,"data":"un texto normal sin nodos de pantalla aquí, solo prosa larga ${"y más ".repeat(40)}"}"""
        val out = ContextCompactor.compactToolResult("web_search", json)
        assertFalse(out.contains("omitidas"))
        assertFalse(out.contains("isSuccess"))
    }
}
