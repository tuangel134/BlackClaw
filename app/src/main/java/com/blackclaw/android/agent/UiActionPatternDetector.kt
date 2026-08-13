package com.blackclaw.android.agent

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.util.ArrayDeque
import java.util.LinkedHashMap

/**
 * Finds short, repeated UI action sequences inside one task.
 *
 * This is deliberately an in-memory accelerator, not a recorder of coordinates
 * across sessions. Text values are treated as slots, while taps and their targets
 * remain part of the signature. A match only produces a hint for the model to use
 * execute_plan; it never replays clicks behind the model's back.
 */
class UiActionPatternDetector {

    data class Step(val toolName: String, val params: Map<String, Any>)

    data class Match(
        val steps: List<Step>,
        val repetitions: Int,
    ) {
        fun buildHint(): String {
            var valueSlot = 0
            val jsonSteps = steps.map { step ->
                val params = LinkedHashMap(step.params)
                if (step.toolName == "input_text" || step.toolName == "type_text") {
                    valueSlot++
                    params["text"] = "<VALUE_$valueSlot>"
                }
                linkedMapOf<String, Any>(
                    "tool" to step.toolName,
                    "params" to params,
                )
            }
            val json = JSON.toJson(jsonSteps)
            return "[Optimization detected] The same UI sequence was completed " +
                "$repetitions times. For the next item, prefer execute_plan with " +
                "this template, replacing each <VALUE_N> only with the new text: " +
                "$json. Keep the fixed taps unchanged, but observe the screen first " +
                "and abort the plan if the form or target screen differs. Do not use " +
                "the previous text values."
        }
    }

    private data class RecordedStep(
        val step: Step,
        val signature: String,
    )

    private val history = ArrayDeque<RecordedStep>()
    private val announced = HashSet<String>()

    /** Records a successful action and returns a new pattern only once per shape. */
    fun record(toolName: String, params: Map<String, Any>): Match? {
        if (toolName !in CANDIDATE_TOOLS) return null
        val step = Step(toolName, LinkedHashMap(params))
        history.addLast(RecordedStep(step, signature(step)))
        while (history.size > MAX_HISTORY) history.removeFirst()

        val maxLength = minOf(MAX_PATTERN_LENGTH, history.size / 2)
        for (length in maxLength downTo MIN_PATTERN_LENGTH) {
            val recent = history.toList().takeLast(length * 2)
            val first = recent.take(length).map { it.signature }
            val second = recent.drop(length).map { it.signature }
            if (first != second || first.distinct().size < 2) continue
            if (recent.none { it.step.toolName == "input_text" || it.step.toolName == "type_text" }) continue

            val key = first.joinToString("→")
            if (!announced.add(key)) continue
            return Match(
                steps = recent.take(length).map { it.step },
                repetitions = 2,
            )
        }
        return null
    }

    fun reset() {
        history.clear()
        announced.clear()
    }

    private fun signature(step: Step): String {
        val stableParams = LinkedHashMap(step.params)
        stableParams.remove("text")
        // wait_after affects timing but not the UI shape; omitting it avoids
        // treating two otherwise identical actions as different patterns.
        stableParams.remove("wait_after")
        return step.toolName + "|" + JSON.toJson(stableParams)
    }

    companion object {
        private const val MIN_PATTERN_LENGTH = 2
        private const val MAX_PATTERN_LENGTH = 6
        private const val MAX_HISTORY = 24
        private val JSON: Gson = GsonBuilder().disableHtmlEscaping().create()

        private val CANDIDATE_TOOLS = setOf(
            "tap", "tap_node", "tap_ocr", "find_and_tap", "input_text", "type_text",
            "swipe", "scroll_to_find", "system_key", "open_app", "open_url",
        )
    }
}
