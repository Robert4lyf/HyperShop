package com.robert4lyf.hypershop.core.search

import com.robert4lyf.hypershop.core.search.RecipeSearch.Ingredient
import com.robert4lyf.hypershop.core.search.RecipeSearch.Recipe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecipeSearchTest {
    private fun ing(vararg names: String) = Ingredient(names.first(), names.toList())

    private val risotto =
        Recipe(
            1,
            "Mushroom risotto",
            listOf(ing("chestnut mushroom", "chestnut mushrooms", "cremini"), ing("risotto rice"), ing("parmesan")),
        )
    private val stroganoff =
        Recipe(2, "Beef stroganoff", listOf(ing("beef"), ing("oyster mushrooms", "oyster mushroom"), ing("soured cream")))
    private val curry = Recipe(3, "Spinach and chickpea curry", listOf(ing("spinach", "baby spinach"), ing("chickpeas"), ing("onion")))
    private val satay = Recipe(4, "Satay noodles", listOf(ing("peanut butter"), ing("egg noodles")))
    private val all = listOf(risotto, stroganoff, curry, satay)

    @Test
    fun anIngredientFindsEveryDishWithIt() {
        val hits = RecipeSearch.search(all, listOf("mushroom"))
        assertEquals(listOf(1L, 2L), hits.map { it.id })
        assertEquals(listOf("chestnut mushroom"), hits[0].uses)
        assertTrue(hits[0].inTitle)
        assertEquals(listOf("oyster mushrooms"), hits[1].uses)
    }

    @Test
    fun wholeWordsOnlyAndPluralsFolded() {
        assertEquals(emptyList<Long>(), RecipeSearch.search(all, listOf("pea")).map { it.id })
        assertEquals(listOf(3L), RecipeSearch.search(all, listOf("Chickpea")).map { it.id })
        assertEquals(listOf(3L), RecipeSearch.search(all, listOf("onions")).map { it.id })
        assertEquals("tomato", RecipeSearch.stem("tomatoes"))
        assertEquals("berry", RecipeSearch.stem("berries"))
        assertEquals("hummus", RecipeSearch.stem("hummus"))
    }

    @Test
    fun severalThingsYouHaveRankRecipesUsingMoreOfThemFirst() {
        val hits = RecipeSearch.search(all, listOf("onion", "spinach", "mushrooms"))
        assertEquals(3L, hits.first().id)
        assertEquals(2, hits.first().matched)
        assertEquals(listOf(3L, 1L, 2L), hits.map { it.id })
    }

    @Test
    fun aDishByItsTitle() {
        val hits = RecipeSearch.search(all, listOf("curry"))
        assertEquals(listOf(3L), hits.map { it.id })
        assertTrue(hits.single().uses.isEmpty())
        assertTrue(hits.single().inTitle)
        assertEquals(emptyList<RecipeSearch.Hit>(), RecipeSearch.search(all, listOf(" ")))
    }

    @Test
    fun theWordBeingTypedMatchesWordStarts() {
        // Half a word finds the dish while typing, in the title or an ingredient.
        assertEquals(listOf(1L, 2L), RecipeSearch.search(all, listOf("mush"), typing = true).map { it.id })
        assertEquals(listOf(4L), RecipeSearch.search(all, listOf("noo"), typing = true).map { it.id })
        assertEquals(listOf(2L), RecipeSearch.search(all, listOf("soured cr"), typing = true).map { it.id })
        // A finished term (a chip) is still whole words: "pea" is not "peanut".
        assertEquals(emptyList<Long>(), RecipeSearch.search(all, listOf("mush")).map { it.id })
        assertEquals(listOf(3L), RecipeSearch.search(all, listOf("chickpea", "spin"), typing = true).map { it.id })
        assertEquals(emptyList<Long>(), RecipeSearch.search(all, listOf("pea", "zzz"), typing = true).map { it.id })
    }
}
