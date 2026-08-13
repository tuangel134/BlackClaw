package com.blackclaw.android.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiActionPatternDetectorTest {
    @Test
    fun `detects repeated form clicks while treating text as slots`() {
        val detector = UiActionPatternDetector()
        val first = listOf(
            "tap_node" to mapOf<String, Any>("node_id" to "add"),
            "input_text" to mapOf<String, Any>("node_id" to "name", "text" to "Ana"),
            "tap_node" to mapOf<String, Any>("node_id" to "phone"),
            "input_text" to mapOf<String, Any>("node_id" to "phone", "text" to "5551"),
            "tap_node" to mapOf<String, Any>("node_id" to "save"),
        )
        first.forEach { (tool, params) -> detector.record(tool, params) }

        var match: UiActionPatternDetector.Match? = null
        first.forEach { (tool, params) ->
            match = detector.record(tool, params + if ("text" in params) mapOf("text" to "nuevo") else emptyMap()) ?: match
        }

        assertNotNull(match)
        val hint = match!!.buildHint()
        assertTrue(hint.contains("execute_plan"))
        assertTrue(hint.contains("<VALUE_1>"))
        assertFalse(hint.contains("Ana"))
        assertFalse(hint.contains("5551"))
    }

    @Test
    fun `does not optimize repeated taps without variable form data`() {
        val detector = UiActionPatternDetector()
        repeat(2) {
            detector.record("tap_node", mapOf("node_id" to "same"))
            detector.record("tap_node", mapOf("node_id" to "other"))
        }

        assertTrue(detector.record("tap_node", mapOf("node_id" to "same")) == null)
    }
}
