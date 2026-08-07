package com.blackclaw.android.emergency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyConfigTest {
    @Test fun normalizesInternationalPhoneWithoutLosingPlus() {
        assertEquals("+526140001234", EmergencyConfig.normalizePhone("+52 (614) 000-1234"))
        assertEquals("6140001234", EmergencyConfig.normalizePhone("614 000 1234"))
    }

    @Test fun alertIncludesLocationOnlyWhenAvailable() {
        val located = EmergencyConfig.formatAlert("Ayuda", "Ana", 28.632, -106.069, 0L)
        assertTrue(located.contains("https://maps.google.com/?q=28.632,-106.069"))
        assertTrue(located.contains("Ayuda"))
        assertTrue(located.contains("Ana"))

        val missing = EmergencyConfig.formatAlert("Ayuda", "Ana", null, null, 0L)
        assertTrue(missing.contains("no disponible"))
        assertFalse(missing.contains("maps.google.com"))
    }

    @Test fun evidenceSegmentNamesCannotEscapePrivateVault() {
        val name = EmergencyEvidenceVault.safeSegmentName("../../2026 07:18!!")
        assertFalse(name.contains(".."))
        assertFalse(name.contains('/'))
        assertTrue(name.startsWith("emergency_"))
    }

    @Test fun periodicLocationUpdateIsClearlyStructured() {
        val update = EmergencyConfig.buildLocationUpdate(28.632, -106.069, 0L)
        assertTrue(update.contains("Actualización de ubicación"))
        assertTrue(update.contains("https://maps.google.com/?q=28.632,-106.069"))
    }
}
