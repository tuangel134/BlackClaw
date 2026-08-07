package com.blackclaw.android.ui.chat

/**
 * Turns raw model output into a block structure the chat can render.
 *
 * ## Why this exists
 *
 * Assistant replies were painted with a single plain `Text`, so every `**bold**`,
 * heading, list and fenced code block reached the user as literal punctuation. The
 * model was formatting its answers all along; the UI was just showing the source.
 *
 * ## Why it is pure
 *
 * No Android and no Compose types here, so the parsing rules are unit-testable on the
 * JVM. The Composable that consumes this only maps blocks onto layout — it makes no
 * decisions about what the text means.
 *
 * ## Streaming is the hard constraint
 *
 * This parser runs on **partial** text: it is called again on every token that
 * arrives. That drives two deliberate choices, both documented at their
 * implementation:
 *
 * - An unterminated fence still produces a [Block.Code]. Treating it as a paragraph
 *   until the closing fence lands would make the text reflow and restyle the instant
 *   it does, which reads as a glitch.
 * - An unterminated emphasis marker opens anyway. `**Hola` renders bold immediately
 *   and stays bold when the closing marker arrives, instead of flickering.
 *
 * ## What it deliberately does not do
 *
 * Backslash escapes are not honoured. Half-working escapes are worse than none: the
 * flanking rules below already keep the cases that actually show up in model output
 * (`2 * 3`, `snake_case`) out of the emphasis path, and pretending to support `\*`
 * while getting nested cases wrong would be harder to reason about than not
 * supporting it. Tables, footnotes and reference links are also out of scope.
 */
object ChatMarkdown {

    /**
     * A run of text sharing one set of inline styles.
     *
     * Flat rather than a tree: model output nests emphasis very rarely, and flags on a
     * run are enough to build an `AnnotatedString` in a single pass.
     */
    data class Span(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val strike: Boolean = false,
    )

    sealed interface Block {
        data class Paragraph(val spans: List<Span>) : Block
        data class Heading(val level: Int, val spans: List<Span>) : Block
        data class Bullet(val depth: Int, val spans: List<Span>) : Block
        data class Numbered(val number: Int, val depth: Int, val spans: List<Span>) : Block
        data class Quote(val spans: List<Span>) : Block

        /**
         * @param closed false while the closing fence has not arrived yet. The renderer
         *   uses it to avoid drawing a "finished" affordance (the copy button) on a
         *   block that is still being written.
         */
        data class Code(val language: String, val code: String, val closed: Boolean) : Block
        data object Rule : Block

        /**
         * A pipe table.
         *
         * @param header the first row, which the separator line identifies as headings.
         * @param alignments one entry per header cell, taken from the separator line.
         * @param rows every row after the separator, each padded or truncated to the
         *   header width so a ragged table lays out as a grid rather than a staircase.
         */
        data class Table(
            val header: List<Cell>,
            val alignments: List<Align>,
            val rows: List<List<Cell>>,
        ) : Block
    }

    /** One table cell, already split into styled runs. */
    data class Cell(val spans: List<Span>)

    /** Column alignment, taken from the colons in the separator line. */
    enum class Align { START, CENTER, END }

    private const val FENCE = "```"

    /** Deepest indent level tracked; beyond this, extra nesting reads as noise. */
    private const val MAX_DEPTH = 3

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^([ \\t]*)[-*+][ \\t]+(.*)$")
    private val NUMBERED = Regex("^([ \\t]*)(\\d{1,3})[.)][ \\t]+(.*)$")
    private val QUOTE = Regex("^[ \\t]{0,3}>[ \\t]?(.*)$")

    /** `---`, `***` or `___`, optionally spaced. Checked before [BULLET], which `- - -` also matches. */
    private val RULE = Regex("^[ \\t]{0,3}([-*_])([ \\t]*\\1){2,}[ \\t]*$")

