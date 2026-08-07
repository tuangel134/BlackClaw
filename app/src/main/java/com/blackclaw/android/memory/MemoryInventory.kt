package com.blackclaw.android.memory

/**
 * What BlackClaw has learned about the user, in a form a privacy screen can show.
 *
 * ## Why this exists
 *
 * Everything the memory subsystem stores — name, city, sleep schedule, frequent
 * contacts, favourite apps, arbitrary learned traits, explicitly remembered facts and
 * summaries of past conversations — is injected into the system prompt on **every**
 * request, which for a cloud model means it leaves the device every time. Until now
 * there was no screen showing any of it and `forgetAll()` had no callers, so the user
 * could neither see nor erase it.
 *
 * The counts and previews are assembled here rather than in the Composable so the
 * screen cannot disagree with what is actually stored, and so the "what leaves the
 * device" estimate can be tested.
 */
object MemoryInventory {

    /** One line the user can read, with enough context to decide whether to keep it. */
    data class Item(val label: String, val detail: String)

    data class Category(
        val id: String,
        val title: String,
        /** Plain-language statement of what this is and where it comes from. */
        val explanation: String,
        val count: Int,
        /** A few representative entries; never the whole store. */
        val preview: List<Item>,
        /** True when this category contributes to the prompt sent to the model. */
        val leavesDevice: Boolean,
    )

    data class Snapshot(
        val categories: List<Category>,
        /** Characters the memory block currently adds to each prompt. */
        val promptCostChars: Int,
    ) {
        val totalItems: Int get() = categories.sumOf { it.count }
        val isEmpty: Boolean get() = totalItems == 0
    }

    private const val PREVIEW_LIMIT = 4

    /**
     * Read everything. Touches MMKV, so call it off the main thread when possible.
     *
     * Each store read is guarded independently: one corrupt store must not stop the
     * user from seeing — and erasing — the others. A privacy screen that fails to load
     * is a privacy screen that cannot be used to delete anything.
     */
    fun snapshot(): Snapshot {
        val profile = runCatching { UserProfile.get() }.getOrDefault(UserProfile.Profile())
        val profileLines = runCatching { UserProfile.snippetLines(profile) }.getOrDefault(emptyList())
        val facts = runCatching { UserMemoryStore.all() }.getOrDefault(emptyList())
        val conversations = runCatching { ConversationMemory.all() }.getOrDefault(emptyList())
        val tasks = runCatching {
            com.blackclaw.android.agent.TaskHistoryStore.all()
        }.getOrDefault(emptyList())

        val categories = listOf(
            Category(
                id = "profile",
                title = "Perfil aprendido",
                explanation = "Deducido de cómo usas el teléfono: horarios, apps y " +
                    "contactos frecuentes. Nadie te lo preguntó; se infiere solo.",
                count = profileLines.size,
                preview = profileLines.take(PREVIEW_LIMIT).map(::parseProfileLine),
                leavesDevice = true,
            ),
            Category(
                id = "facts",
                title = "Hechos que pediste recordar",
                explanation = "Lo que guardaste a propósito, con \"recuerda que…\".",
                count = facts.size,
                preview = facts.takeLast(PREVIEW_LIMIT).map { Item(it.key, it.value) },
                leavesDevice = true,
            ),
            Category(
                id = "conversations",
                title = "Resúmenes de conversaciones",
                explanation = "Un resumen corto de cada charla anterior, para poder " +
                    "retomar referencias de otro día.",
                count = conversations.size,
                preview = conversations.takeLast(PREVIEW_LIMIT).map {
                    Item(it.topics.firstOrNull() ?: "Conversación", it.summary)
                },
                leavesDevice = true,
            ),
            Category(
                id = "tasks",
                title = "Tareas recientes",
                explanation = "Qué le pediste hacer en las últimas horas, para resolver " +
                    "\"otra vez\" o \"a la misma persona\".",
                count = tasks.size,
                preview = tasks.take(PREVIEW_LIMIT).map { Item(it.task, it.outcome) },
                leavesDevice = true,
            ),
        )

        val promptCost = runCatching { MemoryHub.assemble().length }.getOrDefault(0)
        return Snapshot(categories, promptCost)
    }

    /**
     * Split a profile snippet line into label and value for display.
     *
     * The lines come from [UserProfile.snippetLines], which formats them as
     * `"- Ciudad: Monterrey"` for the prompt. Splitting on the first colon keeps the
     * value intact when it contains one itself (a sleep schedule reads "23:00 - 07:00",
     * so splitting on the last colon or on every colon would mangle exactly the field
     * most likely to appear).
     *
     * A line with no colon becomes an all-label item rather than an empty row: showing
     * the raw text is worse than useless only if it is blank.
     */
    fun parseProfileLine(line: String): Item {
        val clean = line.removePrefix("- ").trim()
        val idx = clean.indexOf(':')
        if (idx < 0) return Item(clean, "")
        return Item(clean.substring(0, idx).trim(), clean.substring(idx + 1).trim())
    }

    /**
     * How many things are stored, without building previews or the prompt estimate.
     *
     * The settings row only needs a badge number, and [snapshot] runs a full
     * [MemoryHub.assemble] — too much work for a list row that renders on the main
     * thread while the screen is being laid out.
     */
    fun totalCount(): Int =
        runCatching { UserProfile.snippetLines(UserProfile.get()).size }.getOrDefault(0) +
            runCatching { UserMemoryStore.all().size }.getOrDefault(0) +
            runCatching { ConversationMemory.all().size }.getOrDefault(0) +
            runCatching { com.blackclaw.android.agent.TaskHistoryStore.all().size }.getOrDefault(0)

    /**
     * Erase one category. Returns how many entries were removed.
     *
     * Deleting the profile also clears the interaction log: leaving it would let the
     * next `learnFromInteractions()` rebuild the very profile the user just deleted,
     * which would make the delete button a lie.
     */
    fun forget(categoryId: String): Int = when (categoryId) {
        "profile" -> runCatching { UserProfile.forgetEverything() }.getOrDefault(0)
        "facts" -> runCatching { UserMemoryStore.forgetAll() }.getOrDefault(0)
        "conversations" -> runCatching { ConversationMemory.forgetAll() }.getOrDefault(0)
        "tasks" -> runCatching {
            val n = com.blackclaw.android.agent.TaskHistoryStore.all().size
            com.blackclaw.android.agent.TaskHistoryStore.clear()
            n
        }.getOrDefault(0)
        else -> 0
    }

    /** Erase everything. Returns the total removed. */
    fun forgetEverything(): Int =
        listOf("profile", "facts", "conversations", "tasks").sumOf { forget(it) }

    /**
     * Rough token estimate for the prompt cost, at ~4 chars per token.
     *
     * Deliberately labelled as approximate in the UI: tokenisation is model-specific,
     * and a precise-looking number the user cannot verify would be worse than an
     * honest estimate.
     */
    fun approxTokens(chars: Int): Int = (chars + 3) / 4
}
