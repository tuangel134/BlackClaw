package com.blackclaw.android.tool.impl

import android.app.ActivityManager
import android.content.Context
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

class MemoryInfoTool : BaseTool() {
    override fun getName() = "memory_info"
    override fun getDisplayName() = "Memoria"
    override fun getDescriptionEN() =
        "RAM info: total/available, low memory threshold, swap usage, " +
        "this app's heap usage."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getParameters() = emptyList<ToolParameter>()

    override fun execute(params: Map<String, Any>): ToolResult {
        val ctx = ClawApplication.instance
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val total = info.totalMem
        val avail = info.availMem
        val used = total - avail
        val pct = (used * 100 / total).toInt()
        val rt = Runtime.getRuntime()
        val heapUsed = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
        val heapMax = rt.maxMemory() / 1024 / 1024
        return ToolResult.success(
            "RAM dispositivo: ${used / 1024 / 1024} MB usados de ${total / 1024 / 1024} MB ($pct%)\n" +
            "Disponible: ${avail / 1024 / 1024} MB\n" +
            "Memoria baja: ${if (info.lowMemory) "SÍ ⚠️" else "no"} (umbral ${info.threshold / 1024 / 1024} MB)\n" +
            "Heap BlackClaw: ${heapUsed} / ${heapMax} MB"
        )
    }
}
