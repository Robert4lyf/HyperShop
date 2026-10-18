package com.robert4lyf.hypershop.importing

import com.robert4lyf.hypershop.core.model.UnitLocale
import org.junit.Assert.assertEquals
import org.junit.Test

/** R2.2: which cup a recipe means. */
class LocaleForTest {
    @Test
    fun aLineGivingCupsAndMlSettlesTheCupSize() {
        // RecipeTin Eats: Australian 250 ml cups, mostly written in cups.
        val tin =
            listOf(
                "4 tbsp unsalted butter",
                "6 tbsp / 1/2 cup plain flour",
                "2 cups milk (500ml)",
                "2 cups tomato passata (500ml) (Note 1)",
                "3 cups dried ziti / penne pasta (uncooked)",
                "2 cups shredded cooked chicken",
                "1 cup shredded cheddar cheese",
            )
        assertEquals(UnitLocale.METRIC, Importer.localeFor("https://www.recipetineats.com/x/", tin))
        assertEquals(UnitLocale.US, Importer.localeFor(null, listOf("1 cup (240ml) milk", "2 cups flour")))
    }

    @Test
    fun otherwiseTheOldRulesApply() {
        assertEquals(UnitLocale.US, Importer.localeFor(null, listOf("2 cups flour", "1 cup sugar")))
        assertEquals(UnitLocale.METRIC, Importer.localeFor(null, listOf("200g flour", "100 ml milk", "1 egg")))
        assertEquals(UnitLocale.UK, Importer.localeFor("https://www.bbcgoodfood.com/r", listOf("2 cups flour")))
    }

    @Test
    fun theHostIgnoresThePort() {
        assertEquals(UnitLocale.UK, Importer.localeFor("https://www.bbc.co.uk:443/food/recipes/x", listOf("2 cups flour")))
        assertEquals(UnitLocale.UK, Importer.localeFor("https://user@example.co.uk./r?x=1", listOf("2 cups flour")))
    }

    @Test
    fun thousandsInMlAreRead() {
        // 4 cups = 1,000 ml is a 250 ml cup; read as "1 ml" it settled nothing.
        assertEquals(UnitLocale.METRIC, Importer.localeFor(null, listOf("4 cups stock (1,000ml)", "1 cup rice", "2 cups peas")))
    }

    @Test
    fun theMetricShareIsOfTheLinesLookedAt() {
        // 20 metric lines in the first 50, then 60 more lines in cups: 20 of 50 is metric.
        val lines = List(20) { "100 g flour $it" } + List(30) { "1 egg $it" } + List(60) { "1 cup milk $it" }
        assertEquals(UnitLocale.METRIC, Importer.localeFor(null, lines))
    }
}
