package com.blackclaw.android.memory

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Lightweight on-device semantic search — no ML model, pure Kotlin.
 *
 * Substring matching fails when the user phrases a query differently from how
 * the fact was stored ("¿qué me dijo el jefe?" vs a note titled "reunión con
 * mi superior"). A full embedding model (USE/ONNX) is heavy (~25 MB) and slow
 * on a phone, so instead we use a classic-but-effective lexical-semantic stack:
 *
 *   1. Normalize (lowercase, strip accents, punctuation).
 *   2. Tokenize + drop Spanish/English stopwords.
 *   3. Light stemming (strip common ES/EN suffixes) so "reunión"/"reuniones"
 *      and "jefe"/"jefes" match.
 *   4. Synonym expansion for a small hand-curated set (jefe↔superior↔encargado).
 *   5. Score with TF-IDF-weighted cosine similarity over the candidate set.
 *
 * This catches most "different words, same meaning" cases far better than
 * substring, at zero model cost. It's a pure function over (query, documents),
 * so it's fully unit-testable.
 */
object SemanticSearch {

    private val STOPWORDS = setOf(
        // Spanish
        "el", "la", "los", "las", "un", "una", "unos", "unas", "de", "del", "al",
        "a", "ante", "con", "en", "por", "para", "que", "qué", "y", "o", "u",
        "me", "te", "se", "le", "lo", "mi", "mis", "tu", "tus", "su", "sus",
        "es", "son", "fue", "ser", "estar", "está", "están", "hay", "como",
        "más", "pero", "si", "no", "sí", "ya", "muy", "este", "esta", "eso",
        "dijo", "dije", "dice", "decir", "sobre", "cuando", "donde", "cual",
        // English
        "the", "a", "an", "of", "to", "in", "on", "for", "and", "or", "is",
        "are", "was", "be", "my", "your", "his", "her", "its", "what", "when",
        "where", "which", "that", "this", "with", "about", "said", "say",
    )

    /** Small curated synonym groups (ES-centric since the UI is Spanish). */
    private val SYNONYMS: List<Set<String>> = listOf(
        setOf("jefe", "jefa", "superior", "encargado", "manager", "patron", "patrón"),
        setOf("reunion", "reunión", "junta", "meeting", "cita", "encuentro"),
        setOf("comprar", "compra", "adquirir", "pedir", "encargar"),
        setOf("dinero", "plata", "pago", "pagar", "cobro", "factura", "gasto"),
        setOf("doctor", "medico", "médico", "cita medica", "consulta"),
        setOf("casa", "hogar", "domicilio", "depa", "apartamento"),
        setOf("trabajo", "oficina", "chamba", "curro", "empleo"),
        setOf("comida", "comer", "almuerzo", "cena", "desayuno"),
        setOf("amigo", "amiga", "colega", "compa", "compañero"),
        setOf("llamar", "llamada", "telefonear", "marcar"),
        setOf("mensaje", "mensajes", "texto", "whatsapp", "chat"),
        setOf("recordar", "recordatorio", "acordar", "memo", "nota"),
        setOf("auto", "coche", "carro", "vehiculo", "vehículo"),
        setOf("medicina", "medicamento", "pastilla", "pastillas", "remedio", "dosis"),
        setOf("ejercicio", "gym", "gimnasio", "entrenar", "deporte"),
    )

    /** Expand a token into its synonym set (including itself). */
    private fun expand(token: String): Set<String> {
        val group = SYNONYMS.firstOrNull { token in it }
        return group ?: setOf(token)
    }

    fun normalize(text: String): String {
        val lowered = text.lowercase()
        val sb = StringBuilder(lowered.length)
        for (c in lowered) {
            sb.append(
                when (c) {
                    'á' -> 'a'; 'é' -> 'e'; 'í' -> 'i'; 'ó' -> 'o'; 'ú' -> 'u'; 'ü' -> 'u'
                    'ñ' -> 'n'
                    else -> if (c.isLetterOrDigit() || c == ' ') c else ' '
                }
            )
        }
        return sb.toString().trim().replace(Regex(" +"), " ")
    }

    /** Strip a few common ES/EN inflectional suffixes. Cheap, not linguistically perfect. */
    fun stem(word: String): String {
        if (word.length <= 4) return word
        val suffixes = listOf("ciones", "cion", "mente", "ando", "iendo", "es", "as", "os", "s", "ar", "er", "ir")
        for (sfx in suffixes) {
            if (word.length - sfx.length >= 3 && word.endsWith(sfx)) {
                return word.dropLast(sfx.length)
            }
        }
        return word
    }

    /** Tokenize into stemmed, stopword-filtered, synonym-expanded terms. */
    fun terms(text: String): List<String> {
        return normalize(text).split(" ")
            .filter { it.length > 1 && it !in STOPWORDS }
            .flatMap { expand(it) }
            .map { stem(it) }
    }

    /**
     * Rank [documents] by semantic relevance to [query]. Returns indices+scores
     * sorted descending, filtered to those above [minScore].
     *
     * TF-IDF cosine: rare shared terms weigh more than common ones.
     */
    fun rank(query: String, documents: List<String>, minScore: Double = 0.05): List<Pair<Int, Double>> {
        if (documents.isEmpty()) return emptyList()
        val docTerms = documents.map { terms(it) }
        val queryTerms = terms(query)
        if (queryTerms.isEmpty()) return emptyList()

        // IDF over the candidate set.
        val n = documents.size.toDouble()
        val df = HashMap<String, Int>()
        docTerms.forEach { dt -> dt.toSet().forEach { df[it] = (df[it] ?: 0) + 1 } }
        fun idf(term: String): Double = ln((n + 1) / ((df[term] ?: 0) + 1).toDouble()) + 1.0

        fun vector(tokens: List<String>): Map<String, Double> {
            val tf = HashMap<String, Double>()
            tokens.forEach { tf[it] = (tf[it] ?: 0.0) + 1.0 }
            return tf.mapValues { (term, freq) -> freq * idf(term) }
        }

        val qVec = vector(queryTerms)
        val qNorm = sqrt(qVec.values.sumOf { it * it })
        if (qNorm == 0.0) return emptyList()

        val scored = docTerms.mapIndexed { idx, dt ->
            val dVec = vector(dt)
            val dNorm = sqrt(dVec.values.sumOf { it * it })
            if (dNorm == 0.0) return@mapIndexed idx to 0.0
            val dot = qVec.entries.sumOf { (term, w) -> w * (dVec[term] ?: 0.0) }
            idx to (dot / (qNorm * dNorm))
        }
        return scored.filter { it.second >= minScore }.sortedByDescending { it.second }
    }

    /** Convenience: return the top-[k] documents (text) most relevant to [query]. */
    fun search(query: String, documents: List<String>, k: Int = 5, minScore: Double = 0.05): List<String> =
        rank(query, documents, minScore).take(k).map { documents[it.first] }
}
