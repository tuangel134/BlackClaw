package com.blackclaw.android.terminal

import com.blackclaw.android.utils.KVUtils

/**
 * Settings for BlackClaw's internal terminal.
 *
 * The terminal is an opt-in power feature.  The user and AI get separate working
 * directories; the AI is limited to the fixed local Linux environment and cannot
 * use the optional privileged or remote-ADB console features.
 */
object TerminalConfig {

    private const val KEY_ENABLED = "terminal_enabled"

    /** Master switch. Off by default — it lets the agent execute local Linux commands. */
    var enabled: Boolean
        get() = KVUtils.getBoolean(KEY_ENABLED, false)
        set(v) { KVUtils.putBoolean(KEY_ENABLED, v); KVUtils.sync() }
}
