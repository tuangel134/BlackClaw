package com.blackclaw.android.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure lexical-semantic search.
 */
class SemanticSearchTest {

    @Test
    fun normalizeStripsAccentsAndPunctuation() {
        assertEquals("reunion con el jefe", SemanticSearch.normalize("¡Reunión con el jefe!"))
    }

    @Test
    fun synonymMatchJefeSuperior() {
        val docs = listOf(
            "Comprar leche en el supermercado",
            "Reunión con mi superior el lunes a las 9",
            "Llamar a mamá el fin de semana",
        )
        val results = SemanticSearch.search("qué me dijo el jefe", docs)
        assertTrue("Should find the 'superior' note via synonym", results.isNotEmpty())
        assertEquals("Reunión con mi superior el lunes a las 9", results.first())
    }

    @Test
    fun synonymMatchMedicoDoctor() {
        val docs = listOf(
            "Cita con el médico el martes",
            "Pagar la factura de la luz",
        )
        val results = SemanticSearch.search("cuándo es lo del doctor", docs)
        assertEquals("Cita con el médico el martes", results.first())
    }

    @Test
    fun stemmingMatchesPlurals() {
        val docs = listOf("Tengo varias reuniones esta semana", "Comprar pan")
        val results = SemanticSearch.search("reunión", docs)
        assertTrue(results.isNotEmpty())
        assertEquals("Tengo varias reuniones esta semana", results.first())
    }

    @Test
    fun unrelatedQueryReturnsNothing() {
        val docs = listOf("Comprar leche", "Llamar a Juan")
        val results = SemanticSearch.rank("programación cuántica de servidores", docs, minScore = 0.1)
        assertTrue(results.isEmpty())
    }

    @Test
    fun rankOrdersByRelevance() {
        val docs = listOf(
            "Pan y leche",
            "Reunión importante con el jefe sobre el proyecto",
            "Reunión",
        )
        val ranked = SemanticSearch.rank("reunión con mi superior", docs)
        // Both reunion docs should rank above the groceries one
        assertTrue(ranked.isNotEmpty())
        assertTrue(ranked.first().first != 0)
    }

    @Test
    fun emptyDocumentsReturnsEmpty() {
        assertTrue(SemanticSearch.search("cualquier cosa", emptyList()).isEmpty())
    }

    @Test
    fun stopwordsAreIgnored() {
        // Query is mostly stopwords; only "leche" is meaningful
        val docs = listOf("Comprar leche", "Lavar el coche")
        val results = SemanticSearch.search("el de la que es leche", docs)
        assertEquals("Comprar leche", results.first())
    }
}
