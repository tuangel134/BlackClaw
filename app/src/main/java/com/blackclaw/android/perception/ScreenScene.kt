package com.blackclaw.android.perception

import java.text.Normalizer

/**
 * A small, deterministic screen description assembled from two complementary
 * sensors: accessibility exposes intent and controls; OCR exposes pixels that
 * accessibility misses.  It is deliberately not an image captioner — it only
 * states evidence actually present on the screen.
 */
data class ScreenScene(
    val foregroundApp: String?,
    val visibleText: List<String>,
    val actions: List<String>,
) {
    fun describeForQuickAssist(): String = buildString {
        foregroundApp?.takeIf { it.isNotBlank() }?.let { append("Estás en: $it.\n") }
        if (visibleText.isNotEmpty()) {
            append("Veo:\n")
            visibleText.take(12).forEach { append("· ").append(it.take(120)).append('\n') }
        }
        if (actions.isNotEmpty()) {
            append("Puedes interactuar con: ")
            append(actions.take(4).joinToString(", ")).append('.')
        }
    }.trim()

    companion object {
        private val NODE_LINE = Regex("""\[n\d+]\s+\"([^\"]+)\"(.*)""")

        fun compose(
            foregroundApp: String?,
            accessibilityTree: String?,
            ocrLines: List<String>,
        ): ScreenScene {
            val visible = mutableListOf<String>()
            val actions = mutableListOf<String>()
            accessibilityTree.orEmpty().lineSequence().forEach { line ->
                val match = NODE_LINE.find(line) ?: return@forEach
                val text = match.groupValues[1].cleanVisibleText()
                if (text.isEmpty()) return@forEach
                addDistinct(visible, text)
                val flags = match.groupValues[2]
                if (flags.contains(" tap") || flags.contains(" edit") || flags.contains(" on") || flags.contains(" off")) {
                    addDistinct(actions, text)
                }
            }

            ocrLines.take(60).forEach { text ->
                addDistinct(visible, text.cleanVisibleText())
            }
            return ScreenScene(
                foregroundApp = foregroundApp?.removePrefix("Foreground: ")?.substringBefore(" (")?.trim(),
                visibleText = visible,
                actions = actions,
            )
        }

        private fun addDistinct(target: MutableList<String>, candidate: String) {
            if (candidate.isBlank() || target.size >= 60) return
            val normalized = candidate.normalizedForComparison()
            if (normalized.length < 2) return
            if (target.none { existing ->
                    val seen = existing.normalizedForComparison()
                    seen == normalized || (normalized.length >= 5 && (seen.contains(normalized) || normalized.contains(seen)))
                }
            ) {
                target += candidate
            }
        }

        private fun String.cleanVisibleText(): String =
            replace(Regex("\\s+"), " ").trim()

        private fun String.normalizedForComparison(): String =
            Normalizer.normalize(this, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
    }
}
