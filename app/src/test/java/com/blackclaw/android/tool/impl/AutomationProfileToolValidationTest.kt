package com.blackclaw.android.tool.impl

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationProfileToolValidationTest {
    private val tool = AutomationProfileTool()

    @Test fun `validate accepts structured maps without unchecked casts`() {
        val result = tool.execute(
            mapOf(
                "operation" to "validate",
                "name" to "Aviso manual",
                "triggers" to listOf(
                    mapOf("type" to "manual", "params" to emptyMap<String, Any>()),
                ),
                "conditions" to emptyList<Map<String, Any>>(),
                "actions" to listOf(
                    mapOf(
                        "type" to "notify",
                        "params" to mapOf("title" to "BlackClaw", "text" to "Listo"),
                    ),
                ),
            )
        )
        assertTrue(result.isSuccess)
        assertTrue(result.data.orEmpty().contains("Perfil válido"))
    }

    @Test fun `validate rejects non object action params`() {
        val result = tool.execute(
            mapOf(
                "operation" to "validate",
                "name" to "Inválido",
                "triggers" to listOf(mapOf("type" to "manual")),
                "actions" to listOf(mapOf("type" to "notify", "params" to "not-an-object")),
            )
        )
        assertFalse(result.isSuccess)
        assertTrue(result.error.orEmpty().contains("objeto JSON"))
    }

    @Test fun `create without confirmation remains a preview`() {
        val result = tool.execute(
            mapOf(
                "operation" to "create",
                "name" to "Solo vista previa",
                "triggers" to listOf(mapOf("type" to "manual")),
                "actions" to listOf(mapOf("type" to "notify", "params" to mapOf("text" to "Hola"))),
            )
        )
        assertTrue(result.isSuccess)
        assertTrue(result.data.orEmpty().contains("PREVIEW"))
        assertTrue(result.data.orEmpty().contains("no se activó nada"))
    }
}