    /**
     * A candidate table line: starts with a pipe.
     *
     * Requiring the leading pipe is stricter than the markdown spec allows, and
     * deliberately so — without it any sentence containing a pipe would start a table.
     * Models emit the leading pipe essentially always.
     */
    private val TABLE_LINE = Regex("^[ \\t]{0,3}\\|.*$")

    /** A separator cell: dashes with optional alignment colons. */
    private val SEPARATOR_CELL = Regex("^:?-{1,}:?$")

    /** Splits on pipes that are not backslash-escaped. */
    private val CELL_SPLIT = Regex("(?<!\\\\)\\|")

    /**
     * Rows kept from one table.
     *
     * Generous, because truncating a table silently hides data. This only exists so a
     * pathological response cannot build an unbounded structure.
     */
    private const val MAX_TABLE_ROWS = 100

    // ── Block level ───────────────────────────────────────────────────────────

    fun parse(raw: String): List<Block> {
        if (raw.isBlank()) return emptyList()

        val out = mutableListOf<Block>()
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')

        // Consecutive lines of the same kind merge into one block, so a wrapped
        // paragraph or a multi-line quote is laid out as a unit.
        val paragraph = mutableListOf<String>()
        val quote = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                // Joined with newlines, not spaces. Markdown would collapse these, but
                // models use single newlines to mean a line break (steps, addresses,
                // key: value lists), and collapsing them runs those together.
                out += Block.Paragraph(inlineSpans(paragraph.joinToString("\n")))
                paragraph.clear()
            }
        }

        fun flushQuote() {
            if (quote.isNotEmpty()) {
                out += Block.Quote(inlineSpans(quote.joinToString("\n")))
                quote.clear()
            }
        }

        fun flushAll() {
            flushParagraph()
            flushQuote()
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // Fences first: a `#` or `-` inside code is code, not a heading or a bullet.
            if (trimmed.startsWith(FENCE)) {
                flushAll()
                val language = trimmed.removePrefix(FENCE).trim()
                    .takeWhile { !it.isWhitespace() }
                val body = mutableListOf<String>()
                var closed = false
                i++
                while (i < lines.size) {
                    if (lines[i].trim().startsWith(FENCE)) {
                        closed = true
                        i++
                        break
                    }
                    body += lines[i]
                    i++
                }
                out += Block.Code(language, body.joinToString("\n").trimEnd(), closed)
                continue
            }

            if (trimmed.isEmpty()) {
                flushAll()
                i++
                continue
            }

            // A table is only a table once the separator line has arrived. Checked before
            // RULE so a separator like `|---|---|` is never mistaken for a horizontal
            // rule, and before the paragraph fallback so the header row is not consumed
            // as prose.
            //
            // STREAMING NOTE: between the header line arriving and its separator arriving,
            // the header renders as a paragraph and then becomes a table. That single
            // reflow is accepted on purpose. The alternative — treating any pipe-bearing
            // line as a table row — would turn ordinary sentences containing a pipe into
            // one-cell tables, which is a permanent wrong answer instead of a momentary
            // one.
            if (TABLE_LINE.matches(line) && i + 1 < lines.size && isSeparatorRow(lines[i + 1])) {
                flushAll()
                val header = splitCells(line).map { Cell(inlineSpans(it)) }
                val alignments = splitCells(lines[i + 1]).map(::alignmentOf)
                i += 2
                val rows = mutableListOf<List<Cell>>()
                while (i < lines.size && TABLE_LINE.matches(lines[i]) && !isSeparatorRow(lines[i])) {
                    // Past the cap the lines are still consumed, only not collected.
                    // Stopping the scan instead would leave the remaining rows to be
                    // parsed as prose, so an over-long table would be followed by
                    // hundreds of paragraphs full of pipes — worse than dropping them.
                    if (rows.size < MAX_TABLE_ROWS) {
                        rows += fitToWidth(splitCells(lines[i]), header.size)
                    }
                    i++
                }
                out += Block.Table(
                    header = header,
                    alignments = fitAlignments(alignments, header.size),
                    rows = rows,
                )
                continue
            }

            if (RULE.matchEntire(line) != null) {
                flushAll()
                out += Block.Rule
                i++
                continue
            }

            val heading = HEADING.matchEntire(trimmed)
            if (heading != null) {
                flushAll()
                out += Block.Heading(
                    level = heading.groupValues[1].length,
                    spans = inlineSpans(heading.groupValues[2].trim()),
                )
                i++
                continue
            }

            val quoted = QUOTE.matchEntire(line)
            if (quoted != null) {
                flushParagraph()
                quote += quoted.groupValues[1]
                i++
                continue
            }
            // Any other line ends an open quote.
            flushQuote()

            val bullet = BULLET.matchEntire(line)
            if (bullet != null) {
                flushParagraph()
                out += Block.Bullet(
                    depth = depthOf(bullet.groupValues[1]),
                    spans = inlineSpans(bullet.groupValues[2].trim()),
                )
                i++
                continue
            }

            val numbered = NUMBERED.matchEntire(line)
            if (numbered != null) {
                flushParagraph()
                out += Block.Numbered(
                    number = numbered.groupValues[2].toIntOrNull() ?: 1,
                    depth = depthOf(numbered.groupValues[1]),
                    spans = inlineSpans(numbered.groupValues[3].trim()),
                )
                i++
                continue
            }

            paragraph += trimmed
            i++
        }

        flushAll()
        return out
    }

    // ── Tables ────────────────────────────────────────────────────────────────

    /**
     * True when every cell in [line] is dashes with optional alignment colons.
     *
     * This line is what turns a run of pipes into a table, so the check is strict: one
     * non-separator cell and the whole thing stays prose.
     */
    internal fun isSeparatorRow(line: String): Boolean {
        if (!line.contains('-')) return false
        val cells = splitCells(line)
        return cells.isNotEmpty() && cells.all { SEPARATOR_CELL.matches(it) }
    }

    /**
     * Splits a table row into cell contents.
     *
     * The outer pipes are delimiters, not empty cells, so they are dropped. Escaped pipes
     * are unescaped afterwards so a cell can legitimately contain one.
     */
    internal fun splitCells(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
        return s.split(CELL_SPLIT).map { it.replace("\\|", "|").trim() }
    }

    private fun alignmentOf(separatorCell: String): Align {
        val startsWith = separatorCell.startsWith(":")
        val endsWith = separatorCell.endsWith(":")
        return when {
            startsWith && endsWith -> Align.CENTER
            endsWith -> Align.END
            else -> Align.START
        }
    }

    /**
     * Forces a row to the header's column count.
     *
     * Ragged rows are common in generated tables. Padding keeps the grid aligned and
     * truncating keeps an over-long row from widening every other one; dropping the row
     * would lose data the user can see in the raw text.
     */
    private fun fitToWidth(cells: List<String>, width: Int): List<Cell> {
        val fitted = ArrayList<Cell>(width)
        for (index in 0 until width) {
            fitted += Cell(inlineSpans(cells.getOrNull(index).orEmpty()))
        }
        return fitted
    }

    private fun fitAlignments(alignments: List<Align>, width: Int): List<Align> =
        List(width) { alignments.getOrNull(it) ?: Align.START }

    /** Two spaces (or one tab) per level, clamped so pathological indent cannot push text off-screen. */
    private fun depthOf(indent: String): Int {
        val columns = indent.sumOf { if (it == '\t') 4L else 1L }.toInt()
        return (columns / 2).coerceIn(0, MAX_DEPTH)
    }

    // ── Inline level ──────────────────────────────────────────────────────────

    /**
     * Splits one block's text into styled runs.
     *
     * Code spans are extracted first and their contents are never scanned for
     * emphasis, because `` `a_b_c` `` is an identifier, not italics.
     */
    fun inlineSpans(text: String): List<Span> {
        if (text.isEmpty()) return emptyList()

        val out = mutableListOf<Span>()
        val plain = StringBuilder()

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                out += emphasisSpans(plain.toString())
                plain.clear()
            }
        }

        var i = 0
        while (i < text.length) {
            if (text[i] == '`') {
                val open = runLength(text, i, '`')
                val close = findRun(text, i + open, '`', open)
                if (close >= 0) {
                    flushPlain()
                    val body = text.substring(i + open, close)
                    // Trimmed because ``` `x` ``` is written with padding to allow a
                    // literal backtick at the edge; the padding is syntax, not content.
                    out += Span(body.trim(), code = true)
                    i = close + open
                    continue
                }
                // No matching run: a lone backtick is literal. Opening a code span that
                // swallows the rest of the message would be far more destructive.
            }
            plain.append(text[i])
            i++
        }

        flushPlain()
        return out.filter { it.text.isNotEmpty() }
    }

    private fun runLength(text: String, start: Int, c: Char): Int {
        var n = 0
        while (start + n < text.length && text[start + n] == c) n++
        return n
    }

    /** Index of the next run of exactly [length] [c] characters, or -1. */
    private fun findRun(text: String, from: Int, c: Char, length: Int): Int {
        var i = from
        while (i < text.length) {
            if (text[i] == c) {
                val n = runLength(text, i, c)
                if (n == length) return i
                i += n
            } else {
                i++
            }
        }
        return -1
    }

    private fun emphasisSpans(text: String): List<Span> {
        val out = mutableListOf<Span>()
        val buf = StringBuilder()
        var bold = false
        var italic = false
        var strike = false

        fun flush() {
            if (buf.isNotEmpty()) {
                out += Span(buf.toString(), bold = bold, italic = italic, strike = strike)
                buf.clear()
            }
        }

        var i = 0
        while (i < text.length) {
            if (text.startsWith("**", i) || text.startsWith("__", i)) {
                if (canToggle(text, i, 2, bold)) {
                    flush(); bold = !bold; i += 2; continue
                }
            }
            if (text.startsWith("~~", i)) {
                if (canToggle(text, i, 2, strike)) {
                    flush(); strike = !strike; i += 2; continue
                }
            }
            val c = text[i]
            if ((c == '*' || c == '_') &&
                !partOfRun(text, i, c) &&
                !intraword(text, i) &&
                canToggle(text, i, 1, italic)
            ) {
                flush(); italic = !italic; i++; continue
            }
            buf.append(c)
            i++
        }

        flush()
        return out
    }

    /**
     * Flanking rule: a marker may only open when text follows it and only close when
     * text precedes it.
     *
     * This is what keeps `2 * 3` and a trailing `**` out of the emphasis path — the
     * marker is surrounded by space, so it can neither open nor close and stays
     * literal. Without it, one stray asterisk italicises the rest of the message.
     */
    private fun canToggle(text: String, i: Int, markerLength: Int, currentlyOpen: Boolean): Boolean =
        if (currentlyOpen) {
            i > 0 && !text[i - 1].isWhitespace()
        } else {
            i + markerLength < text.length && !text[i + markerLength].isWhitespace()
        }

    /**
     * True when a single-char marker is part of a longer run.
     *
     * The two-char markers are matched first, so a leftover run here is one that
     * already failed [canToggle]. Letting its individual characters toggle italics
     * would make `a ** b` silently lose the asterisks.
     */
    private fun partOfRun(text: String, i: Int, c: Char): Boolean =
        text.getOrNull(i - 1) == c || text.getOrNull(i + 1) == c

    /**
     * True when a marker sits between two alphanumerics.
     *
     * Protects `snake_case_name` and `2*3`, which appear in model output far more often
     * than intraword emphasis does.
     */
    private fun intraword(text: String, i: Int): Boolean {
        val before = text.getOrNull(i - 1) ?: return false
        val after = text.getOrNull(i + 1) ?: return false
        return before.isLetterOrDigit() && after.isLetterOrDigit()
    }
}
