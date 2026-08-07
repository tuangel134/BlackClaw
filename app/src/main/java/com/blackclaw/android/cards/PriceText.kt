package com.blackclaw.android.cards

/**
 * Finds a price inside free text, such as a search-result snippet.
 *
 * ## Why quote and never reformat
 *
 * [find] returns the matched substring exactly as the source wrote it. Thousands and
 * decimal separators are swapped between locales, so "tidying up" `1.299,00 €` without
 * knowing which convention it follows is how a price silently becomes `1.30 €`. The card
 * is quoting a merchant, so it quotes verbatim.
 *
 * ## Why a currency marker is required
 *
 * A number on its own is not a price — snippets are full of years, model numbers, review
 * counts and percentages. Requiring a symbol or a currency word next to the number is
 * what keeps "iPhone 15 Pro, 256 GB, 4.8 de 5 en 2026" from being read as a price.
 *
 * Inventing a price the user might act on is worse than showing no price at all, so
 * every rule here errs toward finding nothing.
 */
object PriceText {

    /**
     * Grouped or plain decimal, at most two fraction digits.
     *
     * Two digits is the cap on purpose: it stops the pattern from swallowing a version
     * string or a date fragment that happens to sit beside a currency word.
     */
    private const val NUMBER = """\d{1,3}(?:[.,]\d{3})*(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?"""

    private const val SYMBOLS = """US\$|MX\$|R\$|[$€£¥]"""
    private const val CODES = """USD|EUR|MXN|GBP|ARS|COP|CLP|PEN|BRL|JPY"""
    private const val WORDS = """pesos?|euros?|d[oó]lares?|d[oó]lar|libras?|reales?"""

    /** `$1,299.00`, `€1.299`, `US$ 49.99`, `MXN 1,299`. */
    private val MARKER_FIRST = Regex("""(?:$SYMBOLS|\b(?:$CODES)\b)\s?(?:$NUMBER)""", RegexOption.IGNORE_CASE)

    /** `1.299,00 €`, `49.99 USD`, `1299 pesos`. */
    private val MARKER_LAST = Regex("""(?:$NUMBER)\s?(?:$SYMBOLS|\b(?:$CODES|$WORDS)\b)""", RegexOption.IGNORE_CASE)

    /**
     * The first price in [text], or null.
     *
     * Marker-first is tried before marker-last because `$50 - 100 euros` should report
     * the price that is unambiguously delimited, and because a leading symbol is the far
     * more common form in the sources this reads.
     */
    fun find(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.take(MAX_SCAN)
        val first = MARKER_FIRST.find(trimmed)
        val last = MARKER_LAST.find(trimmed)
        val chosen = when {
            first == null -> last
            last == null -> first
            // Whichever appears earlier wins, so the card shows the price the snippet
            // leads with rather than one buried in a trailing comparison.
            first.range.first <= last.range.first -> first
            else -> last
        } ?: return null
        return chosen.value.trim().takeIf { it.isNotEmpty() && it.any(Char::isDigit) }
    }

    fun has(text: String?): Boolean = find(text) != null

    /**
     * Cap on how much of a snippet is scanned.
     *
     * Snippets arrive from HTML scraping and can be far longer than they look; the price
     * a result is about is at the front, and scanning kilobytes of tail text only finds
     * unrelated numbers.
     */
    private const val MAX_SCAN = 400
}
