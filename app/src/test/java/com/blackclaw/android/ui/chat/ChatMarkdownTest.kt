package com.blackclaw.android.ui.chat

import com.blackclaw.android.ui.chat.ChatMarkdown.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract of the chat markdown parser.
 *
 * Two groups matter most and are called out below: the STREAMING cases, because the
 * parser re-runs on partial text for every token that arrives, and the DO-NOT-MANGLE
 * cases, because silently dropping punctuation changes what the model said.
 */
class ChatMarkdownTest {

    private fun text(spans: List<ChatMarkdown.Span>) = spans.joinToString("") { it.text }
    private fun firstBlock(raw: String) = ChatMarkdown.parse(raw).first()

    // ── Plain text ────────────────────────────────────────────────────────────

    @Test fun `plain text becomes a single paragraph`() {
        val blocks = ChatMarkdown.parse("Hola, soy BlackClaw.")
        assertEquals(1, blocks.size)
        assertEquals("Hola, soy BlackClaw.", text((blocks[0] as Block.Paragraph).spans))
    }

    @Test fun `blank input yields no blocks`() {
        assertEquals(emptyList<Block>(), ChatMarkdown.parse(""))
        assertEquals(emptyList<Block>(), ChatMarkdown.parse("   \n  \n"))
    }

