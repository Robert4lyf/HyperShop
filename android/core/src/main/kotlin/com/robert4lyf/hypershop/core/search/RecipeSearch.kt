package com.robert4lyf.hypershop.core.search

import com.robert4lyf.hypershop.core.catalog.CatalogKey

/**
 * "Search your recipes": what you have in ("mushrooms", "spinach") or fancy
 * ("mushroom"). A term matches a recipe's title or any of its ingredients,
 * as whole words with plurals folded, so "mushroom" finds chestnut, oyster and
 * wild mushrooms but "pea" doesn't find "peanut". Recipes using every term come
 * first, then those using more of them; otherwise the library's own order stays.
 */
object RecipeSearch {
    /** One ingredient: how to show it, and every name it answers to (its line's and the catalogue's). */
    data class Ingredient(
        val display: String,
        val names: List<String>,
    )

    data class Recipe(
        val id: Long,
        val title: String,
        val ingredients: List<Ingredient>,
    )

    data class Hit(
        val id: Long,
        /** How many of the terms it matches. */
        val matched: Int,
        /** The ingredients that matched, as shown. */
        val uses: List<String>,
        /** A term matched the title (and perhaps no ingredient). */
        val inTitle: Boolean,
    )

    fun search(
        recipes: List<Recipe>,
        terms: List<String>,
    ): List<Hit> {
        val queries = terms.map(::words).filter { it.isNotEmpty() }.distinct()
        if (queries.isEmpty()) return emptyList()
        return recipes
            .mapNotNull { r ->
                val title = words(r.title)
                val names = r.ingredients.map { i -> i to i.names.map(::words) }
                var matched = 0
                var inTitle = false
                val uses = LinkedHashSet<String>()
                for (q in queries) {
                    val byIngredient = names.filter { (_, ns) -> ns.any { contains(it, q) } }.map { it.first.display }
                    val byTitle = contains(title, q)
                    if (byIngredient.isNotEmpty() || byTitle) matched++
                    uses += byIngredient
                    inTitle = inTitle || byTitle
                }
                if (matched == 0) null else Hit(r.id, matched, uses.toList(), inTitle)
            }.sortedByDescending { it.matched } // stable: ties keep the library's order
    }

    /** Lower case, accents folded, plurals folded, split into words. */
    fun words(s: String): List<String> = CatalogKey.of(s).split(' ', '-', '\'').filter { it.isNotEmpty() }.map(::stem)

    /** [q] as consecutive words of [text]. */
    private fun contains(
        text: List<String>,
        q: List<String>,
    ): Boolean {
        if (q.size > text.size) return false
        for (i in 0..text.size - q.size) if (q.indices.all { text[i + it] == q[it] }) return true
        return false
    }

    /** berries → berry, tomatoes → tomato, peaches → peach, onions → onion; "cress", "hummus" stay. */
    fun stem(w: String): String =
        when {
            w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
            w.length > 4 && w.endsWith("oes") -> w.dropLast(2)
            w.length > 4 && (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes") || w.endsWith("sses")) -> w.dropLast(2)
            w.length > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") -> w.dropLast(1)
            else -> w
        }
}
