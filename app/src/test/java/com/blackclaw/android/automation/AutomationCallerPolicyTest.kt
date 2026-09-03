package com.blackclaw.android.automation

import com.blackclaw.android.automation.AutomationCallerPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The automation entrypoints can start an arbitrary agent task, which can read the
 * screen, notifications, SMS and clipboard and then call tools. These tests pin the
 * rules that decide which caller is allowed to do that.
 */
class AutomationCallerPolicyTest {

    private companion object {
        const val SELF = "com.blackclaw.android"
        const val TASKER = "net.dinglisch.android.taskerm"
        const val EVIL = "com.evil.app"
    }

    // ── Allowlist parsing ─────────────────────────────────────────────────────

    @Test fun `empty and blank storage parse to an empty allowlist`() {
        listOf(null, "", "   ", ",", " , , ").forEach {
            assertEquals("failed for '$it'", emptySet<String>(), AutomationCallerPolicy.parseAllowlist(it))
        }
    }

    @Test fun `parsing trims whitespace around each package`() {
        assertEquals(
            setOf(TASKER, EVIL),
            AutomationCallerPolicy.parseAllowlist("  $TASKER , $EVIL  "),
        )
    }

    @Test fun `parsing drops empty entries from sloppy hand editing`() {
        assertEquals(
            setOf(TASKER),
            AutomationCallerPolicy.parseAllowlist(",,$TASKER,,"),
        )
    }

    @Test fun `serialize round-trips through parse`() {
        val original = setOf(TASKER, EVIL)
        val stored = AutomationCallerPolicy.serializeAllowlist(original)
        assertEquals(original, AutomationCallerPolicy.parseAllowlist(stored))
    }

    @Test fun `serialize drops blanks and duplicates`() {
        val stored = AutomationCallerPolicy.serializeAllowlist(
            listOf(TASKER, "", "  ", TASKER, " $TASKER "),
        )
        assertEquals(TASKER, stored)
    }

    // ── Decisions ─────────────────────────────────────────────────────────────

    @Test fun `allowlisted caller is permitted`() {
        assertEquals(
            Decision.ALLOW,
            AutomationCallerPolicy.decide(TASKER, SELF, setOf(TASKER)),
        )
    }

    @Test fun `caller outside a configured allowlist is refused`() {
        assertEquals(
            Decision.DENY_NOT_ALLOWLISTED,
            AutomationCallerPolicy.decide(EVIL, SELF, setOf(TASKER)),
        )
    }

    /**
     * The regression this whole item is about: enabling automation for one app must
     * not enable it for the next app the user installs.
     */
    @Test fun `allowlisting one app does not authorize a different app`() {
        val allowlist = setOf(TASKER)
        assertEquals(Decision.ALLOW, AutomationCallerPolicy.decide(TASKER, SELF, allowlist))
        assertEquals(
            Decision.DENY_NOT_ALLOWLISTED,
            AutomationCallerPolicy.decide("com.installed.later", SELF, allowlist),
        )
    }

    @Test fun `unknown caller is refused once an allowlist is configured`() {
        assertEquals(
            Decision.DENY_UNIDENTIFIED_CALLER,
            AutomationCallerPolicy.decide(null, SELF, setOf(TASKER)),
        )
    }

    @Test fun `blank caller is treated the same as a missing one`() {
        listOf("", "   ").forEach {
            assertEquals(
                "failed for '$it'",
                Decision.DENY_UNIDENTIFIED_CALLER,
                AutomationCallerPolicy.decide(it, SELF, setOf(TASKER)),
            )
        }
    }

    @Test fun `an empty allowlist denies every third-party caller without a token`() {
        assertEquals(
            Decision.DENY_UNIDENTIFIED_CALLER,
            AutomationCallerPolicy.decide(null, SELF, emptySet()),
        )
        listOf(TASKER, EVIL).forEach {
            assertEquals(
                "failed for '$it'",
                Decision.DENY_NOT_ALLOWLISTED,
                AutomationCallerPolicy.decide(it, SELF, emptySet()),
            )
        }
    }

    @Test fun `a valid automation token authorizes an unidentified caller`() {
        assertEquals(
            Decision.ALLOW,
            AutomationCallerPolicy.decide(
                callingPackage = null,
                selfPackage = SELF,
                allowlist = emptySet(),
                tokenPresented = true,
            ),
        )
    }

    @Test fun `a valid automation token also authorizes a non-allowlisted identified caller`() {
        assertEquals(
            Decision.ALLOW,
            AutomationCallerPolicy.decide(
                callingPackage = EVIL,
                selfPackage = SELF,
                allowlist = setOf(TASKER),
                tokenPresented = true,
            ),
        )
    }

    @Test fun `our own package is always permitted`() {
        assertEquals(
            Decision.ALLOW,
            AutomationCallerPolicy.decide(SELF, SELF, emptySet()),
        )
    }

    /**
     * Android package names are case-sensitive, so `com.Evil` is a different app
     * from `com.evil`. Case-insensitive matching would hand the attacker's package
     * the victim's grant.
     */
    @Test fun `package matching is case-sensitive`() {
        assertEquals(
            Decision.DENY_NOT_ALLOWLISTED,
            AutomationCallerPolicy.decide("Com.Evil.App", SELF, setOf(EVIL)),
        )
    }

    @Test fun `surrounding whitespace on the caller does not defeat the check`() {
        assertEquals(
            Decision.ALLOW,
            AutomationCallerPolicy.decide("  $TASKER  ", SELF, setOf(TASKER)),
        )
    }

    @Test fun `a prefix of an allowlisted package is not accepted`() {
        assertEquals(
            Decision.DENY_NOT_ALLOWLISTED,
            AutomationCallerPolicy.decide("net.dinglisch", SELF, setOf(TASKER)),
        )
    }

    @Test fun `a package that merely contains an allowlisted name is not accepted`() {
        assertEquals(
            Decision.DENY_NOT_ALLOWLISTED,
            AutomationCallerPolicy.decide("$TASKER.evil", SELF, setOf(TASKER)),
        )
    }

    // ── Messaging ─────────────────────────────────────────────────────────────

    @Test fun `denial message names the rejected package so the user can act`() {
        val message = AutomationCallerPolicy.denialMessage(Decision.DENY_NOT_ALLOWLISTED, EVIL)
        assertTrue(message, message.contains(EVIL))
    }

    @Test fun `allow produces no denial message`() {
        assertEquals("", AutomationCallerPolicy.denialMessage(Decision.ALLOW, TASKER))
    }
}
