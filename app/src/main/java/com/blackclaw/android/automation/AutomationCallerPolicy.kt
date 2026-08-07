package com.blackclaw.android.automation

/**
 * Decides which *calling app* may drive BlackClaw through the external automation
 * entrypoints. Pure functions, so the whole rule set is unit-testable.
 *
 * ## Why this exists
 *
 * The automation entrypoints hand a caller the ability to start an arbitrary agent
 * task or chat turn. That is the most powerful thing in the app: a task can read the
 * screen, notifications, SMS and clipboard, and fire tools. So "who is calling" is
 * the security question, and until now nothing answered it.
 *
 * Two separate holes were in play:
 *
 *  1. [ExternalAutomationEntrypoint.isTargetedToBlackClaw] inspects the intent's
 *     DESTINATION. Any app satisfies it by writing
 *     `intent.setPackage("com.blackclaw.android")`. It never looked at the origin.
 *  2. The `isExternalAutomationEnabled()` switch is one global boolean. A user who
 *     turns it on to wire up one automation app has simultaneously opened the door
 *     to every other app on the device, including ones installed later.
 *
 * The manifest now gates both components behind a `signature` permission, which is
 * the real lock. This allowlist is the finer-grained second stage: it names the
 * specific packages the user sanctioned, so "enabled" stops meaning "enabled for
 * everyone".
 *
 * ## Caller identity is only available to the Activity
 *
 * An Activity can ask the framework who started it. A BroadcastReceiver cannot —
 * see the note in [ExternalAutomationReceiver]. That asymmetry is why this policy is
 * applied on the Activity path only, and why the receiver leans entirely on the
 * manifest permission.
 */
object AutomationCallerPolicy {

    /** Outcome of an authorization check, so the caller can log/report precisely. */
    enum class Decision {
        /** Caller is authorized. */
        ALLOW,

        /**
         * The framework gave us no caller identity. Happens when the activity was
         * launched with `startActivity()` instead of `startActivityForResult()`,
         * and for `am start` from a shell. Fail closed once the user has bothered
         * to name specific packages: an unprovable origin cannot satisfy an
         * origin-based rule.
         */
        DENY_UNIDENTIFIED_CALLER,

        /** Caller is known and is not on the user's list. */
        DENY_NOT_ALLOWLISTED,
    }

    /**
     * Split the stored allowlist. Stored as a comma-separated string because it
     * rides in a single MMKV key and stays hand-editable.
     */
    fun parseAllowlist(raw: String?): Set<String> =
        raw.orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    /** Round-trips with [parseAllowlist]; drops blanks and duplicates. */
    fun serializeAllowlist(packages: Collection<String>): String =
        packages.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(",")

    /**
     * @param callingPackage what the framework reports as the origin, or null when
     *   it refuses to say.
     * @param selfPackage our own package name, so internal re-dispatch is not
     *   accidentally locked out.
     * @param allowlist packages the user sanctioned. Empty means "no per-caller
     *   restriction configured" — the manifest permission is then the only gate,
     *   which is the pre-existing behaviour for same-signature callers.
     *
     * Comparison is exact, not case-insensitive: Android package names are
     * case-sensitive, so folding case would let `com.Evil` satisfy an allowlist
     * entry of `com.evil`. Widening an access check by accident is exactly the bug
     * class this file exists to close.
     */
    fun decide(
        callingPackage: String?,
        selfPackage: String,
        allowlist: Set<String>,
    ): Decision = decide(callingPackage, selfPackage, allowlist, tokenPresented = false)

    /**
     * @param tokenPresented whether the caller supplied a valid [AutomationToken].
     *   This is the sanctioned path for third-party automation apps: they cannot hold
     *   a signature permission, and [android.app.Activity.getCallingPackage] is null
     *   for the plain `startActivity` that Tasker and `am start` use, so possession of
     *   a secret copied off the phone screen is the only workable proof of consent.
     *   A valid token short-circuits the origin rules entirely — that is the point.
     */
    fun decide(
        callingPackage: String?,
        selfPackage: String,
        allowlist: Set<String>,
        tokenPresented: Boolean,
    ): Decision {
        if (tokenPresented) return Decision.ALLOW
        val caller = callingPackage?.trim().orEmpty()
        if (caller.isNotEmpty() && caller == selfPackage.trim()) return Decision.ALLOW
        if (allowlist.isEmpty()) return Decision.ALLOW
        if (caller.isEmpty()) return Decision.DENY_UNIDENTIFIED_CALLER
        return if (caller in allowlist) Decision.ALLOW else Decision.DENY_NOT_ALLOWLISTED
    }

    /** Message handed back to the calling automation app, and to the log. */
    fun denialMessage(decision: Decision, callingPackage: String?): String = when (decision) {
        Decision.ALLOW -> ""
        Decision.DENY_UNIDENTIFIED_CALLER ->
            "BlackClaw could not identify the calling app, and an automation allowlist is " +
                "configured. Launch the request with startActivityForResult so the caller " +
                "package is visible, or clear the allowlist in BlackClaw Settings."
        Decision.DENY_NOT_ALLOWLISTED ->
            "App '${callingPackage.orEmpty()}' is not in BlackClaw's external automation " +
                "allowlist. Add it in BlackClaw Settings to permit it."
    }
}
