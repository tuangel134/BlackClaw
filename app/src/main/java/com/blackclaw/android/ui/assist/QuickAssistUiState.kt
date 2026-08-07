package com.blackclaw.android.ui.assist

/** A short, user-facing snapshot of work that is currently in progress. */
data class QuickAssistProgress(
    val label: String,
    val detail: String = "",
    val step: Int? = null,
)

/** A recovery the panel can offer without making the user decipher an error string. */
enum class QuickAssistRecovery {
    MICROPHONE,
    ACCESSIBILITY,
    CONNECTION,
    RETRY,
}
