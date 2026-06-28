package com.blackclaw.android.tool

import com.blackclaw.android.ClawApplication
import com.blackclaw.android.utils.XLog

/**
 * In-memory cache of installed apps (package → label).
 *
 * Querying PackageManager for installed apps takes 50-200ms per call. The agent
 * loop calls get_installed_apps and open_app frequently, so caching this saves
 * significant time. Refreshed lazily every 10 minutes or on cache miss.
 */
object AppCache {

    private const val TAG = "AppCache"
    private const val REFRESH_INTERVAL_MS = 10 * 60_000L  // 10 minutes

    data class AppEntry(val packageName: String, val label: String)

    private var cache: List<AppEntry> = emptyList()
    private var lastRefreshMs: Long = 0L

    /**
     * Get all installed apps (cached). Forces a refresh if stale.
     */
    @Synchronized
    fun getApps(): List<AppEntry> {
        if (cache.isEmpty() || System.currentTimeMillis() - lastRefreshMs > REFRESH_INTERVAL_MS) {
            refresh()
        }
        return cache
    }

    /**
     * Find a package name by app label (fuzzy match).
     * Returns null if no match found.
     */
    fun findPackage(query: String): String? {
        val q = query.lowercase().trim()
        val apps = getApps()

        // Exact match
        apps.firstOrNull { it.label.lowercase() == q }?.let { return it.packageName }
        // Package name match
        apps.firstOrNull { it.packageName == q }?.let { return it.packageName }
        // Contains match (prefer shorter labels = more specific apps)
        val matches = apps.filter { it.label.lowercase().contains(q) }
            .sortedBy { it.label.length }
        if (matches.isNotEmpty()) return matches.first().packageName

        // Common aliases
        val aliases = mapOf(
            "whatsapp" to "com.whatsapp",
            "telegram" to "org.telegram.messenger",
            "instagram" to "com.instagram.android",
            "youtube" to "com.google.android.youtube",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "spotify" to "com.spotify.music",
            "chrome" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "camera" to "com.android.camera",
            "cámara" to "com.android.camera",
            "settings" to "com.android.settings",
            "ajustes" to "com.android.settings",
            "calendar" to "com.google.android.calendar",
            "calendario" to "com.google.android.calendar",
            "clock" to "com.google.android.deskclock",
            "reloj" to "com.google.android.deskclock",
            "facebook" to "com.facebook.katana",
            "tiktok" to "com.zhiliaoapp.musically",
            "netflix" to "com.netflix.mediaclient",
            "uber" to "com.ubercab",
            "waze" to "com.waze",
        )
        aliases[q]?.let { pkg ->
            if (apps.any { it.packageName == pkg }) return pkg
        }

        return null
    }

    /**
     * Get the human-readable label for a package.
     */
    fun getLabel(packageName: String): String {
        return getApps().firstOrNull { it.packageName == packageName }?.label ?: packageName
    }

    @Synchronized
    fun refresh() {
        try {
            val pm = ClawApplication.instance.packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            val apps = pm.queryIntentActivities(intent, 0).map { resolveInfo ->
                AppEntry(
                    packageName = resolveInfo.activityInfo.packageName,
                    label = resolveInfo.loadLabel(pm).toString(),
                )
            }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
            cache = apps
            lastRefreshMs = System.currentTimeMillis()
            XLog.d(TAG, "Refreshed app cache: ${apps.size} apps")
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to refresh app cache: ${e.message}")
        }
    }

    fun invalidate() {
        lastRefreshMs = 0L
    }
}
