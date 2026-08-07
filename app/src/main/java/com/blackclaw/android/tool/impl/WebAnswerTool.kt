package com.blackclaw.android.tool.impl

import com.blackclaw.android.cards.AssistCard
import com.blackclaw.android.cards.AssistCardCodec
import com.blackclaw.android.cards.PriceText
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import com.blackclaw.android.utils.XLog
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Real web search that RETURNS readable results to the agent (unlike web_search,
 * which only opens a browser). Lets BlackClaw answer "¿qué película se estrena
 * hoy?", "¿quién ganó anoche?", "precio del bitcoin", etc.
 *
 * No API key: uses DuckDuckGo's free endpoints —
 *  1. Instant Answer API (api.duckduckgo.com) for direct facts.
 *  2. HTML results (html.duckduckgo.com) for snippets, parsed to plain text.
 *
 * The agent reads the returned snippets and composes the answer itself.
 */
class WebAnswerTool : BaseTool() {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    override fun getName() = "web_answer"
    override fun getDisplayName() = "Buscar en internet"
    override fun getDescriptionEN() =
        "Search the web and RETURN the results as text so you can answer the user. " +
        "Use for anything needing current/online info: movie releases, news, scores, prices, " +
        "schedules, facts, 'what's on today', etc. Returns top result snippets — read them and " +
        "answer the user directly. This actually retrieves content (unlike web_search which only " +
        "opens a browser)."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "busca en internet y DEVUELVE los resultados como texto para responder"

