package com.robert4lyf.hypershop.extract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/** Which extractor found the lines (PLAN §3.1, in this order). */
enum class Method { WPRM, TASTY, JSON_LD, MICRODATA, HEADING, PAGE_TEXT, MANUAL }

data class ExtractedRecipe(
    val title: String?,
    val servings: Int?,
    /** Ingredient lines; section headers appear as "Header:" lines (spec R8). */
    val lines: List<String>,
    val method: Method,
)

data class Extraction(
    val pageTitle: String?,
    val recipes: List<ExtractedRecipe>,
    /** Trimmed page text, for Claude when no structured data was found. */
    val pageText: String?,
    val method: Method,
)

/**
 * Finds ingredient lines in a page (PLAN §3.1): WPRM/Tasty plugin markup,
 * JSON-LD `Recipe`, microdata, the block under an "Ingredients" heading, then
 * the page text for Claude, then "Select lines manually".
 *
 * Limits: each JSON-LD block ≤ 512 KB and nesting ≤ 64; ≤ 10 recipes a page,
 * ≤ 300 lines a recipe, ≤ 500 characters a line. Running out of memory or
 * stack while parsing ends in [Method.MANUAL].
 */
object Extractor {
    const val MAX_BLOCK = 512 * 1024
    const val MAX_DEPTH = 64
    const val MAX_RECIPES = 10
    const val MAX_LINES = 300
    const val MAX_LINE = 500
    const val MAX_PAGE_TEXT = 30_000

    private val json =
        Json {
            isLenient = true
            ignoreUnknownKeys = true
            @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
            allowTrailingComma = true

            @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
            allowComments = true
        }

    fun extract(
        html: String,
        baseUrl: String = "",
    ): Extraction =
        try {
            extractUnsafe(html, baseUrl)
        } catch (e: OutOfMemoryError) {
            Extraction(null, emptyList(), null, Method.MANUAL)
        } catch (e: StackOverflowError) {
            Extraction(null, emptyList(), null, Method.MANUAL)
        }

    private fun extractUnsafe(
        html: String,
        baseUrl: String,
    ): Extraction {
        val doc = Jsoup.parse(html, baseUrl)
        val pageTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.ifBlank { null } ?: doc.title().ifBlank { null }
        val ld = jsonLd(doc)
        val ldTitle = ld.firstOrNull()?.title
        val ldServings = ld.firstOrNull()?.servings
        for (plugin in listOf(::wprm, ::tasty)) {
            plugin(
                doc,
            )?.let { return Extraction(pageTitle, listOf(it.copy(title = ldTitle ?: pageTitle, servings = ldServings)), null, it.method) }
        }
        if (ld.isNotEmpty()) return Extraction(pageTitle, ld, null, Method.JSON_LD)
        microdata(doc)?.let { return Extraction(pageTitle, listOf(it.copy(title = it.title ?: pageTitle)), null, Method.MICRODATA) }
        heading(doc)?.let { return Extraction(pageTitle, listOf(it.copy(title = pageTitle)), null, Method.HEADING) }
        val text = pageText(doc)
        return if (text.isNullOrBlank()) {
            Extraction(pageTitle, emptyList(), null, Method.MANUAL)
        } else {
            Extraction(pageTitle, emptyList(), text, Method.PAGE_TEXT)
        }
    }

    // ---- 1. recipe plugins -----------------------------------------------------------

    private fun wprm(doc: Document): ExtractedRecipe? {
        val container = doc.selectFirst(".wprm-recipe-ingredients-container") ?: return null
        val lines = ArrayList<String>()
        val groups = container.select(".wprm-recipe-ingredient-group")
        for (g in groups.ifEmpty { listOf(container) }) {
            g.selectFirst(
                ".wprm-recipe-group-name",
            )?.text()?.let { clean(it) }?.takeIf { it.isNotEmpty() }?.let { lines += "${it.trimEnd(':')}:" }
            for (li in g.select("li.wprm-recipe-ingredient")) {
                val parts =
                    listOf("amount", "unit", "name", "notes").mapNotNull { cls ->
                        li.selectFirst(".wprm-recipe-ingredient-$cls")?.text()?.trim()?.ifEmpty { null }
                    }
                val text = if (parts.isEmpty()) li.text() else parts.joinToString(" ")
                clean(text).takeIf { it.isNotEmpty() }?.let { lines += it }
            }
        }
        return recipe(lines, Method.WPRM)
    }

