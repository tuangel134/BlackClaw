package com.blackclaw.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextCompressorTest {

    @Test fun `summarizeToolResult compresses success`() {
        val compressor = AgentContextCompressor(provider = { LlmProvider.OPENAI })
        val json = """{"isSuccess":true,"data":"Battery level: 73%"}"""
        val result = invokeSummarize(compressor, json)
        assertTrue(result.startsWith("✓"))
        assertTrue(result.contains("73%"))
    }

    @Test fun `summarizeToolResult compresses failure`() {
        val compressor = AgentContextCompressor(provider = { LlmProvider.OPENAI })
        val json = """{"isSuccess":false,"error":"App not installed"}"""
        val result = invokeSummarize(compressor, json)
        assertTrue(result.startsWith("✗"))
        assertTrue(result.contains("not installed"))
    }

    @Test fun `summarizeToolResult handles malformed json`() {
        val compressor = AgentContextCompressor(provider = { LlmProvider.OPENAI })
        val result = invokeSummarize(compressor, "not json at all")
        assertEquals("not json at all", result)
    }

    @Test fun `summarizeToolResult truncates long data`() {
        val compressor = AgentContextCompressor(provider = { LlmProvider.OPENAI })
        val longData = "x".repeat(200)
        val json = """{"isSuccess":true,"data":"$longData"}"""
        val result = invokeSummarize(compressor, json)
        assertTrue(result.length < 100)
        assertTrue(result.contains("..."))
    }

    private fun invokeSummarize(compressor: AgentContextCompressor, input: String): String {
        val method = AgentContextCompressor::class.java.getDeclaredMethod("summarizeToolResult", String::class.java)
        method.isAccessible = true
        return method.invoke(compressor, input) as String
    }
}
