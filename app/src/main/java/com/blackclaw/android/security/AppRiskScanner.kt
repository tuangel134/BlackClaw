package com.blackclaw.android.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.utils.XLog

/**
 * Lightweight, on-device app risk scanner — the "intermediate" tier of
 * BlackClaw's built-in security. No cloud, no signatures DB: it scores each
 * installed app on observable, high-signal traits an ad/spyware/dropper app
 * tends to have (overlay permission, accessibility service, device-admin,
 * ability to install other apps, sideloaded origin, dangerous permission mix,
 * hidden/no-launcher). Pure heuristics — meant to surface suspects for the user
 * (or the AI) to act on, not to be a definitive verdict.
 */
object AppRiskScanner {

    private const val TAG = "AppRiskScanner"

    enum class Level { LOW, MEDIUM, HIGH }

    data class AppRisk(
        val pkg: String,
        val label: String,
        val score: Int,
        val level: Level,
        val reasons: List<String>,
        val isSystem: Boolean,
        val requestsOverlay: Boolean,
        val hasAccessibility: Boolean,
        val installer: String?,
        val firstInstall: Long,
    )

    private val PLAY_STORE = setOf("com.android.vending", "com.google.android.feedback")

    /**
     * Scan installed apps and return them ranked by risk (highest first).
     * System apps are excluded by default (they inflate noise and can't be
     * uninstalled anyway).
     */
    fun scan(context: Context = ClawApplication.instance, includeSystem: Boolean = false): List<AppRisk> {
        val pm = context.packageManager
        val a11yPkgs = accessibilityServicePackages(context)
        val adminPkgs = deviceAdminPackages(context)

        val packages = runCatching {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }.getOrElse {
            XLog.w(TAG, "getInstalledPackages failed: ${it.message}")
            emptyList()
        }

        val out = ArrayList<AppRisk>()
        for (info in packages) {
            val ai = info.applicationInfo ?: continue
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem && !includeSystem) continue
            if (info.packageName == context.packageName) continue

            val perms = info.requestedPermissions?.toSet() ?: emptySet()
            val reasons = ArrayList<String>()
            var score = 0

            val overlay = "android.permission.SYSTEM_ALERT_WINDOW" in perms
            if (overlay) { score += 3; reasons.add("Puede dibujar sobre otras apps (anuncios/overlays)") }

            val hasA11y = info.packageName in a11yPkgs
            if (hasA11y) { score += 4; reasons.add("Trae un servicio de accesibilidad (puede leer/controlar la pantalla)") }

            if (info.packageName in adminPkgs) { score += 4; reasons.add("Es administrador del dispositivo") }

            if ("android.permission.REQUEST_INSTALL_PACKAGES" in perms) {
                score += 3; reasons.add("Puede instalar otras apps (riesgo de 'dropper')")
            }
            if ("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" in perms ||
                "android.permission.BIND_ACCESSIBILITY_SERVICE" in perms) {
                score += 1
            }

            var smsRisk = 0
            if ("android.permission.SEND_SMS" in perms) smsRisk++
            if ("android.permission.READ_SMS" in perms) smsRisk++
            if ("android.permission.RECEIVE_SMS" in perms) smsRisk++
            if (smsRisk > 0) { score += smsRisk + 1; reasons.add("Accede a SMS (fraude por SMS premium)") }

            if ("android.permission.RECORD_AUDIO" in perms) { score += 2; reasons.add("Puede grabar audio") }
            if ("android.permission.READ_CALL_LOG" in perms) { score += 2; reasons.add("Lee el registro de llamadas") }
            if ("android.permission.READ_CONTACTS" in perms) { score += 1 }
            if ("android.permission.CAMERA" in perms) { score += 1 }

            val installer = installerOf(pm, info.packageName)
            val sideloaded = installer == null || installer !in PLAY_STORE
            if (sideloaded && !isSystem) {
                score += 2
                reasons.add(if (installer == null) "Origen de instalación desconocido"
                    else "No se instaló desde Play Store ($installer)")
            }

            val hidden = pm.getLaunchIntentForPackage(info.packageName) == null && !isSystem
            if (hidden) { score += 5; reasons.add("No tiene icono en el lanzador (firma típica de adware tipo IconAds)") }

            // Classic hidden-adware combo: can overlay AND hides its icon.
            if (hidden && overlay) { score += 3; reasons.add("Combina superposición + icono oculto (patrón de anuncios fuera de contexto)") }

            val level = when {
                score >= 8 -> Level.HIGH
                score >= 4 -> Level.MEDIUM
                else -> Level.LOW
            }

            val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(info.packageName)
            out.add(AppRisk(
                pkg = info.packageName, label = label, score = score, level = level,
                reasons = reasons, isSystem = isSystem, requestsOverlay = overlay,
                hasAccessibility = hasA11y, installer = installer,
                firstInstall = info.firstInstallTime,
            ))
        }
        return out.sortedByDescending { it.score }
    }

    private fun installerOf(pm: PackageManager, pkg: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
        }
    }.getOrNull()

    private fun accessibilityServicePackages(context: Context): Set<String> = runCatching {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).mapNotNull {
            it.resolveInfo?.serviceInfo?.packageName
        }.toSet()
    }.getOrDefault(emptySet())

    private fun deviceAdminPackages(context: Context): Set<String> = runCatching {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
            as android.app.admin.DevicePolicyManager
        dpm.activeAdmins?.map { it.packageName }?.toSet() ?: emptySet()
    }.getOrDefault(emptySet())
}
