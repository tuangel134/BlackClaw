package com.blackclaw.android.agent

import android.content.Context
import android.os.BatteryManager
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.utils.XLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a tiny "what's true right now" snippet to inject into every system prompt.
 *
 * Cheap to compute (no I/O beyond a battery query) and small (< 200 chars).
 * Helps the LLM avoid wasting tool calls on trivial state queries the user
 * never asked about (e.g. "the user's phone is at 73% so don't warn about battery").
 *
 * Failure is silent — if anything throws we just skip that field. The agent
 * loop must still work without the snippet.
 */
object AmbientContext {
    private const val TAG = "AmbientContext"

    /** Returns a single-line snippet like:
     *  "Now: 2026-05-28 10:42 (Thu); battery 73% charging; foreground com.android.settings"
     *  May return empty string on catastrophic failure. */
    fun build(): String {
        val parts = mutableListOf<String>()
        runCatching {
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm (EEE)", Locale.getDefault())
            parts.add("Now: ${df.format(Date())}")
        }
        runCatching {
            val ctx = ClawApplication.instance
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return@runCatching
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                           status == BatteryManager.BATTERY_STATUS_FULL
            parts.add("battery $pct%${if (charging) " charging" else ""}")
        }
        runCatching {
            val service = com.blackclaw.android.service.ClawAccessibilityService.getInstance()
            val pkg = service?.rootInActiveWindow?.packageName?.toString()
            if (!pkg.isNullOrBlank()) {
                parts.add("foreground $pkg")
            }
        }
        runCatching {
            when (com.blackclaw.android.adb.PrivilegedShell.activeBackend()) {
                com.blackclaw.android.adb.PrivilegedShell.Backend.SHIZUKU ->
                    parts.add("privileged shell: Shizuku (fast_tap/fast_swipe/shell_exec/force_stop_app OK)")
                com.blackclaw.android.adb.PrivilegedShell.Backend.ADB ->
                    parts.add("privileged shell: self-ADB (fast_tap/fast_swipe/shell_exec/force_stop_app OK)")
                com.blackclaw.android.adb.PrivilegedShell.Backend.NONE ->
                    parts.add("privileged shell: none (use accessibility tap/swipe)")
            }
        }
        return if (parts.isEmpty()) "" else parts.joinToString("; ")
    }

    /** Wraps the snippet in a system-prompt header. Returns "" if no parts collected. */
    fun asPromptSection(): String {
        val s = build()
        if (s.isBlank()) return ""
        XLog.d(TAG, "ambient: $s")
        return "\n\n## Ambient state\n$s\n"
    }
}
