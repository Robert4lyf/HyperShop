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
    /** The recipe's own photo, absolute https (or http) URL: JSON-LD `image`, microdata, then `og:image`. */
    val image: String? = null,
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
    private val METRIC_UNITS = setOf("g", "kg", "ml", "l")
    private val PLAIN_NUMBER = Regex("""\d{1,6}(\.\d{1,3})?""")
    private val OWN_METRIC = Regex("""\d\s*(g|kg|ml|l)\b""", RegexOption.IGNORE_CASE)
    const val MAX_DEPTH = 64
    const val MAX_RECIPES = 10
    const val MAX_LINES = 300
    const val MAX_LINE = 500
    const val MAX_PAGE_TEXT = 30_000
    const val MAX_IMAGE_URL = 2_000

    private val json =
        Json {
            isLenient = true
            ignoreUnknownKeys = true
            @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
            allowTrailingComma = true

            @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
            allowComments = true
        }

    /**
     * A document served as `application/ld+json` (which the fetcher allows), as
     * the HTML [extract] reads: one script block. A `</script>` inside a JSON
     * string would end the block early, so it is escaped as JSON allows.
     */
    fun wrapLdJson(json: String): String {
        val safe = json.replace("</", "<\\/")
        return "<html><head><script type=\"application/ld+json\">$safe</script></head></html>"
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
        return extractFrom(doc).copy(image = pageImage(doc))
    }

    private fun extractFrom(doc: Document): Extraction {
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
        val metric = wprmMetric(doc)
        var index = 0
        val groups = container.select(".wprm-recipe-ingredient-group")
        for (g in groups.ifEmpty { listOf(container) }) {
            g.selectFirst(
                ".wprm-recipe-group-name",
            )?.text()?.let { clean(it) }?.takeIf { it.isNotEmpty() }?.let { lines += "${it.trimEnd(':')}:" }
            for (li in g.select("li.wprm-recipe-ingredient")) {
                fun part(cls: String) = li.selectFirst(".wprm-recipe-ingredient-$cls")?.text()?.trim()?.ifEmpty { null }
                // V.4: the recipe's own metric figure, from the site's unit switch, when the line has none: written
                // after the amount ("2 (560g) tomatoes"), where the parser reads it as the amount's alternative.
                val own = metric.getOrNull(index++)?.takeIf { (name, _) -> name == part("name")?.let { clean(it).lowercase() } }?.second
                val parts = listOfNotNull(part("amount"), part("unit"), own?.let { "($it)" }, part("name"))
                // A note is set apart from the name ("chicken, cut into chunks"), not glued on
                // ("chicken cut into chunks" would read as a "chicken cut").
                val notes =
                    part("notes")?.let {
                        when {
                            it.startsWith(",") -> it
                            it.startsWith("(") -> " $it"
                            else -> ", $it"
                        }
                    }
                val text = if (parts.isEmpty() && notes == null) li.text() else parts.joinToString(" ") + notes.orEmpty()
                clean(text).takeIf { it.isNotEmpty() }?.let { lines += it }
            }
        }
        return recipe(lines, Method.WPRM)
    }

    /**
     * V.4: WP Recipe Maker's own data for the recipe on the page (`window.wprm_recipes`): per ingredient, in
     * order, its name and the metric amount the site's "Metric" switch shows ("560 g" for "2 tomatoes"), or
     * null. Only an amount in g, kg, ml or l, and only for a line whose own amount has no metric figure in it
     * already. Untrusted like the rest of the page: capped and depth-checked as JSON-LD is, and matched to a
     * line by its name, never by position alone.
     */
    fun wprmMetric(doc: Document): List<Pair<String, String?>> {
        val script = doc.select("script").firstOrNull { "window.wprm_recipes" in it.data() }?.data() ?: return emptyList()
        val start = script.indexOf('{', script.indexOf("window.wprm_recipes")).takeIf { it >= 0 } ?: return emptyList()
        val body = jsonObjectAt(script, start) ?: return emptyList()
        if (body.length > MAX_BLOCK || depth(body) > MAX_DEPTH) return emptyList()
        val recipes = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val recipe = recipes.values.firstOrNull() as? JsonObject ?: return emptyList()
        val ingredients = recipe["ingredients"] as? JsonArray ?: return emptyList()
        return ingredients.mapNotNull { it as? JsonObject }.filter { str(it["type"]) != "group" }.take(MAX_LINES).map { ing ->
            val name = str(ing["name"])?.let { clean(it).lowercase() }.orEmpty()
            val own = "${str(ing["amount"]).orEmpty()} ${str(ing["unit"]).orEmpty()}"
            val converted = ((ing["converted"] as? JsonObject)?.get("2") as? JsonObject)
            val amount = str(converted?.get("amount"))?.trim().orEmpty()
            val unit = str(converted?.get("unit"))?.trim()?.lowercase().orEmpty()
            val ok = unit in METRIC_UNITS && amount.matches(PLAIN_NUMBER) && !OWN_METRIC.containsMatchIn(own)
            name to (if (ok) "$amount$unit" else null)
        }
    }

    /** The JSON object starting at [from] in [s], braces matched outside strings; null if it never closes. */
    private fun jsonObjectAt(
        s: String,
        from: Int,
    ): String? {
        var depth = 0
        var inString = false
        var i = from
        val end = minOf(s.length, from + MAX_BLOCK)
        while (i < end) {
            val c = s[i]
            if (inString) {
                if (c == '\\') {
                    i++
                } else if (c == '"') {
                    inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> if (--depth == 0) return s.substring(from, i + 1)
                }
            }
            i++
        }
        return null
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
                        is JsonPrimitive -> isRecipeType(type.contentOrNull)
                        is JsonArray -> type.any { isRecipeType((it as? JsonPrimitive)?.contentOrNull) }
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

    /** "Recipe", "schema:Recipe" or a full schema.org IRI. */
    private fun isRecipeType(t: String?): Boolean = t != null && t.substringAfterLast('/').substringAfterLast(':') == "Recipe"

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
                // One string holding every line: bounded like the array form, or a 512 KB
                // block of "\n" could queue hundreds of thousands of pieces for cleaning.
                is JsonPrimitive -> e.contentOrNull?.let { s -> flat += s.splitToSequence('\n').take(MAX_LINES - flat.size) }
                is JsonObject -> {
                    // A section ("For the sauce") holds its lines in itemListElement; its name is a
                    // "Header:" line (R8), as for WPRM and Tasty.
                    val items = e["itemListElement"]
                    if (items != null) {
                        str(e["name"])?.let { clean(it).trimEnd(':').trim() }?.takeIf { it.isNotEmpty() }?.let { flat += "$it:" }
                        walk(items, d + 1)
                    } else {
                        str(e["text"] ?: e["name"])?.let { flat += it }
                    }
                }
                else -> Unit
            }
        }
        walk(v, 0)
        return flat.take(MAX_LINES).map { clean(it) }.filter { it.isNotEmpty() }
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
        // The whole number: "1000 ml" is a yield of 1000, not 100 servings; the range check drops it.
        while (j < s.length && s[j].isDigit()) j++
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

    // ---- the recipe's photo -----------------------------------------------------------

    /** The first image the page declares for its recipe; a page's general share image when it declares none. */
    fun pageImage(doc: Document): String? {
        val candidates = ArrayList<String>()
        for (s in doc.select("script[type=application/ld+json]")) {
            val raw = s.data()
            if (raw.length > MAX_BLOCK || depth(raw) > MAX_DEPTH) continue
            val el = runCatching { json.parseToJsonElement(sanitiseJson(raw)) }.getOrNull() ?: continue
            findRecipes(el, 0).firstNotNullOfOrNull { imageOf(it["image"], 0) }?.let { candidates += it }
        }
        doc.select("[itemscope][itemtype~=(?i)schema.org/Recipe] [itemprop=image]").firstOrNull()?.let {
            candidates += it.attr("content").ifBlank { it.attr("src") }.ifBlank { it.attr("href") }
        }
        doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { candidates += it }
        return candidates.firstNotNullOfOrNull { absolute(doc, it) }
    }

    /** schema.org `image`: a URL, an ImageObject (`url`/`contentUrl`), or a list of either. */
    private fun imageOf(
        e: JsonElement?,
        d: Int,
    ): String? =
        when {
            d > 3 || e == null -> null
            e is JsonPrimitive -> e.contentOrNull
            e is JsonArray -> e.firstNotNullOfOrNull { imageOf(it, d + 1) }
            e is JsonObject -> imageOf(e["url"] ?: e["contentUrl"], d + 1)
            else -> null
        }

    /** [url] made absolute against the page (a relative one needs the page's address), http(s) only. */
    private fun absolute(
        doc: Document,
        url: String,
    ): String? {
        val t = url.trim()
        if (t.isEmpty() || t.length > MAX_IMAGE_URL) return null
        val abs =
            runCatching {
                val base = doc.location().ifEmpty { null }
                if (base == null) java.net.URI(t) else java.net.URI(base).resolve(t)
            }.getOrNull() ?: return null
        return abs.toString().takeIf { abs.isAbsolute && (abs.scheme == "https" || abs.scheme == "http") }
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
