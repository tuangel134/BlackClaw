package com.blackclaw.android.security

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo

/** Hard boundary for app-remediation actions. Heuristic scoring never overrides this policy. */
object SecurityPolicy {
    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$")
    private val ALWAYS_PROTECTED = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.blackclaw.android",
    )

    fun isValidPackageName(pkg: String): Boolean = PACKAGE_NAME.matches(pkg)

    fun protectionReason(context: Context, pkg: String): String? {
        if (!isValidPackageName(pkg)) return "Nombre de paquete inválido"
        if (pkg in ALWAYS_PROTECTED) return "Componente esencial de Android o BlackClaw"
        val pm = context.packageManager
        val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
            ?: return "La app no está instalada"
        if ((info.flags and ApplicationInfo.FLAG_SYSTEM) != 0) return "App del sistema protegida"

        val homePackages = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
        if (pkg in homePackages) return "Launcher/inicio del dispositivo protegido"

        val accessibility = runCatching {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .mapNotNull { it.resolveInfo?.serviceInfo?.packageName }.toSet()
        }.getOrDefault(emptySet())
        if (pkg in accessibility) return "Servicio de accesibilidad activo protegido"

        val ownerPackages = runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.activeAdmins?.map { it.packageName }?.toSet() ?: emptySet()
        }.getOrDefault(emptySet())
        if (pkg in ownerPackages) return "Administrador/propietario del dispositivo protegido"
        return null
    }
}
