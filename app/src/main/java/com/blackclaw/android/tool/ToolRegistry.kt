package com.blackclaw.android.tool

import com.blackclaw.android.agent.knowledge.*
import com.blackclaw.android.tool.impl.*
import com.blackclaw.android.tool.impl.mobile.*
import com.blackclaw.android.tool.impl.tv.*

object ToolRegistry {

    enum class DeviceType { TV, MOBILE }

    private val tools = LinkedHashMap<String, BaseTool>()
    var deviceType: DeviceType = DeviceType.TV
        private set

    @JvmStatic
    fun getInstance(): ToolRegistry = this

    fun registerAllTools(type: DeviceType = DeviceType.TV) {
        deviceType = type
        tools.clear()
        registerCommonTools()
        when (type) {
            DeviceType.TV -> registerTvTools()
            DeviceType.MOBILE -> registerMobileTools()
        }
    }

    private fun registerCommonTools() {
        register(GetScreenInfoTool())
        register(FindNodeInfoTool())
        register(InputTextTool())
        register(SystemKeyTool())
        register(OpenAppTool())
        register(GetInstalledAppsTool())
        register(TakeScreenshotTool())
        register(WaitTool())
        register(RepeatActionsTool())
        register(ClipboardTool())
        // Progressive tool disclosure: lets the LLM load tools from the catalog
        register(RequestToolTool())
        register(SendFileTool())
        register(GetDeviceInfoTool())
        register(GetNotificationsTool())
        register(MakeCallTool())
        register(FinishTool())

        // Knowledge Base tools — shared vault available in all modes
        register(KbWriteTool())
        register(KbReadTool())
        register(KbSearchTool())
        register(KbAppendTool())
        register(KbAddTodoTool())

        // Long-term memory facts (lightweight per-fact recall)
        register(RememberFactTool())
        register(RecallFactsTool())
        register(ForgetFactTool())

        // Persistent task scheduling (cron-style)
        register(ScheduleTaskTool())
        register(ListScheduledTasksTool())
        register(CancelScheduledTaskTool())

        // Native Assistant hub — reminders, alarms, notes, events, alerts, finance.
        // The AI writes here instead of bouncing out to external Clock/Calendar apps.
        register(AssistantReminderTool())
        register(AssistantAlarmTool())
        register(AssistantNoteTool())
        register(AssistantEventTool())
        register(AssistantAlertTool())
        register(AssistantFinanceTool())
        register(AssistantListTool())
        register(AssistantRemoveTool())
        register(AssistantLocationReminderTool())
        register(AssistantShoppingTool())
        register(AssistantBudgetTool())
        register(AssistantMedicationTool())
        register(AssistantTrackPromiseTool())
        register(AssistantLeaveReminderTool())

        // Direct device control (no UI navigation needed)
        register(SetVolumeTool())
        register(SetBrightnessTool())
        register(ToggleSettingTool())

        // App-level helpers
        register(GetForegroundAppTool())
        register(CloseAppTool())
        register(ShowRecentsTool())
        register(OpenUrlTool())
        register(WebSearchTool())
        register(ShareTextTool())

        // Calendar / SMS / Contacts / Call log
        register(GetCalendarEventsTool())
        register(CreateCalendarEventTool())
        register(GetSmsTool())
        register(SendSmsTool())
        register(GetCallLogTool())
        register(FindContactTool())

        // System integrations
        register(SetAlarmTool())
        register(OpenCameraTool())
        register(SpeakTextTool())
        register(MediaControlTool())
        register(FlashlightTool())
        register(VibrateTool())
        register(SystemNotifyTool())
        register(HttpFetchTool())
        register(MathEvalTool())
        register(RunSkillTool())

        // App management
        register(AppInfoTool())
        register(UninstallAppTool())
        register(AppSettingsTool())
        register(CreateShortcutTool())

        // Misc helpers
        register(GetLocationTool())
        register(CountTool())
        register(QrGenerateTool())
        register(WriteFileTool())
        register(ReadFileTool())
        register(Base64Tool())
        register(RandomTool())
        register(TranslateTool())
        register(ImportChatExportTool())

        // Network / device telemetry
        register(WeatherTool())
        register(NetworkSpeedTool())
        register(PingHostTool())
        register(PublicIpTool())
        register(ConnectivityCheckTool())
        register(PowerInfoTool())
        register(MemoryInfoTool())
        register(SunInfoTool())

        // Text / data utilities
        register(HashTextTool())
        register(UrlEncodeTool())
        register(ColorTool())
        register(UnitConvertTool())
        register(CurrencyTool())
        register(RegexExtractTool())
        register(JsonQueryTool())
        register(DateMathTool())
        register(SummarizeTextTool())
        register(AppShortcutsTool())

        // Perception (game / surface support via screen capture + OCR)
        register(ReadScreenOcrTool())
        register(TapOcrTool())

        // Multi-step planning (skip LLM rounds when the plan is obvious)
        register(ExecutePlanTool())

        // Shell access via Shizuku (optional — only enabled if user activates Shizuku)
        register(ShellExecTool())
        register(FastTapTool())
        register(FastSwipeTool())
        register(ForceStopAppTool())
    }

    private fun registerTvTools() {
        register(DpadUpTool())
        register(DpadDownTool())
        register(DpadLeftTool())
        register(DpadRightTool())
        register(DpadCenterTool())
        register(VolumeUpTool())
        register(VolumeDownTool())
        register(PressMenuTool())
        register(PressPowerTool())
    }

    private fun registerMobileTools() {
        register(TapTool())
        register(TapNodeTool())
        register(LongPressTool())
        register(SwipeTool())
        register(ScrollToFindTool())
        register(FindAndTapTool())
        register(SendMessageTool())
        register(AutoReplyTool())
        // Advanced gesture tools (work without Shizuku, all via accessibility)
        register(TapBurstTool())
        register(PinchTool())
        register(DragDropTool())
        register(PathTraceTool())
    }

    fun register(tool: BaseTool) {
        tools[tool.getName()] = tool
    }

    fun getTool(name: String): BaseTool? = tools[name]

    fun getDisplayName(name: String): String = tools[name]?.getDisplayName() ?: name

    fun getAllTools(): List<BaseTool> = tools.values.toList()

    fun executeTool(name: String, params: Map<String, Any>): ToolResult {
        val tool = tools[name] ?: return ToolResult.error("Unknown tool: $name")

        // Per-task TTL cache for stable read-only tools (saves time + tokens
        // when the LLM redundantly re-queries within one task).
        ToolResultCache.get(name, params)?.let { cached ->
            return cached
        }

        return try {
            val result = tool.executeWithWaitAfter(params)
            ToolResultCache.put(name, params, result)
            result
        } catch (e: Exception) {
            com.blackclaw.android.utils.XLog.e("ToolRegistry", "Tool '$name' execution failed with params=$params", e)
            ToolResult.error("Tool execution failed: ${e.message}")
        }
    }

    /** Wipe the per-task tool cache. Called by the agent service at task start/end. */
    fun clearCache() {
        ToolResultCache.clear()
    }
}
