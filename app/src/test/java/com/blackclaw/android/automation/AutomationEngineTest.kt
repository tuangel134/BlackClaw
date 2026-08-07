package com.blackclaw.android.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationEngineTest {
    private fun rule(match: String = "Novia", pkg: String = "com.whatsapp", enabled: Boolean = true) =
        AutomationRuleStore.Rule("1", "Despertar", enabled, AutomationRuleStore.Trigger.NOTIFICATION,
            match, pkg, "despiértame")

    @Test fun `notification rules match contact case insensitively`() {
        assertTrue(AutomationEngine.notificationMatches(rule(), "com.whatsapp", "Mi NOVIA", "Hola"))
    }

    @Test fun `notification rules honor package and enabled state`() {
        assertFalse(AutomationEngine.notificationMatches(rule(), "org.telegram.messenger", "Novia", "Hola"))
        assertFalse(AutomationEngine.notificationMatches(rule(enabled = false), "com.whatsapp", "Novia", "Hola"))
    }
}
