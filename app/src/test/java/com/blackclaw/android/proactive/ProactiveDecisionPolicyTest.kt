package com.blackclaw.android.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveDecisionPolicyTest {

    @Test
    fun invitationAloneNeverAllowsAlarm() {
        assertFalse(
            ProactiveDecisionPolicy.shouldExecute(
                "alarm",
                ProactiveDecisionPolicy.CommitmentState.PROPOSED,
                0.99,
            )
        )
    }

    @Test
    fun userDeclineOverridesModelAcceptance() {
        val context = """
            them: ¿Salimos mañana a las 6?
            me: No puedo, mañana trabajo.
        """.trimIndent()

        assertEquals(ProactiveDecisionPolicy.UserStance.DECLINED, ProactiveDecisionPolicy.userStance(context))
        assertEquals(
            ProactiveDecisionPolicy.CommitmentState.DECLINED,
            ProactiveDecisionPolicy.resolvedState(ProactiveDecisionPolicy.CommitmentState.ACCEPTED, context),
        )
        assertFalse(
            ProactiveDecisionPolicy.shouldExecute(
                "alarm",
                ProactiveDecisionPolicy.resolvedState(ProactiveDecisionPolicy.CommitmentState.ACCEPTED, context),
                0.99,
            )
        )
    }

    @Test
    fun userMaybeKeepsPlanPending() {
        val context = """
            them: La cena es mañana a las 8.
            me: Tal vez, te confirmo más tarde.
        """.trimIndent()

        assertEquals(ProactiveDecisionPolicy.UserStance.PENDING, ProactiveDecisionPolicy.userStance(context))
        assertEquals(
            ProactiveDecisionPolicy.CommitmentState.PENDING,
            ProactiveDecisionPolicy.resolvedState(ProactiveDecisionPolicy.CommitmentState.ACCEPTED, context),
        )
        assertFalse(ProactiveDecisionPolicy.shouldExecute("alarm", ProactiveDecisionPolicy.CommitmentState.PENDING, 1.0))
    }

    @Test
    fun clearUserAcceptanceAllowsHighConfidenceAlarm() {
        val context = """
            them: Nos vemos mañana a las 6 entonces.
            me: Sí voy, ahí estaré.
        """.trimIndent()

        val state = ProactiveDecisionPolicy.resolvedState(ProactiveDecisionPolicy.CommitmentState.PROPOSED, context)
        assertEquals(ProactiveDecisionPolicy.CommitmentState.ACCEPTED, state)
        assertTrue(ProactiveDecisionPolicy.shouldExecute("alarm", state, 0.93))
    }

    @Test
    fun alarmConfidenceThresholdIsStrict() {
        val state = ProactiveDecisionPolicy.CommitmentState.CONFIRMED
        assertFalse(ProactiveDecisionPolicy.shouldExecute("alarm", state, 0.819))
        assertTrue(ProactiveDecisionPolicy.shouldExecute("alarm", state, 0.82))
    }

    @Test
    fun calendarAlsoRequiresConfirmedCommitment() {
        assertFalse(ProactiveDecisionPolicy.shouldExecute("calendar", ProactiveDecisionPolicy.CommitmentState.PROPOSED, 1.0))
        assertTrue(ProactiveDecisionPolicy.shouldExecute("calendar", ProactiveDecisionPolicy.CommitmentState.CONFIRMED, 0.72))
    }

    @Test
    fun actualTransactionCanBeRecordedWithoutSocialCommitment() {
        assertTrue(ProactiveDecisionPolicy.shouldExecute("finance", ProactiveDecisionPolicy.CommitmentState.NONE, 0.72))
    }

    @Test
    fun latestNegativeUserReplyWinsOverOlderAcceptance() {
        val context = """
            them: ¿Fiesta a las 10?
            me: Sí voy.
            them: Va, nos vemos.
            me: Siempre no, no voy a poder.
        """.trimIndent()
        assertEquals(ProactiveDecisionPolicy.UserStance.DECLINED, ProactiveDecisionPolicy.userStance(context))
    }
}
