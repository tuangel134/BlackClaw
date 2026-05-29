package com.blackclaw.android.tool.impl

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Best-effort location read.
 *  1. Try the most recent cached fix from any provider (cheap, no GPS spin-up).
 *  2. If none, request a single update with a short timeout.
 *
 * No fancy fused-provider dependency — we use the platform LocationManager so the
 * app stays Play-Services-free. Callers should already hold ACCESS_FINE_LOCATION
 * or ACCESS_COARSE_LOCATION at runtime; this returns a clear error otherwise.
 */
class GetLocationTool : BaseTool() {
    override fun getName() = "get_location"
    override fun getDisplayName() = "Ubicación"
    override fun getDescriptionEN() =
        "Read the device's current location (latitude, longitude, accuracy). " +
        "Uses the last known fix when available, else waits up to 8 seconds for a fresh one. " +
        "Requires ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION at runtime."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getParameters() = emptyList<ToolParameter>()

    override fun execute(params: Map<String, Any>): ToolResult {
        val ctx = ClawApplication.instance
        val fineGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            return ToolResult.error("Falta el permiso de ubicación. Concédelo en Ajustes > Apps > BlackClaw > Permisos.")
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return ToolResult.error("Servicio de ubicación no disponible.")

        // Try cached fix first
        val providers = lm.getProviders(true)
        if (providers.isEmpty()) {
            return ToolResult.error("Sin proveedores de ubicación activos (¿GPS apagado?).")
        }
        var best: Location? = null
        for (p in providers) {
            try {
                val loc = lm.getLastKnownLocation(p) ?: continue
                if (best == null || loc.accuracy < best.accuracy) best = loc
            } catch (_: SecurityException) {}
        }
        if (best != null && (System.currentTimeMillis() - best.time) < 5L * 60_000L) {
            return ToolResult.success(formatLocation(best))
        }

        // Request a fresh fix with timeout
        val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER
                       else providers.first()
        val latch = CountDownLatch(1)
        var fresh: Location? = null
        val listener = android.location.LocationListener { loc ->
            fresh = loc
            latch.countDown()
        }
        return try {
            try {
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            } catch (e: SecurityException) {
                return ToolResult.error("Permiso revocado en runtime: ${e.message}")
            }
            val ok = latch.await(8, TimeUnit.SECONDS)
            try { lm.removeUpdates(listener) } catch (_: Exception) {}
            val result = fresh ?: best
            if (result != null) ToolResult.success(formatLocation(result))
            else if (!ok) ToolResult.error("Tiempo de espera agotado, sin fix de ubicación.")
            else ToolResult.error("No se obtuvo ubicación.")
        } catch (e: Exception) {
            ToolResult.error("Error de ubicación: ${e.message}")
        }
    }

    private fun formatLocation(loc: Location): String {
        val ageSec = ((System.currentTimeMillis() - loc.time) / 1000L).coerceAtLeast(0)
        return "Ubicación: %.6f, %.6f (precisión ±%.0fm, hace %ds, proveedor %s)".format(
            loc.latitude, loc.longitude, loc.accuracy, ageSec, loc.provider ?: "?"
        )
    }
}