    override fun getParameters() = listOf(
        ToolParameter("query", "string", "What to search for.", true),
        ToolParameter("max_results", "integer", "How many result snippets to return (default 5).", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val query = requireString(params, "query").trim()
        if (query.isEmpty()) return ToolResult.error("query vacío")
        val maxResults = optionalInt(params, "max_results", 5).coerceIn(1, 10)

        val sb = StringBuilder()

        // 1) Instant Answer (direct fact, when available).
        runCatching { instantAnswer(query) }.getOrNull()?.let {
            if (it.isNotBlank()) sb.append("Respuesta directa: ").append(it).append("\n\n")
        }

        // 2) HTML result snippets (DuckDuckGo, then Bing as fallback).
        var results = runCatching { htmlResults(query, maxResults) }.getOrDefault(emptyList())
        if (results.isEmpty()) {
            results = runCatching { bingResults(query, maxResults) }.getOrDefault(emptyList())
        }
        if (results.isNotEmpty()) {
            sb.append("Resultados de búsqueda:\n")
            results.forEachIndexed { i, r ->
                sb.append("${i + 1}. ${r.title}\n")
                if (r.snippet.isNotBlank()) sb.append("   ${r.snippet}\n")
                if (r.url.isNotBlank()) sb.append("   ${r.url}\n")
            }
        }

        val out = sb.toString().trim()
        return if (out.isBlank())
            ToolResult.error("No obtuve resultados para '$query'. Intenta reformular la búsqueda.")
        else ToolResult.successWithCards(
            data = out.take(8000),
            cards = AssistCardCodec.encode(results.map(::cardFor)),
        )
    }

    /**
     * One search result as a card.
     *
     * A result becomes an offer only when a price is actually present in its title or
     * snippet, and the price is carried through exactly as written. Guessing a price the
     * user might act on would be worse than showing none, so anything without one stays a
     * plain link.
     *
     * The title is searched before the snippet because shopping results put the price in
     * the title and a merchant's snippet often quotes a different, unrelated one
     * ("desde 99 €", shipping thresholds, other products).
     */
    private fun cardFor(r: Result): AssistCard {
        val price = PriceText.find(r.title) ?: PriceText.find(r.snippet)
        return if (price == null) {
            AssistCard.Link(title = r.title, url = r.url, snippet = r.snippet)
        } else {
            AssistCard.Offer(
                title = r.title,
                priceLabel = price,
                url = r.url,
                merchant = hostOf(r.url),
                snippet = r.snippet,
            )
        }
    }

    private fun hostOf(url: String): String = runCatching {
        java.net.URI(url).host?.removePrefix("www.").orEmpty()
    }.getOrDefault("")

    private data class Result(val title: String, val snippet: String, val url: String)

    private fun instantAnswer(query: String): String {
        val url = "https://api.duckduckgo.com/?q=${enc(query)}&format=json&no_html=1&skip_disambig=1"
        val req = Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Android) BlackClaw").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return ""
            val body = resp.body?.string() ?: return ""
            val o = JSONObject(body)
            val abstract = o.optString("AbstractText", "")
            if (abstract.isNotBlank()) return abstract
            val answer = o.optString("Answer", "")
            if (answer.isNotBlank()) return answer
            // Related topics first item
            val related = o.optJSONArray("RelatedTopics")
            if (related != null && related.length() > 0) {
                val first = related.optJSONObject(0)
                val t = first?.optString("Text", "") ?: ""
                if (t.isNotBlank()) return t
            }
            return ""
        }
    }

    private fun htmlResults(query: String, max: Int): List<Result> {
        val url = "https://html.duckduckgo.com/html/?q=${enc(query)}"
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Accept", "text/html").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) { XLog.d("WebAnswer", "ddg html ${resp.code}"); return emptyList() }
            val html = resp.body?.string() ?: return emptyList()
            return parseDdgHtml(html, max)
        }
    }

    /** Extract title/snippet/url triples from DuckDuckGo HTML results page. */
    private fun parseDdgHtml(html: String, max: Int): List<Result> {
        val results = mutableListOf<Result>()
        // Titles + URLs: <a ... class="result__a" href="URL">TITLE</a>
        val linkRe = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        // Snippets: <a ... class="result__snippet"...>SNIPPET</a>
        val snipRe = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

        val links = linkRe.findAll(html).toList()
        val snips = snipRe.findAll(html).toList()
        for (i in links.indices) {
            if (results.size >= max) break
            val rawUrl = links[i].groupValues[1]
            val title = stripHtml(links[i].groupValues[2])
            val snippet = snips.getOrNull(i)?.groupValues?.get(1)?.let { stripHtml(it) } ?: ""
            if (title.isNotBlank()) {
                results.add(Result(title, snippet, cleanDdgUrl(rawUrl)))
            }
        }
        return results
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
            .trim()

    /** DDG wraps URLs as /l/?uddg=ENCODED — unwrap to the real URL. */
    private fun cleanDdgUrl(raw: String): String {
        val marker = "uddg="
        val idx = raw.indexOf(marker)
        if (idx < 0) return raw.take(120)
        val enc = raw.substring(idx + marker.length).substringBefore("&")
        return runCatching { java.net.URLDecoder.decode(enc, "UTF-8") }.getOrDefault(raw).take(120)
    }

    /** Bing HTML fallback when DuckDuckGo returns nothing. */
    private fun bingResults(query: String, max: Int): List<Result> {
        val url = "https://www.bing.com/search?q=${enc(query)}"
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Accept", "text/html").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val html = resp.body?.string() ?: return emptyList()
            val results = mutableListOf<Result>()
            // Bing results: <li class="b_algo"> … <h2><a href="URL">TITLE</a></h2> … <p>SNIPPET</p>
            val blockRe = Regex("""<li class="b_algo">(.*?)</li>""", RegexOption.DOT_MATCHES_ALL)
            val titleRe = Regex("""<h2>.*?<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            val snipRe = Regex("""<p[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
            for (m in blockRe.findAll(html)) {
                if (results.size >= max) break
                val block = m.groupValues[1]
                val t = titleRe.find(block) ?: continue
                val title = stripHtml(t.groupValues[2])
                val link = t.groupValues[1]
                val snippet = snipRe.find(block)?.groupValues?.get(1)?.let { stripHtml(it) } ?: ""
                if (title.isNotBlank()) results.add(Result(title, snippet, link.take(120)))
            }
            return results
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
