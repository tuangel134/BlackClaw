package com.blackclaw.android.agent

import com.blackclaw.android.memory.UserMemoryStore
import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog

/**
 * Prompt composition helpers (#45 persistent global prompt + user memory facts).
 *
 * Layered injection (top to bottom in final prompt):
 *  1. User's persistent global instructions (KVUtils.getGlobalPrompt)
 *  2. Long-term remembered facts (UserMemoryStore.asPromptSnippet)
 *  3. The base/system prompt
 *
 * Empty / blank user global prompt = no-op for layer 1. Empty memory store = no-op for layer 2.
 */
object PromptUtils {
    private const val TAG = "PromptUtils"

    private const val PREFIX_HEADER = "User's persistent global instructions:"
    private const val SEPARATOR = "\n\n---\n\n"

    /**
     * Returns the base prompt augmented with the user's global instructions and
     * remembered facts when present. Stable separator so downstream debug-report
     * tooling can detect injection.
     */
    fun applyGlobalPrompt(basePrompt: String): String {
        val global = KVUtils.getGlobalPrompt()
        val memory = UserMemoryStore.asPromptSnippet()

        if (global.isBlank() && memory.isBlank()) {
            XLog.d(TAG, "applyGlobalPrompt: no injections, returning base (${basePrompt.length} chars)")
            return basePrompt
        }

        val sb = StringBuilder()
        if (global.isNotBlank()) {
            sb.append(PREFIX_HEADER).append('\n').append(global).append(SEPARATOR)
            XLog.i(TAG, "applyGlobalPrompt: injecting global prompt (${global.length} chars)")
        }
        if (memory.isNotBlank()) {
            sb.append(memory.trimStart()).append(SEPARATOR)
            XLog.i(TAG, "applyGlobalPrompt: injecting memory facts (${memory.length} chars)")
        }
        sb.append(basePrompt)
        return sb.toString()
    }
}
