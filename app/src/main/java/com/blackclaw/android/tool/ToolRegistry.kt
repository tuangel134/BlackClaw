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
        // Visual verification — confirm an action worked (a11y + OCR)
        register(VerifyScreenTool())
        // Progressive tool disclosure: lets the LLM load tools from the catalog
        register(RequestToolTool())
        register(SendFileTool())
        register(GetDeviceInfoTool())
        register(GetNotificationsTool())
        register(ReplyNotificationTool())
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
        register(AutomationRuleTool())
        register(AutomationProfileTool())

        // Native Assistant hub — reminders, alarms, notes, events, alerts, finance.
        // The AI writes here instead of bouncing out to external Clock/Calendar apps.
        register(AssistantReminderTool())
        register(AssistantAlarmTool())
        register(AssistantNoteTool())
        register(AssistantEventTool())
        register(AssistantAppointmentTool())
        register(AssistantAgendaTool())
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
        register(AssistantDraftReplyTool())
        register(AssistantRecurringBillTool())
        register(AssistantSavingsGoalTool())
        register(AssistantExportFinanceTool())
        register(UndoLastTool())

        // Routines — multi-step automated sequences
        register(RunRoutineTool())
        register(ListRoutinesTool())
        register(CreateRoutineTool())
        register(DeleteRoutineTool())

        // User profile learning — auto-builds a profile of the user
        register(LearnUserTool())

        // Smart Home — webhook-based device control
        register(SmartHomeTool())
        register(ListSmartDevicesTool())
        register(AddSmartDeviceTool())

        // Habit Tracking — streaks, daily goals, progress
        register(HabitLogTool())
        register(HabitStatusTool())
        register(HabitCreateTool())

        // Focus Mode / Pomodoro
        register(FocusModeTool())
        register(FocusStopTool())

        // Wellness — mood, sleep, energy tracking
        register(MoodLogTool())
        register(WellnessStatusTool())
        register(SleepLogTool())

        // Remote Shell — SSH to PC/servers + local terminal
        register(RemoteShellTool())
        register(RemoteConnectTool())
        register(RemoteDisconnectTool())
        register(RemoteListTool())
        register(RemoteDiagnoseTool())
        register(LocalTerminalTool())

        // PC co-pilot: persistent remote monitoring with alerts
        register(RemoteMonitorTool())
        register(StopMonitorTool())
        register(ListMonitorsTool())

        // Learning by demonstration — record actions, save as routine
        register(StartDemoTool())
        register(SaveDemoTool())
        register(CancelDemoTool())
        register(SaveLastAsRoutineTool())

        // Hands-free voice mode (wake word + STT)
        register(VoiceModeTool())

        // Direct device control (no UI navigation needed)
        register(SetVolumeTool())
        register(SetBrightnessTool())
        register(ToggleSettingTool())

        // App-level helpers
        register(GetForegroundAppTool())
        register(CloseAppTool())
        register(ShowRecentsTool())
        register(OpenUrlTool())
        register(OpenAppActionTool())
        register(DiscoverAppActionsTool())
        register(PlayMusicTool())
        register(SetMusicPlayerTool())
        register(WebSearchTool())
        register(WebAnswerTool())
        register(ShareTextTool())

        // Calendar / SMS / Contacts / Call log
        register(GetCalendarEventsTool())
        register(CreateCalendarEventTool())
        register(GetSmsTool())
        register(SendSmsTool())
        register(GetCallLogTool())
        register(FindContactTool())
        register(CreateContactsTool())

        // System integrations
        register(SetAlarmTool())
        register(OpenCameraTool())
        register(SpeakTextTool())
        register(MediaControlTool())
        register(FlashlightTool())
        register(EmergencyModeTool())
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

        // Security / antimalware — scan apps, find ad culprits, block/uninstall
        register(SecurityScanTool())
        register(FindAdCulpritTool())
        register(BlockAppTool())

        // Misc helpers
        register(GetLocationTool())
        register(CountTool())
        register(QrGenerateTool())
        register(WriteFileTool())
        register(ReadFileTool())
        register(RecognizeSongTool())
        register(ZimConsultTool())
        register(ZimSearchTool())
        register(ZimReadTool())
        register(ZimIndexTool())
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
        register(GameObserveTool())
        register(GameActionTool())
        register(GameRecordMacroTool())
        register(GameMacroTool())
        register(GameAutoclickerTool())
        // Photo OCR + receipt scanning (vision over shared images)
        register(OcrImageTool())
        register(ScanReceiptTool())

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
        register(ListOptionsTool())
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

    private val toolExecutor = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "tool-exec").apply { isDaemon = true }
    }

    private const val DEFAULT_TIMEOUT_MS = 30_000L
    private val LONG_RUNNING_TOOLS = setOf(
        "web_search", "web_answer", "http_fetch", "remote_shell", "remote_connect",
        "translate", "read_screen_ocr", "tap_ocr", "ocr_image", "scan_receipt",
        "zim_consult", "zim_search", "zim_index", "network_speed", "ping_host",
        "game_observe", "execute_plan", "terminal",
    )
    private const val LONG_TIMEOUT_MS = 120_000L

    /**
     * Single choke point for tool execution, and therefore the right place for the
     * risk gate.
     *
     * The gate used to live only inside the agent loop, so every other caller —
     * `ExecutePlanTool`, `DebugTaskReceiver`, the config server's debug endpoint —
     * reached every tool ungated. Enforcing here means all of them inherit it.
     */
    fun executeTool(name: String, params: Map<String, Any>): ToolResult {
        val tool = tools[name] ?: return ToolResult.error("Unknown tool: $name")

        // Provenance gate. Arbitrary-command tools are unreachable from a remote or
        // unattributed request, and locally require a recent, expiring opt-in.
        val decision = com.blackclaw.android.tool.guard.ToolRiskPolicy.evaluate(
            toolName = name,
            origin = com.blackclaw.android.tool.guard.ToolExecutionContext.origin,
            privilegedArmed = com.blackclaw.android.tool.guard.PrivilegedToolConsent.isArmed(),
        )
        if (decision is com.blackclaw.android.tool.guard.ToolRiskPolicy.Decision.Deny) {
            com.blackclaw.android.utils.XLog.w(
                "ToolRegistry",
                "Blocked '$name' (origin=${com.blackclaw.android.tool.guard.ToolExecutionContext.origin}): ${decision.reason}",
            )
            return ToolResult.error(decision.reason)
        }
        // Profile learning. This is the only place every execution path passes through
        // (agent loop, skills, plans, Tier-1 shortcuts, debug), which is why the
        // previous approach — recording from one Activity — left UserProfile's
        // topApps/topContacts branches permanently unreachable. No-ops for tools that
        // say nothing about the user's habits.
        runCatching { com.blackclaw.android.memory.UserProfile.recordToolUse(name, params) }

        if (com.blackclaw.android.tool.guard.ToolRiskPolicy.shouldAudit(name)) {
            com.blackclaw.android.utils.XLog.i(
                "ToolRegistry",
                "Running '$name' (tier=${com.blackclaw.android.tool.guard.ToolRiskPolicy.classify(name)}, " +
                    "origin=${com.blackclaw.android.tool.guard.ToolExecutionContext.origin})",
            )
        }

        ToolResultCache.get(name, params)?.let { cached ->
            return cached
        }

        val timeoutMs = if (name in LONG_RUNNING_TOOLS) LONG_TIMEOUT_MS else DEFAULT_TIMEOUT_MS
        return try {
            val future = toolExecutor.submit<ToolResult> { tool.executeWithWaitAfter(params) }
            val result = future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            ToolResultCache.put(name, params, result)
            result
        } catch (e: java.util.concurrent.TimeoutException) {
            com.blackclaw.android.utils.XLog.e("ToolRegistry", "Tool '$name' timed out after ${timeoutMs}ms")
            ToolResult.error("Tool '$name' timed out after ${timeoutMs / 1000}s. The action may still be in progress.")
        } catch (e: Exception) {
            val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
            // Parameter values can contain passwords, message bodies, tokens and other
            // private data. Keys are enough to diagnose schema/caller mistakes.
            com.blackclaw.android.utils.XLog.e(
                "ToolRegistry",
                "Tool '$name' execution failed (paramKeys=${params.keys.sorted()})",
                cause,
            )
            ToolResult.error("Tool execution failed: ${cause.message}")
        }
    }

    /** Wipe the per-task tool cache. Called by the agent service at task start/end. */
    fun clearCache() {
        ToolResultCache.clear()
    }
}
