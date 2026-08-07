package com.blackclaw.android.emergency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyCommandParserTest {
    @Test fun `both camera variants are reinforced`() {
        listOf(
            "activa modo discreto con ambas cámaras",
            "inicia modo discreto con las dos camaras",
            "activar modo discreto con frontal y trasera",
            "enciende emergencia con 2 cámaras",
        ).forEach { command ->
            assertEquals(command, EmergencyCameras.BOTH, EmergencyCommandParser.parse(command)?.cameras)
        }
    }

    @Test fun `discreet defaults to rear camera and stays silent`() {
        val parsed = EmergencyCommandParser.parse("activa modo discreto")!!
        assertEquals(EmergencyMode.DISCREET, parsed.mode)
        assertEquals(EmergencyCameras.BACK, parsed.cameras)
        assertTrue(parsed.silent)
        assertTrue(parsed.sendLocation)
    }

    @Test fun `location can be explicitly disabled`() {
        val parsed = EmergencyCommandParser.parse("activa modo discreto frontal sin ubicación")!!
        assertEquals(EmergencyCameras.FRONT, parsed.cameras)
        assertFalse(parsed.sendLocation)
    }

    @Test fun `ordinary conversation is not intercepted`() {
        assertNull(EmergencyCommandParser.parse("¿Qué significa modo de emergencia?"))
    }
}
