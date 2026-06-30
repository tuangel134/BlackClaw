package com.blackclaw.android.perception

/**
 * Extracts the total amount and a likely merchant name from OCR'd receipt text.
 * Pure text heuristics (no ML) — works offline. Used by the receipt scanner to
 * auto-log an expense.
 */
object ReceiptParser {

    data class Receipt(val amount: Double?, val merchant: String?, val rawTotalLine: String?)

    // Keywords that mark the grand total (ES + EN). Ordered by priority.
    private val TOTAL_KEYWORDS = listOf(
        "total a pagar", "importe total", "total importe", "gran total",
        "total", "a pagar", "importe", "amount due", "grand total", "balance due", "total due",
    )
    // Lines to ignore as totals (subtotals, taxes, change).
    private val NEGATIVE_KEYWORDS = listOf(
        "subtotal", "sub total", "iva", "tax", "impuesto", "propina", "tip",
        "cambio", "change", "efectivo", "cash", "descuento", "discount",
    )

    // Matches money amounts like 1.234,56 / 1,234.56 / 123.45 / 123,45 / $99
    private val MONEY = Regex("""(?:[$€£]\s*)?(\d{1,3}(?:[.,]\d{3})*(?:[.,]\d{2})|\d+[.,]\d{2}|\d+)""")

    fun parse(ocrText: String): Receipt {
        val lines = ocrText.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return Receipt(null, null, null)

        // 1) Merchant: usually one of the first non-numeric lines.
        val merchant = lines.firstOrNull {
            it.length in 3..40 && it.any { c -> c.isLetter() } &&
                MONEY.find(it)?.value?.length ?: 0 < it.length / 2
        }?.takeIf { !looksLikeAddress(it) }

        // 2) Total: prefer a line with a TOTAL keyword that isn't a negative one.
        var bestAmount: Double? = null
        var bestLine: String? = null
        for (line in lines) {
            val low = line.lowercase()
            if (NEGATIVE_KEYWORDS.any { low.contains(it) }) continue
            if (TOTAL_KEYWORDS.any { low.contains(it) }) {
                val amt = lastAmountIn(line)
                if (amt != null) { bestAmount = amt; bestLine = line; if (low.contains("total")) break }
            }
        }

        // 3) Fallback: the largest money amount on the receipt.
        if (bestAmount == null) {
            val all = lines.flatMap { line ->
                if (NEGATIVE_KEYWORDS.any { line.lowercase().contains(it) }) emptyList()
                else MONEY.findAll(line).mapNotNull { normalize(it.value) }.toList()
            }
            bestAmount = all.maxOrNull()
        }

        return Receipt(bestAmount, merchant, bestLine)
    }

    private fun lastAmountIn(line: String): Double? =
        MONEY.findAll(line).mapNotNull { normalize(it.value) }.lastOrNull()

    /** Normalize a money token to a Double, handling , vs . decimal separators. */
    fun normalize(token: String): Double? {
        var s = token.replace(Regex("[$€£\\s]"), "")
        if (s.isBlank()) return null
        val lastComma = s.lastIndexOf(',')
        val lastDot = s.lastIndexOf('.')
        s = when {
            // Both present → the LAST one is the decimal sep, the other is thousands.
            lastComma >= 0 && lastDot >= 0 ->
                if (lastComma > lastDot) s.replace(".", "").replace(',', '.')
                else s.replace(",", "")
            // Only comma → treat as decimal if 2 digits after, else thousands.
            lastComma >= 0 ->
                if (s.length - lastComma - 1 == 2) s.replace(',', '.') else s.replace(",", "")
            else -> s
        }
        return s.toDoubleOrNull()
    }

    private fun looksLikeAddress(line: String): Boolean {
        val low = line.lowercase()
        return low.contains("calle") || low.contains("av.") || low.contains("avenida") ||
            low.contains("street") || low.contains("st.") || Regex("""\d{4,}""").containsMatchIn(line)
    }
}
