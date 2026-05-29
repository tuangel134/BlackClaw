package com.blackclaw.android.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectDeviceDataGuardTest {

    @Test
    fun `clipboard request blocks completion until clipboard tool is tried`() {
        val guard = DirectDeviceDataGuard.fromTask("Read my clipboard and explain what it says")

        assertTrue(guard.shouldBlockTextOnlyCompletion())
        assertNotNull(guard.maybeBlockFinish())

        guard.recordToolAttempt("clipboard")

        assertFalse(guard.shouldBlockTextOnlyCompletion())
        assertNull(guard.maybeBlockFinish())
    }

    @Test
    fun `notification slang still activates direct data guard`() {
        val guard = DirectDeviceDataGuard.fromTask("yo whats on my notifs")

        assertTrue(guard.shouldBlockTextOnlyCompletion())
        guard.recordToolAttempt("get_notifications")
        assertFalse(guard.shouldBlockTextOnlyCompletion())
    }

    @Test
    fun `conceptual clipboard question stays out of device data guard`() {
        val guard = DirectDeviceDataGuard.fromTask("What is an Android clipboard?")

        assertFalse(guard.shouldBlockTextOnlyCompletion())
        assertNull(guard.maybeBlockFinish())
    }

    @Test
    fun `empty clipboard remains a valid answer path`() {
        val guard = DirectDeviceDataGuard.fromTask("Read my clipboard and explain what it says")

        assertTrue(guard.buildPromptSection().contains("valid result", ignoreCase = true))
        assertTrue(guard.buildCompletionCorrection().contains("valid answer", ignoreCase = true))
        assertTrue(guard.maybeBlockFinish()?.contains("valid result", ignoreCase = true) == true)
    }
}
