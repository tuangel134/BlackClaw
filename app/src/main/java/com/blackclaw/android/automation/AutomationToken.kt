package com.blackclaw.android.automation

import com.blackclaw.android.server.ConfigServerPolicy
import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog
import java.security.SecureRandom

/**
 * Shared secret that lets a third-party automation app prove it was sanctioned.
 *
 * ## Why a token and not just caller identity
 *
 * The automation Activity is the one entrypoint where the framework will name the
 * caller, but [android.app.Activity.getCallingPackage] is **null** unless we were
 * started with `startActivityForResult`. Tasker, MacroDroid and `am start` all use
 * plain `startActivity`, so an origin-based allowlist cannot authorise the very apps
 * the feature exists to support.
 *
 * A `signature` permission is airtight but excludes third parties by construction —
 * they cannot be signed with the developer's key.
 *
 * So the sanctioned third-party path is a token the user copies out of Settings into
 * their automation app, passed as an intent extra. A malicious app cannot guess it
 * (12 chars over a 31-char alphabet ≈ 7.8e17), and unlike the intent's other contents
 * it is not something the caller can fabricate. Same shape as the channel pairing
 * code and the config-server access code: possession requires having seen the phone
 * screen.
 *
 * Off by default: with no token issued, only same-package and (if configured)
 * allowlisted callers get through.
 */
object AutomationToken {

    private const val TAG = "AutomationToken"
    private const val KEY_TOKEN = "external_automation_token"

    /** Intent extra automation apps must set. */
    const val EXTRA_TOKEN = "automation_token"

    private val random = SecureRandom()

    /** Issue (or re-issue) the token, invalidating any previously shared copy. */
    fun regenerate(): String {
        val token = ConfigServerPolicy.generateToken { bound -> random.nextInt(bound) }
        KVUtils.putString(KEY_TOKEN, token)
        KVUtils.sync()
        XLog.i(TAG, "External automation token re-issued")
        return token
    }

    /** Revoke it, so no third-party app can drive automation any more. */
    fun revoke() {
        KVUtils.putString(KEY_TOKEN, "")
        KVUtils.sync()
        XLog.i(TAG, "External automation token revoked")
    }

    fun isIssued(): Boolean = stored().isNotEmpty()

    /** Grouped for readability. Generates one on first view so Settings can show it. */
    fun tokenForDisplay(): String {
        val token = stored().ifEmpty { regenerate() }
        return ConfigServerPolicy.formatTokenForDisplay(token)
    }

    /**
     * Whether [presented] matches the issued token.
     *
     * Returns false when no token has been issued — an absent secret must never
     * authorise anything, which is why this is not written as an equality check
     * against a possibly-empty stored value.
     */
    fun matches(presented: String?): Boolean {
        val expected = stored()
        if (expected.isEmpty()) return false
        return ConfigServerPolicy.tokensMatch(expected, presented)
    }

    private fun stored(): String = KVUtils.getString(KEY_TOKEN, "").trim()
}
