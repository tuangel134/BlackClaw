package com.blackclaw.android.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog

/**
 * Lightweight, dependency-free location reminders.
 *
 * Instead of pulling in Play Services GeofencingClient (heavy, and flaky on
 * some OEM ROMs), we store geofence reminders as REMINDER items with a radius
 * and check them opportunistically against the last known location whenever the
 * assistant's keep-alive service ticks. When the user is inside (enter) or
 * outside (exit) the radius, the reminder fires once.
 *
 * Trade-off: not instant — fires on the next tick after you arrive (≈ minutes),
 * not the second you cross the line. Good enough for "remind me when I get home"
 * without draining the battery on constant GPS.
 */
object GeofenceChecker {
    private const val TAG = "GeofenceChecker"
    private const val KEY_FIRED_PREFIX = "geo_fired_"

    /** True if there's at least one active location reminder. */
    fun hasActiveGeofences(): Boolean =
        AssistantStore.byType(AssistantItemType.REMINDER).any { it.radiusM > 0 && !it.done } ||
            com.blackclaw.android.automation.AutomationRuleStore.list().any {
                it.enabled && it.trigger != com.blackclaw.android.automation.AutomationRuleStore.Trigger.NOTIFICATION
            }

    /** Check all geofence reminders against current location. Call from the
     *  keep-alive tick. Non-blocking-safe (uses last known location only). */
    fun check(context: Context) {
        val geofences = AssistantStore.byType(AssistantItemType.REMINDER)
            .filter { it.radiusM > 0 && !it.done }
        val hasAutomationLocations = com.blackclaw.android.automation.AutomationRuleStore.list().any {
            it.enabled && it.trigger != com.blackclaw.android.automation.AutomationRuleStore.Trigger.NOTIFICATION
        }
        if (geofences.isEmpty() && !hasAutomationLocations) return

        val loc = lastKnownLocation(context) ?: run {
            XLog.d(TAG, "No location available for geofence check"); return
        }
        runCatching { com.blackclaw.android.automation.AutomationEngine.onLocation(context, loc) }

        for (g in geofences) {
            val dist = FloatArray(1)
            Location.distanceBetween(loc.latitude, loc.longitude, g.lat, g.lon, dist)
            val inside = dist[0] <= g.radiusM
            val shouldFire = if (g.geoTrigger == "exit") !inside else inside
            val firedKey = KEY_FIRED_PREFIX + g.id
            val alreadyFired = KVUtils.getBoolean(firedKey, false)

            if (shouldFire && !alreadyFired) {
                AssistantReceiver.postNotification(
                    context, "📍 ${g.title}",
                    g.body.ifBlank { "Recordatorio de ubicación" }, highPriority = true)
                KVUtils.putBoolean(firedKey, true); KVUtils.sync()
                XLog.i(TAG, "Geofence fired: ${g.title} (${dist[0].toInt()}m)")
                // One-shot: mark done so it doesn't repeat.
                AssistantStore.upsert(g.copy(done = true))
            } else if (!shouldFire && alreadyFired) {
                // Reset latch when the condition clears (for future re-arm).
                KVUtils.putBoolean(firedKey, false); KVUtils.sync()
            }
        }
    }

    private fun lastKnownLocation(context: Context): Location? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        var best: Location? = null
        runCatching {
            for (p in lm.getProviders(true)) {
                @Suppress("MissingPermission") val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.time > best!!.time) best = l
            }
        }
        return best
    }
}