    private fun tasty(doc: Document): ExtractedRecipe? {
        val container = doc.selectFirst(".tasty-recipes-ingredients") ?: return null
        val lines = ArrayList<String>()
        for (el in container.select("h3, h4, li")) {
            val t = clean(el.text())
            if (t.isEmpty()) continue
            lines += if (el.tagName() == "li") t else "${t.trimEnd(':')}:"
        }
        return recipe(lines, Method.TASTY)
    }

    // ---- 2. JSON-LD ------------------------------------------------------------------

    fun jsonLd(doc: Document): List<ExtractedRecipe> {
        val out = ArrayList<ExtractedRecipe>()
        for (s in doc.select("script[type=application/ld+json]")) {
            val raw = s.data()
            if (raw.length > MAX_BLOCK || depth(raw) > MAX_DEPTH) continue
            val el = runCatching { json.parseToJsonElement(sanitiseJson(raw)) }.getOrNull() ?: continue
            for (r in findRecipes(el, 0)) {
                if (out.size >= MAX_RECIPES) return out
                val lines = ingredientsOf(r)
                if (lines.isEmpty()) continue
                out +=
                    ExtractedRecipe(
                        title = str(r["name"])?.let { clean(it) }?.ifEmpty { null },
                        servings = servings(r["recipeYield"] ?: r["yield"]),
                        lines = lines.take(MAX_LINES),
                        method = Method.JSON_LD,
                    )
            }
        }
        return out
    }

    private fun findRecipes(
        el: JsonElement,
        d: Int,
    ): List<JsonObject> {
        if (d > MAX_DEPTH) return emptyList()
        return when (el) {
            is JsonArray -> el.flatMap { findRecipes(it, d + 1) }
            is JsonObject -> {
                val type = el["@type"]
                val isRecipe =
                    when (type) {
                        is JsonPrimitive -> type.contentOrNull == "Recipe"
                        is JsonArray -> type.any { (it as? JsonPrimitive)?.contentOrNull == "Recipe" }
                        else -> false
                    }
                if (isRecipe) {
                    listOf(el)
                } else {
                    listOfNotNull(el["@graph"], el["mainEntity"], el["itemListElement"], el["item"]).flatMap { findRecipes(it, d + 1) }
                }
            }
            else -> emptyList()
        }
    }

    private fun ingredientsOf(r: JsonObject): List<String> {
        val v = r["recipeIngredient"] ?: r["ingredients"] ?: return emptyList()
        val flat = ArrayList<String>()

        fun walk(
            e: JsonElement,
            d: Int,
        ) {
            if (flat.size >= MAX_LINES || d > MAX_DEPTH) return
            when (e) {
                is JsonArray -> e.forEach { walk(it, d + 1) }
                is JsonPrimitive -> e.contentOrNull?.let { s -> s.split('\n').forEach { flat += it } }
                is JsonObject -> str(e["text"] ?: e["name"])?.let { flat += it }
                else -> Unit
            }
        }
        walk(v, 0)
        return flat.map { clean(it) }.filter { it.isNotEmpty() }
    }

