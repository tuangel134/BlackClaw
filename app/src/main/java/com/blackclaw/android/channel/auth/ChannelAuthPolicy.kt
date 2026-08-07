package com.blackclaw.android.channel.auth

/**
 * Decides whether an inbound remote message may drive the agent.
 *
 * Pure logic, no Android dependencies, so the whole decision table is unit-testable.
 * [ChannelAuthorization] owns persistence and wiring.
 *
 * ## Why this exists
 *
 * A remote channel message reaches [com.blackclaw.android.TaskOrchestrator] with the
 * full tool surface behind it: SMS, contacts, call log, screen contents, and shell
 * when Shizuku is active. Telegram bots are discoverable by username and anyone can
 * `/start` them, so without an owner check a stranger who finds the bot gets a
 * complete device takeover.
 *
 * Injecting a warning into the model's prompt is not a control — the model is exactly
 * the component an attacker is trying to influence. This gate runs before the message
 * is ever shown to the model.
 *
 * ## Pairing model
 *
 * Ownership is claimed by proving physical access to the phone: the app displays a
 * pairing code, and an unknown sender must send that code as their message to become
 * the owner. Everything else from an unpaired channel is refused, and everything from
 * a non-owner is dropped silently.
 *
 * Codes are single-use and are drawn from [PAIRING_ALPHABET] at [PAIRING_CODE_LENGTH]
 * characters, giving ~1.1e12 combinations. Failed attempts are capped by
 * [MAX_FAILED_ATTEMPTS] inside [LOCKOUT_WINDOW_MS] so the code cannot be brute-forced
 * by a bot, since a channel can otherwise be driven far faster than a human types.
 */
object ChannelAuthPolicy {

    /**
     * Unambiguous alphabet: no `0`/`O`, no `1`/`I`/`L`. The user reads this off a
     * screen and types it into a chat app, often on a phone keyboard.
     */
    const val PAIRING_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    const val PAIRING_CODE_LENGTH = 8

    /** Failed pairing attempts tolerated inside [LOCKOUT_WINDOW_MS]. */
    const val MAX_FAILED_ATTEMPTS = 5

    /** Sliding window for [MAX_FAILED_ATTEMPTS]. */
    const val LOCKOUT_WINDOW_MS = 10 * 60 * 1000L

    /** Persisted authorization state for a single channel. */
    data class AuthState(
        /** Sender id that owns this channel, or null when the channel is unpaired. */
        val ownerId: String? = null,
        /** Active pairing code. Blank disables pairing entirely. */
        val pairingCode: String = "",
        val failedAttempts: Int = 0,
        /** Timestamp of the first failure in the current window. */
        val firstFailedAtMs: Long = 0L,
    )

    sealed interface Decision {
        /** Sender is the established owner. Proceed. */
        data object Allow : Decision

        /** Sender presented the correct code and is now the owner. Proceed. */
        data class Paired(val senderId: String) : Decision

        /** Channel has no owner and the message was not the pairing code. */
        data object RejectUnpaired : Decision

        /**
         * Channel is owned by someone else. Carries no reply — answering would
         * confirm to a stranger that this bot is live and paired.
         */
        data object RejectNotOwner : Decision

        /** Too many wrong codes. Pairing is frozen until the code is regenerated. */
        data object RejectLocked : Decision
    }

    /**
     * @param senderId channel-specific identifier of the sender (Telegram chat id,
     *   Discord channel id, WeChat user id). Blank is always refused: without an
     *   identity there is nothing to authorize.
     */
    fun decide(state: AuthState, senderId: String, message: String, nowMs: Long): Decision {
        if (senderId.isBlank()) return Decision.RejectNotOwner

        val owner = state.ownerId
        if (!owner.isNullOrBlank()) {
            return if (owner == senderId) Decision.Allow else Decision.RejectNotOwner
        }

        // Unpaired channel: the only accepted message is the pairing code.
        if (isLockedOut(state, nowMs)) return Decision.RejectLocked
        if (state.pairingCode.isNotBlank() && matchesCode(state.pairingCode, message)) {
            return Decision.Paired(senderId)
        }
        return Decision.RejectUnpaired
    }

    fun isLockedOut(state: AuthState, nowMs: Long): Boolean =
        state.failedAttempts >= MAX_FAILED_ATTEMPTS &&
            nowMs - state.firstFailedAtMs < LOCKOUT_WINDOW_MS

    /**
     * Fold a failed attempt into the state, restarting the window when the previous
     * one has expired so a slow trickle of typos never accumulates into a lockout.
     */
    fun registerFailure(state: AuthState, nowMs: Long): AuthState {
        val windowExpired = state.failedAttempts == 0 ||
            nowMs - state.firstFailedAtMs >= LOCKOUT_WINDOW_MS
        return if (windowExpired) {
            state.copy(failedAttempts = 1, firstFailedAtMs = nowMs)
        } else {
            state.copy(failedAttempts = state.failedAttempts + 1)
        }
    }

    /** State after a successful pairing: owner set, code burned, counters cleared. */
    fun applyPairing(state: AuthState, senderId: String): AuthState =
        AuthState(ownerId = senderId, pairingCode = "", failedAttempts = 0, firstFailedAtMs = 0L)

    /**
     * Compare a submitted code against the active one, tolerating how chat apps and
     * humans mangle it: surrounding whitespace, internal spaces or dashes, lower
     * case, and a leading slash from users who assume it is a bot command.
     */
    fun matchesCode(expected: String, submitted: String): Boolean {
        val a = normalizeCode(expected)
        val b = normalizeCode(submitted)
        return a.isNotEmpty() && a == b
    }

    internal fun normalizeCode(value: String): String =
        value.trim().removePrefix("/").filter { it.isLetterOrDigit() }.uppercase()

    /**
     * Generate a pairing code. [randomInt] receives an exclusive upper bound, which
     * keeps this function deterministic under test.
     */
    fun generatePairingCode(randomInt: (Int) -> Int): String =
        buildString(PAIRING_CODE_LENGTH) {
            repeat(PAIRING_CODE_LENGTH) {
                append(PAIRING_ALPHABET[randomInt(PAIRING_ALPHABET.length)])
            }
        }

    /** Format a code for display, grouped in halves for readability. */
    fun formatForDisplay(code: String): String {
        val clean = normalizeCode(code)
        if (clean.length != PAIRING_CODE_LENGTH) return clean
        return clean.substring(0, 4) + "-" + clean.substring(4)
    }

    /**
     * Obscure a sender id for logs and UI. Remote identifiers are user data and end
     * up in the event log, so only enough to recognise your own account survives.
     */
    fun maskSenderId(senderId: String): String = when {
        senderId.isBlank() -> "(none)"
        senderId.length <= 4 -> "*".repeat(senderId.length)
        else -> "*".repeat(senderId.length - 4) + senderId.takeLast(4)
    }
}