    @Test fun `a blank line separates paragraphs`() {
        val blocks = ChatMarkdown.parse("Primero.\n\nSegundo.")
        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it is Block.Paragraph })
    }

    @Test fun `single newlines inside a paragraph are preserved`() {
        // Markdown would collapse these to a space, but models use a single newline to
        // mean a line break. Collapsing runs the lines together.
        val p = firstBlock("Nombre: Ana\nCiudad: Monterrey") as Block.Paragraph
        assertEquals("Nombre: Ana\nCiudad: Monterrey", text(p.spans))
    }

    // ── Headings ──────────────────────────────────────────────────────────────

    @Test fun `headings carry their level`() {
        assertEquals(1, (firstBlock("# Uno") as Block.Heading).level)
        assertEquals(3, (firstBlock("### Tres") as Block.Heading).level)
        assertEquals(6, (firstBlock("###### Seis") as Block.Heading).level)
    }

    @Test fun `heading marker is stripped from the text`() {
        assertEquals("Resumen", text((firstBlock("## Resumen") as Block.Heading).spans))
    }

    @Test fun `a hash without a space is not a heading`() {
        // "#1 de la lista" and hashtags are ordinary text.
        assertTrue(firstBlock("#1 de la lista") is Block.Paragraph)
    }

    @Test fun `seven hashes is not a heading`() {
        assertTrue(firstBlock("####### nope") is Block.Paragraph)
    }

    // ── Lists ─────────────────────────────────────────────────────────────────

    @Test fun `all three bullet markers are recognised`() {
        listOf("- uno", "* uno", "+ uno").forEach {
            assertTrue("no reconoció: $it", firstBlock(it) is Block.Bullet)
        }
    }

    @Test fun `bullet marker is stripped`() {
        assertEquals("comprar pan", text((firstBlock("- comprar pan") as Block.Bullet).spans))
    }

    @Test fun `indentation becomes depth`() {
        assertEquals(0, (firstBlock("- raíz") as Block.Bullet).depth)
        assertEquals(1, (firstBlock("  - hijo") as Block.Bullet).depth)
        assertEquals(2, (firstBlock("    - nieto") as Block.Bullet).depth)
    }

    @Test fun `absurd indentation is clamped instead of pushing text off-screen`() {
        assertEquals(3, (firstBlock("                    - hondo") as Block.Bullet).depth)
    }

    @Test fun `numbered lists keep their number`() {
        val b = firstBlock("3. tercero") as Block.Numbered
        assertEquals(3, b.number)
        assertEquals("tercero", text(b.spans))
    }

    @Test fun `numbered lists accept a closing paren`() {
        assertEquals(2, (firstBlock("2) segundo") as Block.Numbered).number)
    }

    @Test fun `a decimal number is not a list item`() {
        // "3.14 es pi" must not become item 3.
        assertTrue(firstBlock("3.14 es pi") is Block.Paragraph)
    }

    @Test fun `consecutive bullets are separate blocks`() {
        val blocks = ChatMarkdown.parse("- uno\n- dos\n- tres")
        assertEquals(3, blocks.size)
        assertTrue(blocks.all { it is Block.Bullet })
    }

    // ── Rules ─────────────────────────────────────────────────────────────────

    @Test fun `three dashes is a rule not a bullet`() {
        assertTrue(firstBlock("---") is Block.Rule)
        assertTrue(firstBlock("***") is Block.Rule)
        assertTrue(firstBlock("___") is Block.Rule)
    }

    @Test fun `a spaced rule still counts`() {
        assertTrue(firstBlock("- - -") is Block.Rule)
    }

    @Test fun `a dash with content is a bullet not a rule`() {
        assertTrue(firstBlock("- item") is Block.Bullet)
    }

    // ── Quotes ────────────────────────────────────────────────────────────────

    @Test fun `quote marker is stripped`() {
        assertEquals("citado", text((firstBlock("> citado") as Block.Quote).spans))
    }

    @Test fun `consecutive quote lines merge into one block`() {
        val blocks = ChatMarkdown.parse("> linea uno\n> linea dos")
        assertEquals(1, blocks.size)
        assertEquals("linea uno\nlinea dos", text((blocks[0] as Block.Quote).spans))
    }

    @Test fun `a normal line ends the quote`() {
        val blocks = ChatMarkdown.parse("> citado\nnormal")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is Block.Quote)
        assertTrue(blocks[1] is Block.Paragraph)
    }

    // ── Code fences ───────────────────────────────────────────────────────────

    @Test fun `a fenced block captures language and body verbatim`() {
        val c = firstBlock("```kotlin\nval x = 1\n```") as Block.Code
        assertEquals("kotlin", c.language)
        assertEquals("val x = 1", c.code)
        assertTrue(c.closed)
    }

    @Test fun `a fence without a language still parses`() {
        val c = firstBlock("```\nhola\n```") as Block.Code
        assertEquals("", c.language)
        assertEquals("hola", c.code)
    }

    @Test fun `markdown inside a fence is not interpreted`() {
        // A '#' in a shell script is a comment, not a heading.
        val c = firstBlock("```bash\n# comenta\n- no es lista\n**no negrita**\n```") as Block.Code
        assertEquals("# comenta\n- no es lista\n**no negrita**", c.code)
    }

    @Test fun `indentation inside a fence survives`() {
        val c = firstBlock("```\nif (a) {\n    b()\n}\n```") as Block.Code
        assertEquals("if (a) {\n    b()\n}", c.code)
    }

    // STREAMING: the closing fence has not arrived yet.
    @Test fun `an unterminated fence is still a code block`() {
        val c = firstBlock("```python\nprint(1)") as Block.Code
        assertEquals("python", c.language)
        assertEquals("print(1)", c.code)
        assertFalse("debe marcarse abierto para no dibujar el botón de copiar", c.closed)
    }

    @Test fun `an unterminated fence does not swallow following blocks into a paragraph`() {
        // If this regressed into Paragraph, the whole block would reflow and restyle the
        // moment the closing fence arrived.
        val blocks = ChatMarkdown.parse("Mira:\n```\nx")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is Block.Paragraph)
        assertTrue(blocks[1] is Block.Code)
    }

    @Test fun `text after a closed fence returns to normal parsing`() {
        val blocks = ChatMarkdown.parse("```\nx\n```\n# Titulo")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is Block.Code)
        assertTrue(blocks[1] is Block.Heading)
    }

    // ── Inline emphasis ───────────────────────────────────────────────────────

    @Test fun `double asterisks make bold and the markers disappear`() {
        val spans = ChatMarkdown.inlineSpans("esto es **importante** ya")
        assertEquals("esto es importante ya", text(spans))
        assertTrue(spans.single { it.text == "importante" }.bold)
    }

    @Test fun `underscores also make bold`() {
        assertTrue(ChatMarkdown.inlineSpans("__fuerte__").single().bold)
    }

    @Test fun `single asterisks make italic`() {
        assertTrue(ChatMarkdown.inlineSpans("*suave*").single().italic)
    }

    @Test fun `double tilde makes strikethrough`() {
        assertTrue(ChatMarkdown.inlineSpans("~~borrado~~").single().strike)
    }

    @Test fun `bold and italic can combine`() {
        val spans = ChatMarkdown.inlineSpans("**_ambos_**")
        val s = spans.single { it.text == "ambos" }
        assertTrue(s.bold)
        assertTrue(s.italic)
    }

    // STREAMING: emphasis that has not closed yet opens anyway, so the text does not
    // flicker from plain to bold when the closing marker lands.
    @Test fun `unclosed bold applies immediately`() {
        val spans = ChatMarkdown.inlineSpans("**Hola")
        assertEquals("Hola", text(spans))
        assertTrue(spans.single().bold)
    }

    // ── DO NOT MANGLE ─────────────────────────────────────────────────────────

    @Test fun `multiplication is not italics`() {
        val spans = ChatMarkdown.inlineSpans("2 * 3 = 6")
        assertEquals("2 * 3 = 6", text(spans))
        assertFalse(spans.any { it.italic })
    }

    @Test fun `snake_case survives`() {
        val spans = ChatMarkdown.inlineSpans("usa snake_case_aqui")
        assertEquals("usa snake_case_aqui", text(spans))
        assertFalse(spans.any { it.italic })
    }

    @Test fun `a stray double marker is kept literally`() {
        assertEquals("a ** b", text(ChatMarkdown.inlineSpans("a ** b")))
    }

    @Test fun `a lone backtick is literal instead of swallowing the message`() {
        assertEquals("precio: 5` y ya", text(ChatMarkdown.inlineSpans("precio: 5` y ya")))
    }

    @Test fun `emphasis markers inside inline code are left alone`() {
        val spans = ChatMarkdown.inlineSpans("llama a `do_a_thing()`")
        val code = spans.single { it.code }
        assertEquals("do_a_thing()", code.text)
        assertFalse(spans.any { it.italic })
    }

    @Test fun `inline code padding is treated as syntax`() {
        assertEquals("`", ChatMarkdown.inlineSpans("`` ` ``").single { it.code }.text)
    }

    // ── A realistic reply ─────────────────────────────────────────────────────

    @Test fun `a typical assistant reply parses into the expected block sequence`() {
        val reply = """
            ## Resumen

            Encontré **3** opciones:

            1. Reiniciar el servicio
            2. Revisar los permisos

            ```bash
            adb shell pm list packages
            ```

            > Ojo: requiere ADB.
        """.trimIndent()

        val kinds = ChatMarkdown.parse(reply).map { it::class.simpleName }
        assertEquals(
            listOf("Heading", "Paragraph", "Numbered", "Numbered", "Code", "Quote"),
            kinds,
        )
    }

    @Test fun `parsing every prefix of a reply never throws`() {
        // Simulates streaming: the parser sees each partial state exactly once.
        val reply = "# T\n\nHola **mundo** y `code`\n\n```kt\nval x = 1\n```\n\n- uno\n> cita"
        for (n in 0..reply.length) {
            ChatMarkdown.parse(reply.substring(0, n))
        }
    }
}

