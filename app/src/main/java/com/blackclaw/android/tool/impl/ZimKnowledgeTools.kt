package com.blackclaw.android.tool.impl

import com.blackclaw.android.knowledge.DirectZimLibrary
import com.blackclaw.android.knowledge.DirectZimReader
import com.blackclaw.android.knowledge.ZimContentIndex
import com.blackclaw.android.knowledge.ZimConsultEngine
import com.blackclaw.android.knowledge.ZimIndexService
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

class ZimSearchTool : BaseTool() {
    override fun getName() = "zim_search"
    override fun getDisplayName() = "Buscar en ZIM offline"
    override fun getDescriptionEN() = "Search titles and, when built, the local full-content index of a .zim archive offline. Returns bounded snippets and never injects an entire archive."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getParameters() = listOf(
        ToolParameter("query", "string", "Tema o artículo que se buscará offline.", true),
        ToolParameter("library", "string", "Nombre o ruta del .zim. Opcional si solo existe uno.", false),
        ToolParameter("limit", "integer", "Máximo de resultados, 1-10. Default 5.", false),
        ToolParameter("scope", "string", "auto | title | content. Default auto.", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val resolution = DirectZimLibrary.resolve(optionalString(params, "library", ""))
        val file = resolution.file ?: return ToolResult.error(resolution.message)
        val query = requireString(params, "query")
        val limit = optionalInt(params, "limit", 5).coerceIn(1, 10)
        val scope = optionalString(params, "scope", "auto").lowercase()
        if (scope !in setOf("auto", "title", "content")) return ToolResult.error("scope debe ser auto, title o content")
        return runCatching {
            val contentAvailable = ZimContentIndex.exists(ClawApplication.instance, file)
            if (scope == "content" && !contentAvailable) {
                return@runCatching ToolResult.error("Aún no existe índice auxiliar de contenido para ${file.name}. Para consultar sin indexar, usa zim_consult; zim_index queda como respaldo opcional.")
            }
            val contentHits = if (scope != "title" && contentAvailable) {
                DirectZimReader(file).use { reader ->
                    ZimContentIndex.open(ClawApplication.instance, file, reader.titleEntryCount).use { it.search(query, limit) }
                }
            } else emptyList()
            DirectZimReader(file).use { reader ->
                val titleHits = if (scope != "content") reader.searchTitles(query, limit) else emptyList()
                val seen = HashSet<String>()
                val rendered = ArrayList<String>()
                contentHits.forEach { hit ->
                    if (seen.add(hit.path)) rendered += "${hit.title} · path=${hit.path}\n   ${hit.snippet}"
                }
                titleHits.forEach { hit ->
                    if (seen.add(hit.path)) rendered += "${hit.title} · path=${hit.path} · coincidencia en título"
                }
                if (rendered.isEmpty()) ToolResult.success("Sin coincidencias en ${file.name}.")
                else ToolResult.success(buildString {
                    appendLine("Resultados de ${reader.libraryInfo()}:")
                    rendered.take(limit).forEachIndexed { i, value -> appendLine("${i + 1}. $value") }
                    append("Fuente: ${file.name} (archivo ZIM local, sin internet). Usa zim_read con title_or_path.")
                    if (!contentAvailable) append(" Para responder preguntas sin indexar el archivo, usa zim_consult con los temas principales.")
                })
            }
        }.getOrElse { ToolResult.error("No pude buscar en ${file.name}: ${it.message}") }
    }
}

class ZimConsultTool : BaseTool() {
    override fun getName() = "zim_consult"
    override fun getDisplayName() = "Consultar biblioteca ZIM"
    override fun getDescriptionEN() = "Consult a local .zim like a reference book without pre-indexing it: select likely articles from the ZIM title index, read only those articles, and return small relevant source-labelled passages. Pass core entities in topics for best retrieval."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getParameters() = listOf(
        ToolParameter("question", "string", "Pregunta concreta que debe responderse usando la biblioteca offline.", true),
        ToolParameter("topics", "string", "Entidades o temas principales separados por comas; por ejemplo: Francia, París. Recomendado.", false),
        ToolParameter("library", "string", "Nombre o ruta del .zim. Opcional si sólo existe uno.", false),
        ToolParameter("max_articles", "integer", "Artículos candidatos que se consultarán, 1-8. Default 5.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val resolution = DirectZimLibrary.resolve(optionalString(params, "library", ""))
        val file = resolution.file ?: return ToolResult.error(resolution.message)
        val question = requireString(params, "question")
        val topics = optionalString(params, "topics", "")
        return runCatching {
            DirectZimReader(file).use { reader ->
                val excerpts = ZimConsultEngine.consult(
                    reader, question, topics, optionalInt(params, "max_articles", 5),
                )
                if (excerpts.isEmpty()) {
                    ToolResult.success("No encontré capítulos candidatos para esa consulta en ${file.name}. Prueba indicando los nombres propios o temas centrales en topics.")
                } else ToolResult.success(buildString {
                    appendLine("Consulta offline en ${reader.libraryInfo()}")
                    appendLine("Pregunta: $question")
                    excerpts.forEachIndexed { index, excerpt ->
                        appendLine("\n[${index + 1}] ${excerpt.title} · path=${excerpt.path}")
                        appendLine(excerpt.text)
                    }
                    append("Fuente exclusiva: ${file.name} (ZIM local). Fragmentos seleccionados; el archivo y los artículos completos no se añadieron al contexto.")
                })
            }
        }.getOrElse { ToolResult.error("No pude consultar ${file.name}: ${it.message}") }
    }
}

class ZimIndexTool : BaseTool() {
    override fun getName() = "zim_index"
    override fun getDisplayName() = "Índice local ZIM"
    override fun getDescriptionEN() = "Optional fallback for archives or queries not resolved by zim_consult. Start, resume, rebuild, pause, or inspect a persistent local full-content index."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getParameters() = listOf(
        ToolParameter("action", "string", "start | resume | rebuild | stop | status", true),
        ToolParameter("library", "string", "Nombre o ruta del .zim. Opcional si solo existe uno.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val action = requireString(params, "action").lowercase().trim()
        if (action == "stop" || action == "pause" || action == "pausar") {
            ZimIndexService.stop(ClawApplication.instance)
            return ToolResult.success("Pausando el índice ZIM. El progreso queda guardado para reanudarlo.")
        }
        val resolution = DirectZimLibrary.resolve(optionalString(params, "library", ""))
        val file = resolution.file ?: return ToolResult.error(resolution.message)
        return when (action) {
            "start", "resume", "reanudar", "rebuild", "reconstruir" -> {
                if (ZimIndexService.isRunning) {
                    ToolResult.success("Ya se está indexando ${ZimIndexService.activePath ?: "una biblioteca ZIM"}.")
                } else if (ZimIndexService.start(ClawApplication.instance, file, action in setOf("rebuild", "reconstruir"))) {
                    ToolResult.success("Índice de ${file.name} iniciado en segundo plano. Puedes ver el progreso y pausarlo desde la notificación.")
                } else ToolResult.error("Android no permitió iniciar el indexador. Abre BlackClaw y vuelve a intentarlo.")
            }
            "status", "estado" -> {
                if (!ZimContentIndex.exists(ClawApplication.instance, file)) {
                    ToolResult.success("${file.name}: índice de contenido todavía no creado.")
                } else runCatching {
                    DirectZimReader(file).use { reader ->
                        ZimContentIndex.open(ClawApplication.instance, file, reader.titleEntryCount).use { index ->
                            val s = index.status()
                            ToolResult.success("${file.name}: ${s.percent}% · ${s.indexed} artículos · ${s.skipped} omitidos · " +
                                if (s.complete) "completo" else if (ZimIndexService.isRunning) "indexando" else "pausado")
                        }
                    }
                }.getOrElse { ToolResult.error("No pude leer el estado: ${it.message}") }
            }
            else -> ToolResult.error("action debe ser start, resume, rebuild, stop o status")
        }
    }
}

class ZimReadTool : BaseTool() {
    override fun getName() = "zim_read"
    override fun getDisplayName() = "Leer artículo ZIM"
    override fun getDescriptionEN() = "Read one article directly from a local .zim archive and return a bounded offline text excerpt with source attribution."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getParameters() = listOf(
        ToolParameter("title_or_path", "string", "Título o path exacto devuelto por zim_search.", true),
        ToolParameter("library", "string", "Nombre o ruta del .zim. Opcional si solo existe uno.", false),
        ToolParameter("max_chars", "integer", "Máximo de caracteres, 500-12000. Default 5000.", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val resolution = DirectZimLibrary.resolve(optionalString(params, "library", ""))
        val file = resolution.file ?: return ToolResult.error(resolution.message)
        return runCatching {
            DirectZimReader(file).use { reader ->
                val article = reader.readArticle(requireString(params, "title_or_path"), optionalInt(params, "max_chars", 5_000).coerceIn(500, 12_000))
                ToolResult.success("Artículo offline: ${article.title}\nFuente: ${file.name} · ${article.path}\n\n${article.text}")
            }
        }.getOrElse { ToolResult.error("No pude leer ${file.name}: ${it.message}") }
    }
}
