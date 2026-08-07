package com.blackclaw.android.security

import android.content.Context
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.service.ClawAccessibilityService

/**
 * "An app is spamming me with ads — which one?" Best-effort attribution.
 *
 * Ad/overlay spam on Android almost always needs the "draw over other apps"
 * permission, so overlay-holding user apps are the prime suspects. We rank them
 * and boost whatever is on screen right now (via accessibility) and anything
 * installed very recently — the classic "installed a game yesterday, now I get
 * pop-ups" pattern.
 */
object AdCulpritDetector {

    data class Suspect(
        val pkg: String,
        val label: String,
        val score: Int,
        val reasons: List<String>,
        val confidence: Int,
        val liveEvents: Int,
    )

    private const val RECENT_MS = 3L * 24 * 60 * 60 * 1000  // 3 days

    fun detect(context: Context = ClawApplication.instance): List<Suspect> {
        val risks = AppRiskScanner.scan(context)
        val foreground = currentForegroundPackage()
        val now = System.currentTimeMillis()

        val suspects = risks.mapNotNull { r ->
            // Real-time evidence: how many out-of-context window interruptions
            // this app produced recently (BlackClaw's accessibility edge).
            val liveEvents = AdEventMonitor.activityScore(r.pkg)

            // Plausible ad source if it can overlay, hides its icon, OR is
            // actively interrupting the user right now.
            val plausible = r.requestsOverlay ||
                r.reasons.any { it.contains("oculto") } ||
                liveEvents >= 2
            if (!plausible) return@mapNotNull null

            val reasons = ArrayList(r.reasons)
            var score = r.score
            if (liveEvents > 0) {
                score += liveEvents * 2
                reasons.add(0, "Apareció $liveEvents veces en pantalla sin que la abrieras (últimos 15 min)")
            }
            if (r.pkg == foreground) { score += 4; reasons.add(0, "Está en primer plano ahora mismo") }
            if (now - r.firstInstall < RECENT_MS) { score += 2; reasons.add("Instalada hace poco") }
            val confidence = confidencePercent(
                liveEvents = liveEvents,
                foregroundNow = r.pkg == foreground,
                overlay = r.requestsOverlay,
                hidden = r.reasons.any { it.contains("oculto") },
                recent = now - r.firstInstall < RECENT_MS,
            )
            Suspect(r.pkg, r.label, score, reasons, confidence, liveEvents)
        }.sortedByDescending { it.score }

        return suspects
    }

    /** The package currently in the foreground, via the accessibility service. */
    private fun currentForegroundPackage(): String? = runCatching {
        val svc = ClawAccessibilityService.getConnectedInstance(500L) ?: return null
        svc.rootInActiveWindow?.packageName?.toString()
    }.getOrNull()

    internal fun confidencePercent(
        liveEvents: Int,
        foregroundNow: Boolean,
        overlay: Boolean,
        hidden: Boolean,
        recent: Boolean,
    ): Int {
        var confidence = 15
        confidence += (liveEvents.coerceAtMost(6) * 9)
        if (foregroundNow) confidence += 12
        if (overlay) confidence += 12
        if (hidden) confidence += 16
        if (recent) confidence += 8
        return confidence.coerceIn(5, 98)
    }
}