/**
 * Pipe tables.
 *
 * Split out because tables are the one block whose detection depends on the *next* line,
 * which makes the streaming behaviour and the "this is not a table" cases the interesting
 * ones.
 */
class ChatMarkdownTableTest {

    private fun text(cell: ChatMarkdown.Cell) = cell.spans.joinToString("") { it.text }
    private fun row(cells: List<ChatMarkdown.Cell>) = cells.map(::text)

    private val simple = """
        | Ciudad | Temp |
        |--------|------|
        | Madrid | 31   |
        | Oslo   | 4    |
    """.trimIndent()

    // ── Recognised ────────────────────────────────────────────────────────────

    @Test fun `a table is parsed into header and rows`() {
        val table = ChatMarkdown.parse(simple).single() as ChatMarkdown.Block.Table
        assertEquals(listOf("Ciudad", "Temp"), row(table.header))
        assertEquals(2, table.rows.size)
        assertEquals(listOf("Madrid", "31"), row(table.rows[0]))
        assertEquals(listOf("Oslo", "4"), row(table.rows[1]))
    }

    @Test fun `the separator line is not emitted as content`() {
        val table = ChatMarkdown.parse(simple).single() as ChatMarkdown.Block.Table
        assertTrue(table.rows.none { r -> row(r).any { it.contains("-") } })
    }

