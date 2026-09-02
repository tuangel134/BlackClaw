package com.blackclaw.android.proactive

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** Visible launcher apps that can be selected for proactive notification monitoring. */
object ProactiveAppCatalog {
    data class Entry(val packageName: String, val label: String, val isSystem: Boolean)

    fun load(context: Context): List<Entry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        return resolved.asSequence()
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                val label = runCatching { info.loadLabel(pm).toString().trim() }
                    .getOrDefault(pkg).ifBlank { pkg }
                Entry(
                    packageName = pkg,
                    label = label,
                    isSystem = ai != null && (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
    }
}
