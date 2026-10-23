package com.robert4lyf.hypershop.extract

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ExtractorTest {
    private val dir = File("../../testdata/pages")

    private fun page(name: String) = Extractor.extract(File(dir, name).readText())

    private fun ld(json: String) = Extractor.extract("<html><head><script type=\"application/ld+json\">$json</script></head></html>")

    @Test
    fun liveFixtures() {
        val expected =
            mapOf(
                "bbcgoodfood-chocolate-cake.html" to Method.JSON_LD,
                "bbcgoodfood-bolognese.html" to Method.JSON_LD,
                "bbcgoodfood-chilli.html" to Method.JSON_LD,
                "nytcooking-cookies.html" to Method.JSON_LD,
                "bonappetit-cookies.html" to Method.JSON_LD,
                "delish-pot-pie.html" to Method.JSON_LD,
                "pioneerwoman-potato-soup.html" to Method.JSON_LD,
                "tasty-pinchofyum-cookies.html" to Method.TASTY,
                "wprm-recipetineats-chasseur.html" to Method.WPRM,
                "wprm-loveandlemons-hummus.html" to Method.WPRM,
                "wprm-skinnytaste-turkey-chili.html" to Method.WPRM,
                "wprm-cookingclassy-noodle-soup.html" to Method.WPRM,
            )
        for ((f, m) in expected) {
            val e = page(f)
            assertEquals(m, e.method, f)
            val r = e.recipes.single()
            assertTrue(r.lines.count { !it.endsWith(":") } >= 5, "$f: ${r.lines}")
            assertNotNull(r.title, f)
            assertTrue(r.lines.none { it.contains('<') || it.contains("&amp;") }, f)
        }
        assertEquals(14, page("bbcgoodfood-chocolate-cake.html").recipes.single().servings)
        assertEquals("200g golden caster sugar", page("bbcgoodfood-chocolate-cake.html").recipes.single().lines.first())
        // WPRM group names become section headers.
        assertTrue(page("wprm-recipetineats-chasseur.html").recipes.single().lines.any { it.endsWith(":") })
    }

    @Test
    fun syntheticFixtures() {
        val micro = page("synthetic/synthetic-microdata.html")
        assertEquals(Method.MICRODATA, micro.method)
        assertEquals(5, micro.recipes.single().lines.size)
        assertEquals(4, micro.recipes.single().servings)
        assertEquals("Red lentil soup", micro.recipes.single().title)
        val blocked = page("synthetic/synthetic-blocked-sallysbaking.html")
        assertEquals(Method.WPRM, blocked.method)
        assertEquals(
            listOf(
                "Cookie dough:",
                "2 1/4 cups all-purpose flour (281g)",
                "1 teaspoon baking soda",
                "To finish:",
                "1 1/4 cups chocolate chips",
            ),
            blocked.recipes.single().lines,
        )
        assertEquals(24, blocked.recipes.single().servings)
        val heading = page("synthetic/synthetic-heading.html")
        assertEquals(Method.HEADING, heading.method)
        assertEquals(4, heading.recipes.single().lines.size)
        val text = page("synthetic/synthetic-text-only.html")
        assertEquals(Method.PAGE_TEXT, text.method)
        assertTrue(text.pageText!!.contains("two cloves of garlic"))
        assertEquals(Method.MANUAL, Extractor.extract("<html><body></body></html>").method)
    }

    // ---- JSON-LD quirks, one test each ---------------------------------------------------

    @Test
    fun graph() =
        assertEquals(
            listOf("1 egg"),
            ld(
                """{"@context":"x","@graph":[{"@type":"WebPage"},{"@type":"Recipe","name":"E","recipeIngredient":["1 egg"]}]}""",
            ).recipes.single().lines,
        )

    @Test
    fun typeAsArray() = assertEquals(1, ld("""{"@type":["Recipe","NewsArticle"],"recipeIngredient":["1 egg"]}""").recipes.size)

    @Test
    fun typeAsSchemaIri() {
        assertEquals(1, ld("""{"@type":"http://schema.org/Recipe","recipeIngredient":["1 egg"]}""").recipes.size)
        assertEquals(1, ld("""{"@type":"https://schema.org/Recipe","recipeIngredient":["1 egg"]}""").recipes.size)
        assertEquals(0, ld("""{"@type":"http://schema.org/NotRecipe","recipeIngredient":["1 egg"]}""").recipes.size)
    }

    @Test
    fun wprmNotesAreSetApartFromTheName() {
        fun li(notes: String) =
            """<li class="wprm-recipe-ingredient"><span class="wprm-recipe-ingredient-amount">500</span>
               <span class="wprm-recipe-ingredient-unit">g</span><span class="wprm-recipe-ingredient-name">chicken</span>
               <span class="wprm-recipe-ingredient-notes">$notes</span></li>"""
        val html =
            """<html><body><div class="wprm-recipe-container"><div class="wprm-recipe-ingredients-container">
               <ul>${li("cut into chunks")}${li(", diced")}${li("(about 2)")}</ul></div></div></body></html>"""
        assertEquals(
            listOf("500 g chicken, cut into chunks", "500 g chicken, diced", "500 g chicken (about 2)"),
            Extractor.extract(html).recipes.single().lines,
        )
    }

    @Test
    fun sectionsInsideIngredientsKeepTheirLines() {
        val json =
            """{"@type":"Recipe","recipeIngredient":[
              {"@type":"HowToSection","name":"For the sauce","itemListElement":["400 g tomatoes",{"@type":"HowToStep","text":"1 onion"}]},
              "2 eggs"]}"""
        assertEquals(listOf("For the sauce:", "400 g tomatoes", "1 onion", "2 eggs"), ld(json).recipes.single().lines)
    }

    @Test
    fun severalRecipesOnOnePage() {
        val e =
            ld(
                """[{"@type":"Recipe","name":"A","recipeIngredient":["1 egg"]},""" +
                    """{"@type":"Recipe","name":"B","recipeIngredient":["2 eggs"]}]""",
            )
        assertEquals(listOf("A", "B"), e.recipes.map { it.title })
        val many = "[" + (1..15).joinToString(",") { """{"@type":"Recipe","recipeIngredient":["$it egg"]}""" } + "]"
        assertEquals(10, ld(many).recipes.size, "at most 10 recipes a page")
    }

    @Test
    fun ingredientsAsOneStringOrTheOldKey() {
        assertEquals(
            listOf("1 egg", "2 tbsp milk"),
            ld("""{"@type":"Recipe","recipeIngredient":"1 egg\n2 tbsp milk"}""").recipes.single().lines,
        )
        assertEquals(listOf("1 egg"), ld("""{"@type":"Recipe","ingredients":["1 egg"]}""").recipes.single().lines)
    }

    @Test
    fun nestedArrays() =
        assertEquals(
            listOf("1 egg", "2 tbsp milk", "salt"),
            ld("""{"@type":"Recipe","recipeIngredient":[["1 egg",["2 tbsp milk"]],"salt"]}""").recipes.single().lines,
        )

    @Test
    fun htmlEntitiesIncludingDoubleEncoded() =
        assertEquals(
            listOf("1 & ½ cups flour", "2 \"large\" eggs", "Crème fraîche"),
            ld(
                """{"@type":"Recipe","recipeIngredient":["1 &amp;amp; &frac12; cups flour",""" +
                    """"2 &quot;large&quot; eggs","Cr&egrave;me fra&#238;che"]}""",
            ).recipes.single().lines,
        )

    @Test
    fun htmlTagsInsideStrings() =
        assertEquals(
            listOf("1 cup flour (sifted)"),
            ld("""{"@type":"Recipe","recipeIngredient":["<strong>1 cup</strong> flour <em>(sifted)</em>"]}""").recipes.single().lines,
        )

    @Test
    fun lenientJson() {
        val sloppy = "{\"@type\":\"Recipe\", // a comment\n \"recipeIngredient\":[\"1 egg\",\"2 tbsp\nmilk\",],}"
        assertEquals(listOf("1 egg", "2 tbsp milk"), ld(sloppy).recipes.single().lines)
    }

    @Test
    fun recipeYieldForms() {
        assertEquals(4, Extractor.servings(JsonPrimitive(4)))
        assertEquals(4, Extractor.servings(JsonPrimitive("4")))
        assertEquals(6, Extractor.servings(JsonPrimitive("6 servings")))
        assertEquals(4, Extractor.servings(JsonPrimitive("Serves 4-6")))
        assertEquals(
            12,
            Extractor.servings(
                buildJsonArray {
                    add(JsonPrimitive("12"))
                    add(JsonPrimitive("12 muffins"))
                },
            ),
        )
        assertEquals(2, Extractor.servings(buildJsonObject { put("value", JsonPrimitive("2")) }))
        assertNull(Extractor.servings(JsonPrimitive("a lot")))
        assertNull(Extractor.servings(JsonPrimitive("0")))
    }

    // ---- limits and hostile input --------------------------------------------------------

    @Test
    fun hostileFixturesDoNotHurt() {
        val t0 = System.nanoTime()
        for (f in listOf(
            "hostile/hostile-deep-array.html",
            "hostile/hostile-50k-ingredients.html",
            "hostile/hostile-oversized-block.html",
        )) {
            val e = page(f)
            assertTrue(e.recipes.all { it.lines.size <= Extractor.MAX_LINES }, f)
        }
        assertTrue((System.nanoTime() - t0) / 1e9 < 5)
        assertTrue(page("hostile/hostile-deep-array.html").recipes.isEmpty(), "nesting over 64 is skipped")
        assertTrue(page("hostile/hostile-oversized-block.html").recipes.isEmpty(), "blocks over 512 KB are skipped")
    }

    @Test
    fun lineCaps() {
        val lines = (1..400).joinToString(",") { "\"$it ${"x".repeat(600)}\"" }
        val r = ld("""{"@type":"Recipe","recipeIngredient":[$lines]}""").recipes.single()
        assertEquals(300, r.lines.size)
        assertTrue(r.lines.all { it.length <= 500 })
    }

    @Test
    fun depthScanIgnoresBracketsInStrings() {
        assertEquals(2, Extractor.depth("""{"a":["[[[[{{{{"]}"""))
    }

    @Test
    fun oneStringOfLinesIsCappedBeforeCleaning() {
        val lines = (1..1000).joinToString("\\n") { "$it egg" }
        val r = ld("""{"@type":"Recipe","recipeIngredient":"$lines"}""").recipes.single()
        assertEquals(300, r.lines.size)
        assertEquals("1 egg", r.lines.first())
    }

    @Test
    fun aFourDigitYieldIsNotReadAsAHundredServings() {
        assertNull(Extractor.servings(JsonPrimitive("1000 ml")))
        assertNull(Extractor.servings(JsonPrimitive("Makes 2500")))
        assertEquals(12, Extractor.servings(JsonPrimitive("Makes 12 muffins")))
    }

    @Test
    fun aBareJsonLdDocumentIsReadLikeAScriptBlock() {
        val json = """{"@type":"Recipe","name":"Eggs","recipeIngredient":["1 egg </script>","2 eggs"]}"""
        val r = Extractor.extract(Extractor.wrapLdJson(json))
        val recipe = r.recipes.single()
        assertEquals(2, recipe.lines.size)
        assertTrue(recipe.lines[0].startsWith("1 egg"))
    }

    @Test
    fun theRecipesOwnPhotoIsFoundAndMadeAbsolute() {
        fun page(
            ld: String,
            head: String = "",
        ) = "<html><head>$head<script type=\"application/ld+json\">$ld</script></head><body></body></html>"
        val lines = "\"recipeIngredient\":[\"2 onions\"]"
        val base = "https://example.com/recipes/chilli"
        // A plain URL, a list, an ImageObject, and a relative one.
        assertEquals(
            "https://cdn.example.com/a.jpg",
            Extractor.extract(page("{\"@type\":\"Recipe\",$lines,\"image\":\"https://cdn.example.com/a.jpg\"}"), base).image,
        )
        assertEquals(
            "https://cdn.example.com/b.jpg",
            Extractor.extract(
                page("{\"@type\":\"Recipe\",$lines,\"image\":[\"https://cdn.example.com/b.jpg\",\"https://cdn.example.com/c.jpg\"]}"),
                base,
            ).image,
        )
        assertEquals(
            "https://cdn.example.com/d.jpg",
            Extractor.extract(
                page("{\"@type\":\"Recipe\",$lines,\"image\":{\"@type\":\"ImageObject\",\"url\":\"https://cdn.example.com/d.jpg\"}}"),
                base,
            ).image,
        )
        assertEquals(
            "https://example.com/img/e.jpg",
            Extractor.extract(page("{\"@type\":\"Recipe\",$lines,\"image\":\"/img/e.jpg\"}"), base).image,
        )
        // No recipe image: the page's share image.
        val og = "<meta property=\"og:image\" content=\"https://example.com/og.jpg\">"
        assertEquals("https://example.com/og.jpg", Extractor.extract(page("{\"@type\":\"Recipe\",$lines}", og), base).image)
        // Never a script, a data: URL or a file.
        for (bad in listOf("javascript:alert(1)", "data:image/png;base64,AAAA", "file:///etc/passwd")) {
            assertNull(Extractor.extract(page("{\"@type\":\"Recipe\",$lines,\"image\":\"$bad\"}"), base).image, bad)
        }
    }
}
