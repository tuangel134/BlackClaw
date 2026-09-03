package com.blackclaw.android.automation

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.blackclaw.android.utils.XLog
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import java.security.MessageDigest
import kotlin.math.abs

/** Resolves a profile's location params from either a reusable saved place or raw coordinates. */
object AutomationLocationTarget {
    data class Target(
        val latitude: Double,
        val longitude: Double,
        val radiusM: Float,
        val placeId: String = "",
        val placeName: String = "",
    )

    fun resolve(params: Map<String, Any>): Target? {
        val placeId = params["place_id"]?.toString().orEmpty().trim()
        val placeName = params["place"]?.toString().orEmpty().trim()
        val place = when {
            placeId.isNotBlank() -> SavedPlaceStore.findById(placeId)
            placeName.isNotBlank() -> SavedPlaceStore.resolve(placeName).place
            else -> null
        }
        if (place != null) {
            val radius = automationFloat(params["radius_m"])?.coerceIn(25f, 100_000f) ?: place.radiusM
            return Target(place.latitude, place.longitude, radius, place.id, place.name)
        }
        val lat = params["latitude"]?.toString()?.toDoubleOrNull() ?: return null
        val lon = params["longitude"]?.toString()?.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return Target(lat, lon, automationFloat(params["radius_m"])?.coerceIn(25f, 100_000f) ?: 150f)
    }

    fun same(a: Target, b: Target): Boolean =
        abs(a.latitude - b.latitude) < 0.000001 &&
            abs(a.longitude - b.longitude) < 0.000001 &&
            abs(a.radiusM - b.radiusM) < 0.5f

    /** Opaque deterministic ID: Play Services never receives raw coordinates as the request ID. */
    fun requestId(target: Target): String {
        val canonical = "%.6f|%.6f|%d".format(
            java.util.Locale.US, target.latitude, target.longitude, target.radiusM.toInt(),
        )
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return "bcg:" + digest.take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/**
 * Hybrid geofence runtime.
 *
 * Google Play Services geofences are the primary low-power path. BlackClaw keeps
 * GeofenceChecker as a platform-only fallback, so devices without Play Services or
 * without background-location permission still retain best-effort behavior.
 */
object AutomationGeofenceManager {
    private const val TAG = "AutomationGeofence"
    private const val MAX_GEOFENCES = 100

    fun sync(context: Context) {
        val app = context.applicationContext
        val client = runCatching { LocationServices.getGeofencingClient(app) }.getOrElse {
            XLog.w(TAG, "Play Services geofencing unavailable; using fallback", it)
            return
        }
        val pi = pendingIntent(app)
        client.removeGeofences(pi).addOnCompleteListener {
            val targets = collectTargets().take(MAX_GEOFENCES)
            if (targets.isEmpty()) return@addOnCompleteListener
            if (!canRegister(app)) {
                XLog.i(TAG, "Geofence registration waiting for precise/background location permission")
                return@addOnCompleteListener
            }
            val geofences = targets.map { (target, transitions) ->
                Geofence.Builder()
                    .setRequestId(AutomationLocationTarget.requestId(target))
                    .setCircularRegion(target.latitude, target.longitude, target.radiusM)
                    .setTransitionTypes(transitions)
                    .setNotificationResponsiveness(30_000)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .build()
            }
            val request = GeofencingRequest.Builder()
                // Do not synthesize an enter/exit immediately at registration. A real
                // transition is safer for automations such as Wi-Fi/mobile data toggles.
                .setInitialTrigger(0)
                .addGeofences(geofences)
                .build()
            @Suppress("MissingPermission")
            client.addGeofences(request, pi)
                .addOnSuccessListener { XLog.i(TAG, "Registered ${geofences.size} automation geofence(s)") }
                .addOnFailureListener { XLog.w(TAG, "Geofence registration failed; fallback remains active", it) }
        }
    }

    internal fun collectTargets(): List<Pair<AutomationLocationTarget.Target, Int>> {
        val combined = linkedMapOf<String, Pair<AutomationLocationTarget.Target, Int>>()
        fun add(target: AutomationLocationTarget.Target, transition: Int) {
            val key = AutomationLocationTarget.requestId(target)
            val old = combined[key]
            combined[key] = target to ((old?.second ?: 0) or transition)
        }
        AutomationProfileStore.list()
            .filter { it.enabled && AutomationProfileValidator.validate(it).isEmpty() }
            .forEach { profile ->
                profile.triggers.forEach { trigger ->
                    val transition = when (trigger.type) {
                        AutomationProfileStore.TriggerType.LOCATION_ENTER -> Geofence.GEOFENCE_TRANSITION_ENTER
                        AutomationProfileStore.TriggerType.LOCATION_EXIT -> Geofence.GEOFENCE_TRANSITION_EXIT
                        else -> return@forEach
                    }
                    AutomationLocationTarget.resolve(trigger.params)?.let { add(it, transition) }
                }
            }
        AutomationRuleStore.list().filter { it.enabled }.forEach { rule ->
            val transition = when (rule.trigger) {
                AutomationRuleStore.Trigger.LOCATION_ENTER -> Geofence.GEOFENCE_TRANSITION_ENTER
                AutomationRuleStore.Trigger.LOCATION_EXIT -> Geofence.GEOFENCE_TRANSITION_EXIT
                else -> return@forEach
            }
            add(AutomationLocationTarget.Target(rule.latitude, rule.longitude, rule.radiusM), transition)
        }
        if (combined.size > MAX_GEOFENCES) XLog.w(TAG, "Geofence limit exceeded; first $MAX_GEOFENCES targets registered")
        return combined.values.toList()
    }

    internal fun resolveRequestId(requestId: String): AutomationLocationTarget.Target? =
        collectTargets().firstOrNull { (target, _) -> AutomationLocationTarget.requestId(target) == requestId }?.first

    private fun canRegister(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    internal fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0xBC70,
        Intent(context, AutomationGeofenceReceiver::class.java).setAction("com.blackclaw.android.AUTOMATION_GEOFENCE"),
        PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0,
    )
}

class AutomationGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != "com.blackclaw.android.AUTOMATION_GEOFENCE") return
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            XLog.w("AutomationGeofence", "Geofence event error code=${event.errorCode}")
            return
        }
        val type = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> AutomationProfileStore.TriggerType.LOCATION_ENTER
            Geofence.GEOFENCE_TRANSITION_EXIT -> AutomationProfileStore.TriggerType.LOCATION_EXIT
            else -> return
        }
        event.triggeringGeofences.orEmpty().forEach { geofence ->
            val target = AutomationGeofenceManager.resolveRequestId(geofence.requestId) ?: return@forEach
            AutomationProfileEngine.onGeofenceTransition(context, type, target)
            AutomationEngine.onGeofenceTransition(context, type, target)
        }
    }
}
