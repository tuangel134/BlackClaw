package com.blackclaw.android.tool.guard

import com.blackclaw.android.tool.guard.ToolRiskPolicy.Decision
import com.blackclaw.android.tool.guard.ToolRiskPolicy.Origin
import com.blackclaw.android.tool.guard.ToolRiskPolicy.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that keeps arbitrary-command tools out of reach of untrusted input.
 *
 * These assertions are the security contract: a regression here is invisible during
 * normal use and only shows up as a compromise, so every branch is pinned.
 */
class ToolRiskPolicyTest {

    private val privileged = listOf(
        "shell_exec", "terminal", "remote_shell", "remote_connect", "add_smart_device",
    )

    // ── Classification ────────────────────────────────────────────────────────

    @Test fun `arbitrary-command tools are privileged`() {
        privileged.forEach { assertEquals(it, Tier.PRIVILEGED, ToolRiskPolicy.classify(it)) }
    }

    @Test fun `bounded but user-visible tools are sensitive`() {
        listOf("send_message", "send_sms", "make_call", "force_stop_app", "uninstall_app")
            .forEach { assertEquals(it, Tier.SENSITIVE, ToolRiskPolicy.classify(it)) }
    }

    @Test fun `ordinary tools are safe`() {
        listOf("tap", "swipe", "get_screen_info", "read_screen_ocr", "finish", "wait")
            .forEach { assertEquals(it, Tier.SAFE, ToolRiskPolicy.classify(it)) }
    }

    @Test fun `coordinate-driven privileged helpers stay safe so game automation keeps working`() {
        // These do go through a shell, but every interpolated value passes requireInt,
        // so they cannot express anything beyond a touch event. Gating them would
        // break game macros for no security gain.
        listOf("fast_tap", "fast_swipe", "tap_burst", "game_observe", "game_run_macro")
            .forEach { assertEquals(it, Tier.SAFE, ToolRiskPolicy.classify(it)) }
    }

    @Test fun `an unknown tool name is safe by default`() {
        // Unknown names are rejected upstream by ToolRegistry; this policy must not
        // accidentally deny every future tool.
        assertEquals(Tier.SAFE, ToolRiskPolicy.classify("some_future_tool"))
    }

    // ── The rule that matters: remote cannot reach a shell ────────────────────

    @Test fun `privileged tools are denied for remote requests even when armed`() {
        privileged.forEach { tool ->
            val decision = ToolRiskPolicy.evaluate(tool, Origin.REMOTE, privilegedArmed = true)
            assertTrue("$tool should be denied from REMOTE", decision is Decision.Deny)
        }
    }

    @Test fun `privileged tools are denied for unattributed requests`() {
        // Fails closed: DebugTaskReceiver, the config server debug endpoint and any
        // future caller that forgets to set provenance land here.
        privileged.forEach { tool ->
            val decision = ToolRiskPolicy.evaluate(tool, Origin.UNKNOWN, privilegedArmed = true)
            assertTrue("$tool should be denied from UNKNOWN", decision is Decision.Deny)
        }
    }

    @Test fun `privileged tools are denied for automation`() {
        privileged.forEach { tool ->
            val decision = ToolRiskPolicy.evaluate(tool, Origin.AUTOMATION, privilegedArmed = true)
            assertTrue("$tool should be denied from AUTOMATION", decision is Decision.Deny)
        }
    }

    // ── Local requires a recent opt-in ────────────────────────────────────────

    @Test fun `privileged tools are denied locally until the user arms them`() {
        privileged.forEach { tool ->
            val decision = ToolRiskPolicy.evaluate(tool, Origin.LOCAL, privilegedArmed = false)
            assertTrue("$tool should be denied unarmed", decision is Decision.Deny)
        }
    }

    @Test fun `privileged tools are allowed locally once armed`() {
        privileged.forEach { tool ->
            assertEquals(
                "$tool should be allowed locally when armed",
                Decision.Allow,
                ToolRiskPolicy.evaluate(tool, Origin.LOCAL, privilegedArmed = true),
            )
        }
    }

    // ── Everything else stays usable ──────────────────────────────────────────

    @Test fun `remote control of messaging still works — that is the point of channels`() {
        listOf("send_message", "send_sms", "make_call").forEach { tool ->
            assertEquals(
                tool,
                Decision.Allow,
                ToolRiskPolicy.evaluate(tool, Origin.REMOTE, privilegedArmed = false),
            )
        }
    }

    @Test fun `safe tools are allowed from every origin regardless of arming`() {
        Origin.entries.forEach { origin ->
            listOf(true, false).forEach { armed ->
                assertEquals(
                    "tap from $origin armed=$armed",
                    Decision.Allow,
                    ToolRiskPolicy.evaluate("tap", origin, armed),
                )
            }
        }
    }

    @Test fun `sensitive tools are allowed from every origin regardless of arming`() {
        Origin.entries.forEach { origin ->
            listOf(true, false).forEach { armed ->
                assertEquals(
                    "force_stop_app from $origin armed=$armed",
                    Decision.Allow,
                    ToolRiskPolicy.evaluate("force_stop_app", origin, armed),
                )
            }
        }
    }

    // ── Denial messages must be actionable ────────────────────────────────────

    @Test fun `denial explains what to do next`() {
        val remote = ToolRiskPolicy.evaluate("shell_exec", Origin.REMOTE, true) as Decision.Deny
        assertTrue(remote.reason.contains("shell_exec"))
        assertTrue(remote.reason.isNotBlank())

        val unarmed = ToolRiskPolicy.evaluate("shell_exec", Origin.LOCAL, false) as Decision.Deny
        // Names the setting the user has to touch, otherwise the error is a dead end.
        assertTrue(unarmed.reason.contains("Modo Pro"))
    }

    // ── Auditing ──────────────────────────────────────────────────────────────

    @Test fun `privileged and sensitive tools are audited, safe ones are not`() {
        assertTrue(ToolRiskPolicy.shouldAudit("shell_exec"))
        assertTrue(ToolRiskPolicy.shouldAudit("send_sms"))
        assertFalse(ToolRiskPolicy.shouldAudit("tap"))
        assertFalse(ToolRiskPolicy.shouldAudit("get_screen_info"))
    }
}
