package com.blackclaw.android.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationProfileEngineTest {
    @Test fun `notification trigger matches package and text`() {
        val trigger = AutomationProfileStore.Trigger(
            AutomationProfileStore.TriggerType.NOTIFICATION,
            mapOf("package" to "com.whatsapp", "match" to "cita"),
        )
        assertTrue(AutomationProfileEngine.matches(trigger, AutomationProfileEngine.Event(
            AutomationProfileStore.TriggerType.NOTIFICATION,
            mapOf("package" to "com.whatsapp", "title" to "Ana", "text" to "¿cita a las 8?"),
        )))
        assertFalse(AutomationProfileEngine.matches(trigger, AutomationProfileEngine.Event(
            AutomationProfileStore.TriggerType.NOTIFICATION,
            mapOf("package" to "org.telegram.messenger", "title" to "Ana", "text" to "cita"),
        )))
    }

    @Test fun `battery trigger enforces range`() {
        val trigger = AutomationProfileStore.Trigger(
            AutomationProfileStore.TriggerType.BATTERY,
            mapOf("min" to 0, "max" to 20),
        )
        assertTrue(AutomationProfileEngine.matches(trigger, AutomationProfileEngine.Event(
            AutomationProfileStore.TriggerType.BATTERY, mapOf("level" to "15"))))
        assertFalse(AutomationProfileEngine.matches(trigger, AutomationProfileEngine.Event(
            AutomationProfileStore.TriggerType.BATTERY, mapOf("level" to "80"))))
    }

    @Test fun `webhook trigger requires the exact token`() {
        val trigger = AutomationProfileStore.Trigger(
            AutomationProfileStore.TriggerType.WEBHOOK,
            mapOf("token" to "secret-123"),
        )
        assertTrue(AutomationProfileEngine.matches(trigger, AutomationProfileEngine.Event(
            AutomationProfileStore.TriggerType.WEBHOOK, mapOf("token" to "secret-123"))))
        assertFalse(AutomationProfileEngine.matches(trigger, AutomationProfileEngine.Event(
            AutomationProfileStore.TriggerType.WEBHOOK, mapOf("token" to "wrong"))))
        assertFalse(AutomationProfileEngine.matches(trigger, AutomationProfileEngine.Event(
            AutomationProfileStore.TriggerType.WEBHOOK, emptyMap())))
    }

    @Test fun `webhook trigger with no configured secret never matches`() {
        val trigger = AutomationProfileStore.Trigger(
            AutomationProfileStore.TriggerType.WEBHOOK,
            mapOf("token" to ""),
        )
        assertFalse(AutomationProfileEngine.matches(trigger, AutomationProfileEngine.Event(
            AutomationProfileStore.TriggerType.WEBHOOK, mapOf("token" to ""))))
    }

    @Test fun `validator rejects unbounded or privileged profiles`() {
        val profile = AutomationProfileStore.Profile(
            id = "x", name = "Shell", enabled = true,
            triggers = listOf(AutomationProfileStore.Trigger(AutomationProfileStore.TriggerType.MANUAL)),
            actions = listOf(AutomationProfileStore.Action(
                AutomationProfileStore.ActionType.TOOL,
                mapOf("tool" to "terminal"),
            )),
        )
        assertTrue(AutomationProfileValidator.validate(profile).any { "privilegiado" in it })
    }

    @Test fun `validator rejects invalid time and location parameters`() {
        val timeProfile = AutomationProfileStore.Profile(
            id = "bad-time", name = "Hora inválida",
            triggers = listOf(AutomationProfileStore.Trigger(
                AutomationProfileStore.TriggerType.TIME,
                mapOf("hour" to 25, "minute" to 70),
            )),
            actions = listOf(AutomationProfileStore.Action(
                AutomationProfileStore.ActionType.NOTIFY,
                mapOf("text" to "aviso"),
            )),
        )
        val locationProfile = timeProfile.copy(
            id = "bad-location", name = "Ubicación inválida",
            triggers = listOf(AutomationProfileStore.Trigger(
                AutomationProfileStore.TriggerType.LOCATION_ENTER,
                mapOf("latitude" to 91, "longitude" to 181),
            )),
        )
        assertTrue(AutomationProfileValidator.validate(timeProfile).any { "hour" in it })
        assertTrue(AutomationProfileValidator.validate(locationProfile).any { "latitude" in it })
    }

    @Test fun `profile JSON keeps nested tool parameters`() {
        val profile = AutomationProfileStore.Profile(
            id = "nested", name = "Volumen",
            triggers = listOf(AutomationProfileStore.Trigger(AutomationProfileStore.TriggerType.MANUAL)),
            actions = listOf(AutomationProfileStore.Action(
                AutomationProfileStore.ActionType.TOOL,
                mapOf("tool" to "set_volume", "params" to mapOf("stream" to "music", "level" to 4)),
            )),
        )
        val restored = AutomationProfileStore.Profile.fromJson(profile.toJson())
        @Suppress("UNCHECKED_CAST")
        val nested = restored.actions.single().params["params"] as Map<String, Any>
        assertEquals("music", nested["stream"])
        assertEquals(4, (nested["level"] as Number).toInt())
    }
}
