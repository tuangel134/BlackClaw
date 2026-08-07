package com.blackclaw.android.knowledge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class DirectZimReaderTest {
    @Test fun convertsHtmlToBoundedReadableText() {
        val text = ZimText.htmlToText("<html><style>x{}</style><h1>Título</h1><p>Uno &amp; dos &#33;</p><script>bad()</script></html>")
        assertTrue(text.contains("Título"))
        assertTrue(text.contains("Uno & dos !"))
        assertFalse(text.contains("bad()"))
    }

    @Test fun normalizationSupportsAccentInsensitiveSpanishSearch() {
        assertTrue(ZimText.normalize("  Enciclopédia  ") == "enciclopedia")
    }

    @Test fun contentQueriesAreSanitizedForFtsPrefixSearch() {
        assertTrue(ZimContentIndex.matchExpression("¿Energía solar + baterías?") == "energia* AND solar* AND baterias*")
        assertTrue(ZimContentIndex.matchExpression("a y de") == "de*")
    }

    @Test fun bookConsultationRemovesQuestionNoiseAndPrioritizesTopics() {
        val tokens = ZimConsultEngine.meaningfulTokens("¿Cuál es la capital de Francia y por qué es importante?")
        assertTrue(tokens.contains("capital"))
        assertTrue(tokens.contains("francia"))
        assertFalse(tokens.contains("cual"))
        val queries = ZimConsultEngine.candidateQueries("¿Cuál es la capital?", "Francia, París", tokens)
        assertTrue(queries.take(2) == listOf("Francia", "París"))
    }

    @Test fun readsOfficialOpenZimFixturesWhenProvided() {
        val root = System.getenv("ZIM_TEST_DATA_DIR")?.let(::File)
        assumeTrue(root?.isDirectory == true)
        val fixtures = listOf("nons/small.zim", "withns/small.zim", "noTitleListingV0/small.zim")
        fixtures.forEach { relative ->
            val file = File(root, relative)
            assumeTrue(file.isFile)
            DirectZimReader(file).use { reader ->
                val hits = reader.searchTitles("Test", 5)
                assertTrue("No title hit in $relative", hits.isNotEmpty())
                val article = reader.readArticle(hits.first().path, 2_000)
                assertTrue("No content in $relative", article.text.contains("Test ZIM file"))
                val indexedArticle = (0 until reader.titleEntryCount).asSequence()
                    .mapNotNull { reader.readArticleAtTitlePosition(it, 2_000) }
                    .firstOrNull { it.text.contains("Test ZIM file") }
                assertTrue("Indexer iterator missed content in $relative", indexedArticle != null)
                val consulted = ZimConsultEngine.consult(reader, "What does the test ZIM file contain?", "Test", 3)
                assertTrue("Book retriever missed content in $relative", consulted.any { it.text.contains("Test ZIM file") })
            }
        }
    }
}
