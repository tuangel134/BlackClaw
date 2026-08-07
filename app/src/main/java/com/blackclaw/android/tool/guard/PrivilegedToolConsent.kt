package com.blackclaw.android.tool.guard

import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog

/**
 * Time-boxed user consent for arbitrary-command tools.
 *
 * Granting Shizuku or pairing ADB is a one-time act, but it is not the same as
 * consenting to the model running a shell on your behalf at any later moment. The
 * agent reads screen text, notifications and web pages, so a prompt-injected model
 * could reach `shell_exec` during an ordinary session. Requiring a deliberate,
 * expiring arm-step means the shell is unreachable unless the user asked for it
 * within the last [WINDOW_MS].
 *
 * Off by default, and it expires on its own so a user who forgets to disarm is not
 * left permanently exposed.
 */
object PrivilegedToolConsent {

    private const val TAG = "PrivilegedConsent"
    private const val KEY_ARMED_UNTIL = "privileged_tools_armed_until"

    /** How long an arm lasts. Long enough for a working session, short enough to matter. */
    const val WINDOW_MS = 30 * 60 * 1000L

    fun arm() {
        val until = System.currentTimeMillis() + WINDOW_MS
        KVUtils.putLong(KEY_ARMED_UNTIL, until)
        KVUtils.sync()
        XLog.i(TAG, "Privileged tools armed for ${WINDOW_MS / 60_000} min")
    }

    fun disarm() {
        KVUtils.putLong(KEY_ARMED_UNTIL, 0L)
        KVUtils.sync()
        XLog.i(TAG, "Privileged tools disarmed")
    }

    fun isArmed(): Boolean = remainingMs() > 0L

    fun remainingMs(): Long {
        val until = KVUtils.getLong(KEY_ARMED_UNTIL, 0L)
        val remaining = until - System.currentTimeMillis()
        return if (remaining > 0L) remaining else 0L
    }

    /** Minutes left, for display. Zero when disarmed. */
    fun remainingMinutes(): Int = ((remainingMs() + 59_999L) / 60_000L).toInt()
}
