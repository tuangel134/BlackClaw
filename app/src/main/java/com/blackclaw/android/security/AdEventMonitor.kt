package com.blackclaw.android.security

/**
 * Real-time ad/interruption attribution — BlackClaw's edge over static scanners.
 *
 * Modern ad-fraud families (IconAds / Kaleidoscope / HiddenAds-Vapor) throw
 * full-screen "out-of-context" ads: an activity/window from an app that is NOT
 * the one the user is actually using, fired without interaction. Our
 * accessibility service already sees every window change, so we watch for a
 * fullscreen window appearing from package X while the user's real foreground
 * app is Y (X≠Y), and tally it against X. When the user says "an app keeps
 * showing me ads", the top-tallied package is the prime suspect — no guessing.
 *
 * Fed from [com.blackclaw.android.service.ClawAccessibilityService]. Pure
 * in-memory ring buffer; nothing persisted, cheap to update.
 */
object AdEventMonitor {

    /** How long an interruption event stays relevant. */
    private const val WINDOW_MS = 15L * 60 * 1000
    private const val MAX_EVENTS = 400

    private data class Event(val pkg: String, val t: Long)

    private val events = ArrayDeque<Event>()

    // Packages that are the system UI / launcher / IME — never "ad culprits".
    private val IGNORE_EXACT = setOf(
        "com.blackclaw.android",
        "android",
        "com.android.systemui",
    )
    private val IGNORE_SUBSTR = listOf(
        "launcher", "inputmethod", "ime", "com.android.settings", "systemui",
    )

    @Volatile private var lastUserPkg: String = ""

    /**
     * Called on every TYPE_WINDOW_STATE_CHANGED. [pkg] is the package whose
     * window just came to the front. We treat a change to a non-launcher app as
     * the new "user package", UNLESS it looks like an out-of-context intrusion
     * (arrived right after a different real app, from a non-launcher package),
     * in which case we tally it.
     */
    @Synchronized
    fun onWindow(pkg: String?) {
        if (pkg.isNullOrBlank()) return
        if (pkg in IGNORE_EXACT) return
        if (IGNORE_SUBSTR.any { pkg.contains(it, ignoreCase = true) }) {
            return
        }
        val now = System.currentTimeMillis()
        // If this window belongs to a different app than the one the user was
        // just in, it's a candidate interruption. We still record it; scoring
        // combines this with static risk (overlay/hidden/sideload) downstream,
        // so benign app-switching doesn't get falsely flagged as adware.
        if (pkg != lastUserPkg) {
            events.addLast(Event(pkg, now))
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
        lastUserPkg = pkg
    }

    /** How many interruption events [pkg] produced in the recent window. */
    @Synchronized
    fun activityScore(pkg: String): Int {
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        return events.count { it.pkg == pkg && it.t >= cutoff }
    }

    /** Packages seen recently, most-active first (for culprit ranking). */
    @Synchronized
    fun recentPackages(): Map<String, Int> {
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        return events.filter { it.t >= cutoff }
            .groupingBy { it.pkg }.eachCount()
    }

    @Synchronized
    fun clear() { events.clear(); lastUserPkg = "" }
}
