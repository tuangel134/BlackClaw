package com.blackclaw.android.proactive

import android.content.Context
import android.os.PowerManager
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog

/**
 * Smart quiet mode detection.
 *
 * Instead of relying solely on fixed quiet hours (23:00–07:00), this detects
 * whether the user is likely asleep or away based on:
 * - Screen off duration (if screen has been off for 30+ min → likely sleeping)
 * - Time of day (reinforces the signal)
 * - Recent user activity (any recent interaction cancels quiet mode)
 *
 * The proactive assistant uses this to suppress notifications during sleep
 * while still allowing time-critical alarms to be set (they fire later).
 */
object SmartQuietDetector {

    private const val TAG = "SmartQuietDetector"
    private const val KEY_LAST_INTERACTION = "smart_quiet_last_interaction"

    /** Minimum screen-off time to consider the user "away". */
    private const val SCREEN_OFF_THRESHOLD_MS = 30 * 60_000L  // 30 minutes

    /** Don't hit MMKV on every accessibility event — throttle persisted writes. */
    private const val WRITE_THROTTLE_MS = 60_000L
    @Volatile private var lastWriteMs = 0L

    /**
     * Record that the user interacted with the device.
     * Called from accessibility events (user taps/scrolls) and chat resume.
     *
     * Throttled: accessibility fires these constantly, so we persist at most once
     * per minute to avoid pointless MMKV churn (important on low-RAM devices).
     */
    fun recordInteraction() {
        val now = System.currentTimeMillis()
        if (now - lastWriteMs < WRITE_THROTTLE_MS) return
        lastWriteMs = now
        KVUtils.putLong(KEY_LAST_INTERACTION, now)
    }

    /**
     * Determine if the user is likely in a "quiet" state (sleeping/away).
     * Returns true if we should suppress non-critical notifications.
     */
    fun isLikelyQuiet(): Boolean {
        // First check fixed quiet hours (user's preference takes priority)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (ProactiveConfig.inQuietHours(hour)) return true

        // Then check screen state + inactivity
        val context = ClawApplication.instance
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val screenOn = pm?.isInteractive ?: true
        if (screenOn) return false  // Screen is on → user is active

        // Screen is off — check how long since last interaction
        val lastInteraction = KVUtils.getLong(KEY_LAST_INTERACTION, 0L)
        val inactiveMs = System.currentTimeMillis() - lastInteraction
        
        // If inactive for 30+ minutes during typical sleep hours (22-10), likely sleeping
        val isTypicalSleepWindow = hour in 22..23 || hour in 0..9
        val threshold = if (isTypicalSleepWindow) SCREEN_OFF_THRESHOLD_MS else SCREEN_OFF_THRESHOLD_MS * 2

        return inactiveMs > threshold
    }

    /**
     * For the proactive assistant: should we suppress a "notify" action?
     * Alarms and reminders are always created (they fire later), but
     * immediate notifications can be held until the user wakes up.
     */
    fun shouldSuppressNotify(): Boolean = isLikelyQuiet()
}
