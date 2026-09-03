package com.blackclaw.android.automation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Shared best-effort location reader used by saved places and automation conditions. */
object LocationSnapshotProvider {
    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun lastKnown(context: Context, maxAgeMs: Long = Long.MAX_VALUE): Location? {
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val now = System.currentTimeMillis()
        return runCatching {
            lm.getProviders(true).mapNotNull { provider ->
                @Suppress("MissingPermission")
                lm.getLastKnownLocation(provider)
            }.filter { maxAgeMs == Long.MAX_VALUE || now - it.time <= maxAgeMs }
                .maxWithOrNull(compareBy<Location> { it.time }.thenBy { -it.accuracy })
        }.getOrNull()
    }

    /** Prefer a recent cached fix; otherwise wait briefly for one fresh update. */
    fun current(context: Context, maxCachedAgeMs: Long = 5 * 60_000L, timeoutMs: Long = 8_000L): Result<Location> = runCatching {
        require(hasLocationPermission(context)) { "Falta el permiso de ubicación." }
        lastKnown(context, maxCachedAgeMs)?.let { return Result.success(it) }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: throw IllegalStateException("Servicio de ubicación no disponible.")
        val providers = lm.getProviders(true)
        require(providers.isNotEmpty()) { "No hay proveedores de ubicación activos." }
        val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else providers.first()
        val latch = CountDownLatch(1)
        var fresh: Location? = null
        val listener = android.location.LocationListener { loc -> fresh = loc; latch.countDown() }
        @Suppress("MissingPermission")
        lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        runCatching { lm.removeUpdates(listener) }
        fresh ?: lastKnown(context) ?: throw IllegalStateException("No se obtuvo ubicación.")
    }
}