    /** recipeYield: 4, "4", "4 servings", "Serves 4-6", ["4", "4 servings"]. */
    fun servings(e: JsonElement?): Int? {
        val s =
            when (e) {
                null, is JsonNull -> return null
                is JsonArray -> return e.firstNotNullOfOrNull { servings(it) }
                is JsonPrimitive -> e.contentOrNull ?: return null
                is JsonObject -> str(e["value"]) ?: return null
            }
        var i = 0
        while (i < s.length && !s[i].isDigit()) i++
        var j = i
        while (j < s.length && s[j].isDigit() && j - i < 3) j++
        return s.substring(i, j).toIntOrNull()?.takeIf { it in 1..100 }
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull

    /** Nesting depth outside strings, in one pass. */
    fun depth(s: String): Int {
        var d = 0
        var max = 0
        var inStr = false
        var esc = false
        for (c in s) {
            if (inStr) {
                if (esc) {
                    esc = false
                } else if (c == '\\') {
                    esc = true
                } else if (c == '"') {
                    inStr = false
                }
                continue
            }
            when (c) {
                '"' -> inStr = true
                '[', '{' -> {
                    d++
                    if (d > max) max = d
                }
                ']', '}' -> d--
            }
        }
        return max
    }

    /** Lenient JSON: raw control characters inside strings (common in the wild) become spaces. */
    fun sanitiseJson(s: String): String {
        val sb = StringBuilder(s.length)
        var inStr = false
        var esc = false
        for (c in s) {
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                    c < ' ' -> {
                        sb.append(' ')
                        continue
                    }
                }
            } else if (c == '"') {
                inStr = true
            }
            sb.append(c)
        }
        return sb.toString()
    }

    // ---- 3. microdata, 4. heading, 5. page text ----------------------------------------

    private fun microdata(doc: Document): ExtractedRecipe? {
        val scope = doc.select("[itemscope][itemtype~=(?i)schema.org/Recipe]").firstOrNull() ?: return null
        val lines = scope.select("[itemprop=recipeIngredient], [itemprop=ingredients]").map { clean(it.text()) }.filter { it.isNotEmpty() }
        val title = scope.selectFirst("[itemprop=name]")?.text()?.let { clean(it) }
        val yieldText = scope.selectFirst("[itemprop=recipeYield]")?.let { it.attr("content").ifBlank { it.text() } }
        return recipe(lines, Method.MICRODATA)?.copy(title = title, servings = yieldText?.let { servings(JsonPrimitive(it)) })
    }

    private fun heading(doc: Document): ExtractedRecipe? {
        val h =
            doc.select("h1, h2, h3, h4, h5, h6, strong, b, p").firstOrNull {
                val t = it.ownText().trim().lowercase().trimEnd(':')
                t == "ingredients" || (t.startsWith("ingredients") && t.length <= 40)
            } ?: return null
        // The first list after the heading, in document order.
        val start = h.parents().firstOrNull { it.nextElementSibling() != null && it.tagName() != "body" }
        var next: Element? = h.nextElementSibling() ?: start?.nextElementSibling()
        var hops = 0
        while (next != null && hops < 20) {
            val list = if (next.tagName() == "ul" || next.tagName() == "ol") next else next.selectFirst("ul, ol")
            if (list != null) {
                val lines = list.select("li").map { clean(it.text()) }.filter { it.isNotEmpty() }
                return recipe(lines, Method.HEADING)
            }
            if (next.tagName().matches(Regex("h[1-6]"))) return null
            next = next.nextElementSibling()
            hops++
        }
        return null
    }

    private fun pageText(doc: Document): String? {
        doc.select("script, style, noscript, nav, footer, header, aside, form").remove()
        val blocks = doc.body()?.select("h1, h2, h3, h4, p, li")?.map { it.text().trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val text = blocks.distinct().joinToString("\n")
        return text.take(MAX_PAGE_TEXT).ifBlank { null }
    }

    private fun recipe(
        lines: List<String>,
        method: Method,
    ): ExtractedRecipe? =
        lines.filter { it.isNotEmpty() }.take(MAX_LINES).takeIf { ls -> ls.any { !it.endsWith(":") } }?.let {
            ExtractedRecipe(null, null, it, method)
        }

    /** HTML tags and entities (double-encoded too) out; whitespace collapsed; at most 500 characters. */
    fun clean(s: String): String {
        var t = s
        repeat(2) {
            if (t.contains('<') || t.contains('&')) t = Jsoup.parseBodyFragment(t).text()
        }
        if (t.contains('&')) t = Parser.unescapeEntities(t, false)
        val sb = StringBuilder()
        var space = false
        for (c in t) {
            if (c.isWhitespace() || c == ' ') {
                space = sb.isNotEmpty()
            } else {
                if (space) sb.append(' ')
                space = false
                sb.append(c)
            }
        }
        return sb.toString().take(MAX_LINE)
    }
}
