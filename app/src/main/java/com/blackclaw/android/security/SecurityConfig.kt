package com.blackclaw.android.security

import com.blackclaw.android.utils.KVUtils

/** Settings for BlackClaw's built-in antimalware / app-security feature. */
object SecurityConfig {

    private const val KEY_ENABLED = "security_enabled"

    /** Master switch for the security screen + scan-on-install watching. */
    var enabled: Boolean
        get() = KVUtils.getBoolean(KEY_ENABLED, false)
        set(v) { KVUtils.putBoolean(KEY_ENABLED, v); KVUtils.sync() }
}
