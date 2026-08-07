package com.blackclaw.android.knowledge

import kotlin.math.max

/**
 * Retrieval layer for treating a ZIM as a book: use its built-in title listing
 * to select a few likely articles, then rank small passages from those articles.
 * It never scans the complete archive and never places a complete article in context.
 */
object ZimConsultEngine {
    data class Excerpt(
        val title: String,
        val path: String,
        val text: String,
        val relevance: Int,
    )

    private data class Candidate(val hit: DirectZimReader.SearchHit, val score: Int)

    private val stopWords = setOf(
        "a", "al", "algo", "ante", "como", "con", "cual", "cuando", "de", "del", "donde", "el", "ella",
        "en", "entre", "era", "es", "esta", "este", "fue", "ha", "hay", "la", "las", "lo", "los", "mas",
        "me", "para", "pero", "por", "porque", "que", "quien", "se", "sin", "sobre", "son", "su", "sus",
        "un", "una", "unos", "unas", "y", "ya",
        "about", "and", "are", "as", "at", "be", "by", "for", "from", "how", "in", "is", "it", "of",
        "on", "or", "that", "the", "to", "was", "what", "when", "where", "which", "who", "why", "with",
    )

    fun consult(
        reader: DirectZimReader,
        question: String,
        topics: String = "",
        maxArticles: Int = 5,
        maxOutputChars: Int = 7_000,
    ): List<Excerpt> {
        val tokens = meaningfulTokens("$question $topics")
        require(tokens.isNotEmpty()) { "La pregunta no contiene términos consultables" }
        val queries = candidateQueries(question, topics, tokens)
        val candidates = LinkedHashMap<String, Candidate>()
        queries.forEachIndexed { queryOrder, query ->
            reader.searchTitles(query, 4).forEach { hit ->
                val title = ZimText.normalize(hit.title)
                val overlap = tokens.count { title.contains(it) }
                val exactBonus = if (title == ZimText.normalize(query)) 30 else 0
                val prefixBonus = if (title.startsWith(ZimText.normalize(query))) 10 else 0
                val score = exactBonus + prefixBonus + overlap * 8 + max(0, 12 - queryOrder)
                val old = candidates[hit.path]
                if (old == null || score > old.score) candidates[hit.path] = Candidate(hit, score)
            }
        }

        val selected = candidates.values.sortedByDescending { it.score }.take(maxArticles.coerceIn(1, 8))
        val excerpts = ArrayList<Excerpt>()
        selected.forEach { candidate ->
            val article = runCatching { reader.readArticle(candidate.hit.path, 60_000) }.getOrNull() ?: return@forEach
            val passages = passages(article.text)
                .mapIndexed { index, text -> Triple(text, passageScore(text, tokens), index) }
                .sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenBy { it.third })
                .take(2)
            passages.forEach { (text, score, _) ->
                excerpts += Excerpt(article.title, article.path, text.take(1_200), candidate.score + score)
            }
        }

        val ranked = excerpts.sortedByDescending { it.relevance }
        val bounded = ArrayList<Excerpt>()
        var used = 0
        for (excerpt in ranked) {
            if (used >= maxOutputChars.coerceIn(1_000, 12_000)) break
            val remaining = maxOutputChars.coerceIn(1_000, 12_000) - used
            val text = excerpt.text.take(remaining)
            if (text.isNotBlank()) {
                bounded += excerpt.copy(text = text)
                used += text.length
            }
        }
        return bounded
    }

    internal fun meaningfulTokens(value: String): List<String> =
        Regex("[\\p{L}\\p{N}]{2,}").findAll(ZimText.normalize(value))
            .map { it.value }.filterNot { it in stopWords }.distinct().take(20).toList()

    internal fun candidateQueries(question: String, topics: String, tokens: List<String>): List<String> {
        val queries = LinkedHashSet<String>()
        topics.split(',', ';', '|').map(String::trim).filter { it.length >= 2 }.forEach(queries::add)
        Regex("[\"“”']([^\"“”']{2,80})[\"“”']").findAll(question).forEach { queries += it.groupValues[1] }
        for (size in minOf(3, tokens.size) downTo 2) {
            tokens.windowed(size).forEach { queries += it.joinToString(" ") }
        }
        tokens.asReversed().forEach(queries::add)
        return queries.take(18)
    }

    private fun passages(text: String): List<String> {
        val result = ArrayList<String>()
        text.split(Regex("\\n+")).map(String::trim).filter { it.length >= 10 }.forEach { paragraph ->
            if (paragraph.length <= 1_200) result += paragraph
            else paragraph.chunked(900).map(String::trim).filter { it.length >= 10 }.forEach(result::add)
        }
        return result.take(2_000)
    }

    private fun passageScore(passage: String, tokens: List<String>): Int {
        val normalized = ZimText.normalize(passage)
        var score = tokens.sumOf { token -> if (normalized.contains(token)) 5 else 0 }
        tokens.windowed(2).forEach { pair -> if (normalized.contains(pair.joinToString(" "))) score += 8 }
        if (tokens.isNotEmpty() && tokens.all { normalized.contains(it) }) score += 20
        return score
    }
}
