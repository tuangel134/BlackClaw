package com.blackclaw.android.channel.auth

import com.blackclaw.android.channel.Channel
import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog
import java.security.SecureRandom

/**
 * Persistent owner binding for remote channels, backed by [KVUtils].
 *
 * Call [evaluate] from a channel handler *before* touching any routing state. A
 * rejected sender must not become the reply target, otherwise a stranger hijacks the
 * destination of the agent's next answer simply by messaging the bot.
 *
 * The decision table lives in [ChannelAuthPolicy]; this class only stores state and
 * renders replies.
 */
object ChannelAuthorization {

    private const val TAG = "ChannelAuth"

    private val random = SecureRandom()

    /** Serialises read-modify-write cycles: channel threads poll concurrently. */
    private val lock = Any()

    private fun ownerKey(channel: Channel) = "channel_owner_id_${channel.name.lowercase()}"
    private fun codeKey(channel: Channel) = "channel_pairing_code_${channel.name.lowercase()}"
    private fun attemptsKey(channel: Channel) = "channel_pairing_fails_${channel.name.lowercase()}"
    private fun firstFailKey(channel: Channel) = "channel_pairing_fail_at_${channel.name.lowercase()}"

    /** Outcome of [evaluate], already carrying the reply to send (null = stay silent). */
    data class Result(
        val allowed: Boolean,
        val justPaired: Boolean,
        val reply: String?,
    )

    fun state(channel: Channel): ChannelAuthPolicy.AuthState = synchronized(lock) {
        ChannelAuthPolicy.AuthState(
            ownerId = KVUtils.getString(ownerKey(channel), "").ifBlank { null },
            pairingCode = KVUtils.getString(codeKey(channel), ""),
            failedAttempts = KVUtils.getInt(attemptsKey(channel), 0),
            firstFailedAtMs = KVUtils.getLong(firstFailKey(channel), 0L),
        )
    }

    private fun persist(channel: Channel, state: ChannelAuthPolicy.AuthState) {
        KVUtils.putString(ownerKey(channel), state.ownerId.orEmpty())
        KVUtils.putString(codeKey(channel), state.pairingCode)
        KVUtils.putInt(attemptsKey(channel), state.failedAttempts)
        KVUtils.putLong(firstFailKey(channel), state.firstFailedAtMs)
        KVUtils.sync()
    }

    /**
     * Authorize an inbound message.
     *
     * @param senderId channel-specific sender identity, never blank for a real message.
     * @return whether the message may reach the agent, plus an optional reply to send
     *   back to that specific sender (never to the routing target).
     */
    fun evaluate(channel: Channel, senderId: String, message: String): Result = synchronized(lock) {
        val current = state(channel)
        val decision = ChannelAuthPolicy.decide(current, senderId, message, System.currentTimeMillis())
        val masked = ChannelAuthPolicy.maskSenderId(senderId)

        return when (decision) {
            is ChannelAuthPolicy.Decision.Allow ->
                Result(allowed = true, justPaired = false, reply = null)

            is ChannelAuthPolicy.Decision.Paired -> {
                persist(channel, ChannelAuthPolicy.applyPairing(current, senderId))
                XLog.i(TAG, "[${channel.displayName}] paired with sender $masked")
                Result(
                    allowed = true,
                    justPaired = true,
                    reply = "✅ Vinculado. Esta cuenta ahora controla BlackClaw por ${channel.displayName}. " +
                        "Puedes desvincularla desde Ajustes → Canales en el teléfono.",
                )
            }

            is ChannelAuthPolicy.Decision.RejectUnpaired -> {
                persist(channel, ChannelAuthPolicy.registerFailure(current, System.currentTimeMillis()))
                XLog.w(TAG, "[${channel.displayName}] rejected unpaired sender $masked")
                val hasCode = current.pairingCode.isNotBlank()
                Result(
                    allowed = false,
                    justPaired = false,
                    reply = if (hasCode) {
                        "🔒 Este canal no está vinculado. Abre BlackClaw en el teléfono " +
                            "(Ajustes → Canales), copia el código de vinculación y envíamelo."
                    } else {
                        "🔒 Este canal no está vinculado y no hay código activo. " +
                            "Genera uno en el teléfono: Ajustes → Canales."
                    },
                )
            }

            is ChannelAuthPolicy.Decision.RejectNotOwner -> {
                // Deliberately silent: replying would confirm to a stranger that this
                // bot is live and already bound to someone.
                XLog.w(TAG, "[${channel.displayName}] dropped message from non-owner $masked")
                Result(allowed = false, justPaired = false, reply = null)
            }

            is ChannelAuthPolicy.Decision.RejectLocked -> {
                XLog.w(TAG, "[${channel.displayName}] pairing locked, sender $masked")
                Result(
                    allowed = false,
                    justPaired = false,
                    reply = "⛔ Demasiados intentos fallidos. Genera un código nuevo en el teléfono " +
                        "(Ajustes → Canales) para volver a intentarlo.",
                )
            }
        }
    }

    // ── Management, called from the settings UI ────────────────────────────────

    /** Issue a fresh single-use pairing code and clear the lockout. */
    fun regeneratePairingCode(channel: Channel): String = synchronized(lock) {
        val code = ChannelAuthPolicy.generatePairingCode { bound -> random.nextInt(bound) }
        persist(
            channel,
            state(channel).copy(
                pairingCode = code,
                failedAttempts = 0,
                firstFailedAtMs = 0L,
            ),
        )
        XLog.i(TAG, "[${channel.displayName}] new pairing code issued")
        code
    }

    /** Drop the owner binding so a different account can pair. */
    fun unpair(channel: Channel) = synchronized(lock) {
        persist(channel, ChannelAuthPolicy.AuthState())
        XLog.i(TAG, "[${channel.displayName}] unpaired")
    }

    fun ownerId(channel: Channel): String? = state(channel).ownerId

    fun isPaired(channel: Channel): Boolean = !state(channel).ownerId.isNullOrBlank()

    /** Code for display, generating one on demand so the UI always has something to show. */
    fun pairingCodeForDisplay(channel: Channel): String {
        val existing = state(channel).pairingCode
        val code = existing.ifBlank { regeneratePairingCode(channel) }
        return ChannelAuthPolicy.formatForDisplay(code)
    }

    fun maskedOwner(channel: Channel): String =
        ChannelAuthPolicy.maskSenderId(state(channel).ownerId.orEmpty())
}
