package com.blackclaw.android.memory

import com.blackclaw.android.agent.TaskHistoryStore
import com.blackclaw.android.assistant.RoutineEngine

/**
 * Unified memory coordinator.
 *
 * BlackClaw has several memory subsystems that each want space in the system
 * prompt:
 *   - UserProfile      → who the user is (highest value, most stable)
 *   - UserMemoryStore  → explicit facts the user asked to remember
 *   - ConversationMemory → summaries of past chats
 *   - TaskHistoryStore → recent task back-references
 *   - RoutineEngine    → available routines
 *
 * Left unmanaged, these append blindly and can bloat the prompt (bad for local
 * models and cloud rate limits). MemoryHub assembles them in PRIORITY order
 * under a single character budget, so the most valuable context always makes it
 * in and the rest is trimmed gracefully.
 */
object MemoryHub {

    /**
     * Default budget for the whole memory block (characters, not tokens — a
     * rough 4:1 ratio means ~1k chars ≈ 250 tokens). Local models get less.
     */
    const val DEFAULT_BUDGET_CHARS = 2400
    const val LOCAL_BUDGET_CHARS = 1400

    internal data class Section(val priority: Int, val text: String)

    /**
     * Greedily pack [sections] by priority into [budgetChars].
     *
     * Split out from [assemble] because this is the part with actual consequences —
     * it decides what the model does *not* get to see — while [assemble] is only
     * plumbing to five stores that each need MMKV and therefore a device. Keeping the
     * decision pure means it can be verified.
     *
     * Stops at the first section that does not fit rather than skipping it and trying
     * the next: sections are ordered by value, so continuing past a rejection would
     * trade something important for something less important purely because the less
     * important one was shorter. Sections are never truncated mid-sentence, because a
     * half-sentence of user profile is worse than none — it invites the model to
     * infer the missing half.
     */
    internal fun packByPriority(sections: List<Section>, budgetChars: Int): String {
        if (budgetChars <= 0) return ""
        val sb = StringBuilder()
        var used = 0
        for (section in sections.sortedBy { it.priority }) {
            if (section.text.isBlank()) continue
            if (used + section.text.length > budgetChars) break
            sb.append(section.text)
            used += section.text.length
        }
        return sb.toString()
    }

    /**
     * Assemble the combined memory prompt section under [budgetChars].
     * Sections are added in priority order; once the budget is exhausted,
     * lower-priority sections are dropped (not truncated mid-sentence).
     */
    fun assemble(budgetChars: Int = DEFAULT_BUDGET_CHARS): String {
        val sections = mutableListOf<Section>()

        // Priority 1: who the user is — most valuable, very stable.
        UserProfile.asPromptSnippet().takeIf { it.isNotBlank() }
            ?.let { sections.add(Section(1, it)) }

        // Priority 2: explicit facts the user asked to remember.
        UserMemoryStore.asPromptSnippet(maxFacts = 20).takeIf { it.isNotBlank() }
            ?.let { sections.add(Section(2, it)) }

        // Priority 3: available routines (so the AI can offer/run them).
        RoutineEngine.asPromptSnippet().takeIf { it.isNotBlank() }
            ?.let { sections.add(Section(3, it)) }

        // Priority 4: recent task back-references ("again", "same person").
        TaskHistoryStore.asPromptSnippet().takeIf { it.isNotBlank() }
            ?.let { sections.add(Section(4, it)) }

        // Priority 5: past conversation summaries (nice-to-have continuity).
        ConversationMemory.asPromptSnippet(maxEntries = 4).takeIf { it.isNotBlank() }
            ?.let { sections.add(Section(5, it)) }

        return packByPriority(sections, budgetChars)
    }

    /**
     * Convenience for the agent loop: pick the budget based on whether the
     * active model is local (tighter context window).
     */
    fun assembleForProvider(isLocal: Boolean): String =
        assemble(if (isLocal) LOCAL_BUDGET_CHARS else DEFAULT_BUDGET_CHARS)
}
