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

    @Test fun `validator accepts stable semantic place references`() {
        val profile = AutomationProfileStore.Profile(
            id = "semantic-place", name = "Llegar a casa de mi novia",
            triggers = listOf(AutomationProfileStore.Trigger(
                AutomationProfileStore.TriggerType.LOCATION_ENTER,
                mapOf("place_id" to "place123", "place" to "casa de mi novia"),
            )),
            conditions = listOf(AutomationProfileStore.Condition(
                AutomationProfileStore.ConditionType.LOCATION,
                mapOf("place_id" to "place123", "inside" to true),
            )),
            actions = listOf(AutomationProfileStore.Action(
                AutomationProfileStore.ActionType.NOTIFY,
                mapOf("text" to "Llegaste"),
            )),
        )
        assertTrue(AutomationProfileValidator.validate(profile).isEmpty())
    }

    @Test fun `interval trigger validates bounded cadence`() {
        fun profile(minutes: Int) = AutomationProfileStore.Profile(
            id = "interval-$minutes", name = "Intervalo",
            triggers = listOf(AutomationProfileStore.Trigger(
                AutomationProfileStore.TriggerType.INTERVAL,
                mapOf("minutes" to minutes),
            )),
            actions = listOf(AutomationProfileStore.Action(
                AutomationProfileStore.ActionType.NOTIFY,
                mapOf("text" to "tick"),
            )),
        )
        assertTrue(AutomationProfileValidator.validate(profile(15)).isEmpty())
        assertTrue(AutomationProfileValidator.validate(profile(0)).any { "minutes" in it })
        assertTrue(AutomationProfileValidator.validate(profile(10_081)).any { "minutes" in it })
    }

    @Test fun `condition logic survives JSON round trip`() {
        val profile = AutomationProfileStore.Profile(
            id = "logic", name = "Cualquiera",
            triggers = listOf(AutomationProfileStore.Trigger(AutomationProfileStore.TriggerType.MANUAL)),
            conditions = listOf(
                AutomationProfileStore.Condition(AutomationProfileStore.ConditionType.CHARGING, mapOf("value" to true)),
                AutomationProfileStore.Condition(AutomationProfileStore.ConditionType.POWER_SAVE, mapOf("value" to false)),
            ),
            conditionLogic = AutomationProfileStore.ConditionLogic.ANY,
            actions = listOf(AutomationProfileStore.Action(AutomationProfileStore.ActionType.NOTIFY, mapOf("text" to "ok"))),
        )
        assertEquals(
            AutomationProfileStore.ConditionLogic.ANY,
            AutomationProfileStore.Profile.fromJson(profile.toJson()).conditionLogic,
        )
    }

    @Test fun `geofence request id is deterministic and does not expose coordinates`() {
        val target = AutomationLocationTarget.Target(28.123456, -106.654321, 175f)
        val id = AutomationLocationTarget.requestId(target)
        assertEquals(id, AutomationLocationTarget.requestId(target.copy()))
        assertTrue(id.startsWith("bcg:"))
        assertFalse(id.contains("28.123456"))
        assertFalse(id.contains("106.654321"))
    }

    @Test fun `saved place normalization is accent and punctuation insensitive`() {
        assertEquals("casa de mi novia", SavedPlaceStore.normalize("  Casa de mi Nóvia!!! "))
        assertEquals("mi cuarto", SavedPlaceStore.normalize("MI---CUARTO"))
    }

    @Test fun `new state triggers match their values`() {
        val trigger = AutomationProfileStore.Trigger(
            AutomationProfileStore.TriggerType.POWER_SAVE,
            mapOf("value" to true),
        )
        assertTrue(AutomationProfileEngine.matches(trigger, AutomationProfileEngine.Event(
            AutomationProfileStore.TriggerType.POWER_SAVE, mapOf("value" to "true"))))
        assertFalse(AutomationProfileEngine.matches(trigger, AutomationProfileEngine.Event(
            AutomationProfileStore.TriggerType.POWER_SAVE, mapOf("value" to "false"))))
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
