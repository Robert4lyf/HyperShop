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
}