    @Test fun `a header only table is still a table`() {
        val blocks = ChatMarkdown.parse("| A | B |\n|---|---|")
        val table = blocks.single() as ChatMarkdown.Block.Table
        assertEquals(listOf("A", "B"), row(table.header))
        assertEquals(0, table.rows.size)
    }

    @Test fun `alignment colons are read from the separator`() {
        val table = ChatMarkdown.parse("| a | b | c |\n|:--|--:|:-:|\n| 1 | 2 | 3 |")
            .single() as ChatMarkdown.Block.Table
        assertEquals(
            listOf(ChatMarkdown.Align.START, ChatMarkdown.Align.END, ChatMarkdown.Align.CENTER),
            table.alignments,
        )
    }

    @Test fun `columns without colons default to start`() {
        val table = ChatMarkdown.parse(simple).single() as ChatMarkdown.Block.Table
        assertEquals(listOf(ChatMarkdown.Align.START, ChatMarkdown.Align.START), table.alignments)
    }

    @Test fun `inline styling inside a cell is parsed`() {
        val table = ChatMarkdown.parse("| a |\n|---|\n| **fuerte** |")
            .single() as ChatMarkdown.Block.Table
        assertTrue(table.rows[0][0].spans.single().bold)
    }

    @Test fun `inline code inside a cell is parsed`() {
        val table = ChatMarkdown.parse("| cmd |\n|---|\n| `ls -la` |")
            .single() as ChatMarkdown.Block.Table
        assertTrue(table.rows[0][0].spans.single().code)
    }

    @Test fun `an escaped pipe stays inside the cell`() {
        val table = ChatMarkdown.parse("| a | b |\n|---|---|\n| x \\| y | z |")
            .single() as ChatMarkdown.Block.Table
        assertEquals(listOf("x | y", "z"), row(table.rows[0]))
    }

    @Test fun `a table without trailing pipes still parses`() {
        val table = ChatMarkdown.parse("| a | b\n|---|---\n| 1 | 2")
            .single() as ChatMarkdown.Block.Table
        assertEquals(listOf("a", "b"), row(table.header))
        assertEquals(listOf("1", "2"), row(table.rows[0]))
    }

    // ── Ragged rows keep the grid ─────────────────────────────────────────────

    @Test fun `a short row is padded instead of dropped`() {
        val table = ChatMarkdown.parse("| a | b | c |\n|---|---|---|\n| 1 |")
            .single() as ChatMarkdown.Block.Table
        assertEquals(listOf("1", "", ""), row(table.rows[0]))
    }

    @Test fun `a long row is truncated to the header width`() {
        val table = ChatMarkdown.parse("| a | b |\n|---|---|\n| 1 | 2 | 3 | 4 |")
            .single() as ChatMarkdown.Block.Table
        assertEquals(listOf("1", "2"), row(table.rows[0]))
    }

    @Test fun `alignments always match the header width`() {
        // Fewer separator cells than header cells must not leave the renderer indexing
        // past the end of the list.
        val table = ChatMarkdown.parse("| a | b | c |\n|---|---|\n| 1 | 2 | 3 |")
            .single() as ChatMarkdown.Block.Table
        assertEquals(table.header.size, table.alignments.size)
    }

    // ── Not a table ───────────────────────────────────────────────────────────

    @Test fun `a line with pipes but no separator stays a paragraph`() {
        // Otherwise any sentence containing a pipe would become a one-cell table.
        val blocks = ChatMarkdown.parse("| esto no es una tabla")
        assertTrue(blocks.single() is ChatMarkdown.Block.Paragraph)
    }

    @Test fun `prose containing a pipe is left alone`() {
        val blocks = ChatMarkdown.parse("Usa grep | wc para contar")
        assertTrue(blocks.single() is ChatMarkdown.Block.Paragraph)
    }

    @Test fun `a separator whose cells are not dashes is not a separator`() {
        val blocks = ChatMarkdown.parse("| a | b |\n| x | y |")
        assertTrue(blocks.none { it is ChatMarkdown.Block.Table })
    }

