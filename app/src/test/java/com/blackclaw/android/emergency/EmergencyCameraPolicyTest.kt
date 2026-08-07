package com.blackclaw.android.emergency

import org.junit.Assert.assertEquals
import org.junit.Test

class EmergencyCameraPolicyTest {
    @Test fun `both cameras are selected only when platform declares concurrency`() {
        val capability = EmergencyCameraController.Capability("front-id", "back-id", true)
        assertEquals(
            listOf("front-id" to "front", "back-id" to "back"),
            EmergencyCameraController.select(EmergencyCameras.BOTH, capability),
        )
    }

    @Test fun `both request falls back honestly to rear camera`() {
        val capability = EmergencyCameraController.Capability("front-id", "back-id", false)
        assertEquals(
            listOf("back-id" to "back"),
            EmergencyCameraController.select(EmergencyCameras.BOTH, capability),
        )
    }

    @Test fun `missing rear camera falls back to available front camera`() {
        val capability = EmergencyCameraController.Capability("front-id", null, false)
        assertEquals(
            listOf("front-id" to "front"),
            EmergencyCameraController.select(EmergencyCameras.BOTH, capability),
        )
    }
}
