package com.blackclaw.android.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityPolicyTest {
    @Test fun packageNamesRejectShellSyntaxAndMalformedValues() {
        assertTrue(SecurityPolicy.isValidPackageName("com.example.adware"))
        assertFalse(SecurityPolicy.isValidPackageName("com.bad; reboot"))
        assertFalse(SecurityPolicy.isValidPackageName("com.bad/app"))
        assertFalse(SecurityPolicy.isValidPackageName(""))
    }

    @Test fun confidenceRewardsIndependentEvidence() {
        val weak = AdCulpritDetector.confidencePercent(0, false, true, false, false)
        val strong = AdCulpritDetector.confidencePercent(4, true, true, true, true)
        assertTrue(strong > weak)
        assertEquals(98, strong)
    }
}
