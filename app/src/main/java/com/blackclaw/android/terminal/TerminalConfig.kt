package com.blackclaw.android.terminal

import com.blackclaw.android.utils.KVUtils

/**
 * Settings for BlackClaw's internal terminal.
 *
 * The terminal is an opt-in power feature: once enabled, both the user (via
 * [com.blackclaw.android.ui.terminal.TerminalActivity]) and the AI (via the
 * `terminal` tool) share one persistent shell session — working directory,
 * chosen backend, and an `adb` router for wireless-debugging to other devices.
 */
object TerminalConfig {

    private const val KEY_ENABLED = "terminal_enabled"

    /** Master switch. Off by default — it grants broad shell reach. */
    var enabled: Boolean
        get() = KVUtils.getBoolean(KEY_ENABLED, false)
        set(v) { KVUtils.putBoolean(KEY_ENABLED, v); KVUtils.sync() }
}
