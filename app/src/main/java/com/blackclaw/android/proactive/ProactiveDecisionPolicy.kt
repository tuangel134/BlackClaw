package com.blackclaw.android.proactive

/**
 * Deterministic guardrails around the proactive LLM.
 *
 * A time mention is evidence of a possible plan, never proof that the user accepted it.
 * Timed autonomous actions require a confirmed commitment plus a minimum confidence.
 */
object ProactiveDecisionPolicy {
    enum class CommitmentState {
        NONE, PROPOSED, PENDING, ACCEPTED, CONFIRMED, RESCHEDULED, DECLINED, CANCELLED, EXPIRED;

        companion object {
            fun parse(raw: String?): CommitmentState = when (raw?.trim()?.lowercase()) {
                "proposed", "proposal", "invited", "invitation" -> PROPOSED
                "pending", "maybe", "uncertain" -> PENDING
                "accepted", "accept", "agreed" -> ACCEPTED
                "confirmed", "firm" -> CONFIRMED
                "rescheduled", "moved", "changed" -> RESCHEDULED
                "declined", "rejected", "refused" -> DECLINED
                "cancelled", "canceled" -> CANCELLED
                "expired" -> EXPIRED
                else -> NONE
            }
        }
    }

    enum class UserStance { UNKNOWN, ACCEPTED, DECLINED, PENDING }

    private val declinePhrases = listOf(
        "no puedo", "no voy", "no podré", "no podre", "no alcanzo", "no me da tiempo",
        "no gracias", "mejor no", "siempre no", "no cuentes conmigo", "no iré", "no ire",
        "cancela", "cancelado", "cancelada", "se canceló", "se cancelo", "no asistiré", "no asistire",
        "can't make it", "cant make it", "i can't", "i cant", "not going", "won't go", "wont go",
        "cancel it", "cancelled", "canceled", "count me out"
    )
    private val pendingPhrases = listOf(
        "tal vez", "quizá", "quizas", "quizás", "a lo mejor", "puede ser", "veré", "vere",
        "te confirmo", "déjame ver", "dejame ver", "maybe", "perhaps", "i'll see", "ill see",
        "not sure", "let me check"
    )
    private val acceptPhrases = listOf(
        "sí voy", "si voy", "ahí estaré", "ahi estare", "nos vemos", "confirmado", "confirmada",
        "de acuerdo", "cuenta conmigo", "jalo", "va,", "va ", "perfecto", "listo", "acepto",
        "i'll be there", "ill be there", "see you", "confirmed", "sounds good", "i'm in", "im in"
    )

    /** Inspect only user-authored lines ("me:") from a captured messaging conversation. */
    fun userStance(conversationContext: String?): UserStance {
        if (conversationContext.isNullOrBlank()) return UserStance.UNKNOWN
        val userLines = conversationContext.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("me:", ignoreCase = true) }
            .map { it.substringAfter(':').trim().lowercase() }
            .filter { it.isNotBlank() }
            .toList()
        if (userLines.isEmpty()) return UserStance.UNKNOWN
        val recent = userLines.takeLast(4).asReversed()
        for (line in recent) {
            if (declinePhrases.any { line.contains(it) }) return UserStance.DECLINED
            if (pendingPhrases.any { line.contains(it) }) return UserStance.PENDING
            if (acceptPhrases.any { line.contains(it) }) return UserStance.ACCEPTED
        }
        return UserStance.UNKNOWN
    }

    fun resolvedState(modelState: CommitmentState, conversationContext: String?): CommitmentState {
        return when (userStance(conversationContext)) {
            UserStance.DECLINED -> CommitmentState.DECLINED
            UserStance.PENDING -> CommitmentState.PENDING
            UserStance.ACCEPTED -> when (modelState) {
                CommitmentState.CANCELLED, CommitmentState.EXPIRED -> modelState
                CommitmentState.RESCHEDULED -> CommitmentState.RESCHEDULED
                else -> CommitmentState.ACCEPTED
            }
            UserStance.UNKNOWN -> modelState
        }
    }

    fun isConfirmed(state: CommitmentState): Boolean = state in setOf(
        CommitmentState.ACCEPTED, CommitmentState.CONFIRMED, CommitmentState.RESCHEDULED
    )

    fun isTerminal(state: CommitmentState): Boolean = state in setOf(
        CommitmentState.DECLINED, CommitmentState.CANCELLED, CommitmentState.EXPIRED
    )

    /** Final non-LLM gate before an autonomous action is allowed to execute. */
    fun shouldExecute(action: String, state: CommitmentState, confidence: Double): Boolean {
        val type = action.lowercase()
        return when (type) {
            "alarm" -> isConfirmed(state) && confidence >= 0.82
            "calendar" -> isConfirmed(state) && confidence >= 0.72
            "reminder" -> isConfirmed(state) && confidence >= 0.68
            "finance" -> confidence >= 0.72
            "note" -> confidence >= 0.62
            "notify", "alert" -> confidence >= 0.65
            else -> false
        }
    }
}