    @Test fun `a horizontal rule is still a rule and not a table`() {
        assertTrue(ChatMarkdown.parse("---").single() is ChatMarkdown.Block.Rule)
    }

    @Test fun `a table inside a code fence is not interpreted`() {
        val fenced = "```\n| a | b |\n|---|---|\n| 1 | 2 |\n```"
        val block = ChatMarkdown.parse(fenced).single()
        assertTrue(block is ChatMarkdown.Block.Code)
        assertTrue((block as ChatMarkdown.Block.Code).code.contains("|---|---|"))
    }

    @Test fun `a bullet list is not turned into a table`() {
        assertTrue(ChatMarkdown.parse("- uno\n- dos").all { it is ChatMarkdown.Block.Bullet })
    }

    // ── Boundaries ────────────────────────────────────────────────────────────

    @Test fun `text before and after a table is kept`() {
        val blocks = ChatMarkdown.parse("Resumen:\n\n| a |\n|---|\n| 1 |\n\nFin.")
        val kinds = blocks.map { it::class.simpleName }
        assertEquals(listOf("Paragraph", "Table", "Paragraph"), kinds)
    }

    @Test fun `a blank line ends the table`() {
        val blocks = ChatMarkdown.parse("| a |\n|---|\n| 1 |\n\n| b |\n|---|\n| 2 |")
        assertEquals(2, blocks.count { it is ChatMarkdown.Block.Table })
    }

    @Test fun `a non-table line ends the table`() {
        val blocks = ChatMarkdown.parse("| a |\n|---|\n| 1 |\nTexto normal")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is ChatMarkdown.Block.Table)
        assertTrue(blocks[1] is ChatMarkdown.Block.Paragraph)
    }

    @Test fun `an enormous table is capped`() {
        val body = (1..400).joinToString("\n") { "| $it |" }
        val table = ChatMarkdown.parse("| n |\n|---|\n$body").single() as ChatMarkdown.Block.Table
        assertTrue("filas: ${table.rows.size}", table.rows.size <= 100)
    }

    // ── Streaming ─────────────────────────────────────────────────────────────

    @Test fun `a header without its separator yet is a paragraph, not a broken table`() {
        // The single accepted reflow: this becomes a table the moment the separator lands.
        assertTrue(ChatMarkdown.parse("| Ciudad | Temp |").single() is ChatMarkdown.Block.Paragraph)
    }

    @Test fun `a table becomes a table as soon as the separator arrives`() {
        assertTrue(
            ChatMarkdown.parse("| Ciudad | Temp |\n|---|---|").single()
                is ChatMarkdown.Block.Table
        )
    }

    @Test fun `a half written row renders without losing the rest of the table`() {
        val table = ChatMarkdown.parse("| a | b |\n|---|---|\n| 1 | 2 |\n| 3")
            .single() as ChatMarkdown.Block.Table
        assertEquals(2, table.rows.size)
        assertEquals(listOf("3", ""), row(table.rows[1]))
    }

    @Test fun `parsing every prefix of a table never throws`() {
        val md = "Datos:\n\n| Ciudad | Temp | Nota |\n|:--|--:|:-:|\n| Madrid | 31 | `sol` |\n| Oslo | 4 | **frío** |\n\nFin."
        for (n in 0..md.length) {
            ChatMarkdown.parse(md.substring(0, n))
        }
    }

    // ── Cell splitting ────────────────────────────────────────────────────────

    @Test fun `outer pipes are delimiters and not empty cells`() {
        assertEquals(listOf("a", "b"), ChatMarkdown.splitCells("| a | b |"))
    }

    @Test fun `an empty cell is preserved`() {
        assertEquals(listOf("a", "", "c"), ChatMarkdown.splitCells("| a |  | c |"))
    }

    @Test fun `separator detection ignores surrounding spaces`() {
        assertTrue(ChatMarkdown.isSeparatorRow("|  ---  |  :---:  |"))
        assertTrue(!ChatMarkdown.isSeparatorRow("| a | b |"))
        assertTrue(!ChatMarkdown.isSeparatorRow("|   |   |"))
    }
}
