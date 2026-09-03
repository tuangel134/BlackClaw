package com.blackclaw.android.agent

import com.blackclaw.android.assistant.RoutineEngine
import com.blackclaw.android.utils.XLog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Learning by demonstration — records the tool calls the agent makes during a
 * task so the flow can be replayed later as a saved Routine (à la Rabbit's LAM
 * "teach by showing").
 *
 * Flow:
 *   1. User: "aprende esto" / starts recording → start(label).
 *   2. The agent loop reports each successful, replayable tool call → record().
 *   3. User: "guárdalo como rutina X" / task ends → saveAsRoutine(name).
 *
 * Only deterministic, replayable tools are captured. Observation/perception
 * tools (get_screen_info, read_screen_ocr, finish, etc.) are skipped because
 * replaying them adds no value and node IDs are not stable across runs.
 */
object DemonstrationRecorder {

    private const val TAG = "DemoRecorder"

    data class RecordedStep(
        val toolName: String,
        val params: Map<String, Any>,
        val description: String,
    )

    @Volatile private var recording = false
    @Volatile private var label: String = ""
    private val steps = CopyOnWriteArrayList<RecordedStep>()

    /**
     * Passive buffer of the CURRENT task's replayable steps, captured ALWAYS
     * (even without an explicit "aprende esto"). Lets the user say afterwards
     * "guarda lo último como rutina X" with zero setup. Reset at task start.
     */
    private val passive = CopyOnWriteArrayList<RecordedStep>()

    /** Tools that are NOT worth replaying (observation / perception / control). */
    private val NON_REPLAYABLE = setOf(
        "get_screen_info", "find_node_info", "read_screen_ocr", "take_screenshot",
        "get_foreground_app", "get_installed_apps", "verify_screen", "wait",
        "finish", "request_tool", "get_notifications", "get_device_info",
        "game_observe", "game_record_macro", "game_macro", "game_autoclicker",
        // Node-id based taps aren't stable across runs; prefer text/coord tools.
        "tap_node",
    )

    fun isRecording(): Boolean = recording

    fun start(label: String = "") {
        this.recording = true
        this.label = label
        steps.clear()
        XLog.i(TAG, "Recording started: labelChars=${label.length}")
    }

    /** Record a successful tool call. Called by the agent loop after execution. */
    fun record(toolName: String, params: Map<String, Any>, success: Boolean) {
        if (!success) return
        if (toolName in NON_REPLAYABLE) return
        val step = RecordedStep(toolName, params, buildDescription(toolName, params))
        // Always feed the passive buffer (frictionless learning).
        passive.add(step)
        // And the explicit recording buffer when the user asked to "learn this".
        if (recording) {
            steps.add(step)
            XLog.d(TAG, "Recorded step: $toolName")
        }
    }

    /** Call at the start of each task to reset the passive buffer. */
    fun noteTaskStart() {
        if (!recording) passive.clear()
    }

    fun lastTaskSteps(): List<RecordedStep> = passive.toList()

    /** Save the LAST task's passively-captured steps as a routine. */
    fun saveLastAsRoutine(name: String, icon: String = "🐾", triggerTime: String = ""): String? {
        val captured = passive.toList()
        if (captured.isEmpty()) return null
        return persistRoutine(captured, name, icon, triggerTime)
    }

    fun stop(): List<RecordedStep> {
        recording = false
        return steps.toList()
    }

    fun currentSteps(): List<RecordedStep> = steps.toList()

    /**
     * Save the recorded steps as a Routine. Returns the routine name on success
     * or null if nothing useful was recorded.
     */
    fun saveAsRoutine(name: String, icon: String = "🎬", triggerTime: String = ""): String? {
        val captured = stop()
        if (captured.isEmpty()) {
            XLog.w(TAG, "saveAsRoutine: nothing recorded")
            return null
        }
        return persistRoutine(captured, name, icon, triggerTime)
    }

    private fun persistRoutine(
        captured: List<RecordedStep>, name: String, icon: String, triggerTime: String,
    ): String? {
        val routineSteps = captured.map {
            RoutineEngine.RoutineStep(
                toolName = it.toolName,
                params = it.params,
                delayAfterMs = 1200,
                description = it.description,
            )
        }
        val routine = RoutineEngine.create(RoutineEngine.Routine(
            id = "",
            name = name,
            description = "Aprendida por demostración (${captured.size} pasos)",
            icon = icon,
            steps = routineSteps,
            triggerTime = triggerTime,
            triggerDays = if (triggerTime.isNotBlank()) "daily" else "",
        )) ?: return null
        XLog.i(TAG, "Saved demonstration as routine: steps=${routineSteps.size}")
        return routine.name
    }

    private fun buildDescription(toolName: String, params: Map<String, Any>): String {
        return when (toolName) {
            "open_app" -> "Abrir ${params["package_name"] ?: params["app"] ?: "app"}"
            "input_text" -> "Escribir \"${(params["text"] ?: "").toString().take(30)}\""
            "find_and_tap" -> "Tocar \"${params["text"]}\""
            "send_message" -> "Enviar mensaje a ${params["contact"]}"
            "system_key" -> "Tecla ${params["key"]}"
            "open_url" -> "Abrir ${params["url"]}"
            "toggle_setting" -> "${params["setting"]} → ${params["state"]}"
            else -> "$toolName ${params.entries.take(2).joinToString(", ") { "${it.key}=${it.value}" }}"
        }
    }
}
