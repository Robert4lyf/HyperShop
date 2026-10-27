package com.robert4lyf.hypershop.ui.recipes

/** How "Saved" is ordered. */
enum class LibrarySort(
    val label: String,
) {
    RECENT("Recently added"),
    RATING("Highest rated"),

    /** Cooked most ("Cooked it" on a meal), then planned most: your usual. */
    PLANNED("Your usual"),
    NAME("A–Z"),
}

/** What ordering a saved recipe needs to know about it. */
data class RecipeFacts(
    val key: String,
    val title: String,
    val addedAt: Long,
    val rating: Int?,
    val timesPlanned: Int,
    val timesCooked: Int = 0,
)

/**
 * The one ordering of saved recipes, for "Saved" and for picking a week's meals. Every
 * order ends in the same tie-breaks — the most recently added, then the title, then the
 * key — so equal recipes never swap places from one frame to the next. Ordering is the
 * screen's only: nothing it decides is stored or changes what is planned or bought.
 */
object LibraryOrder {
    /** "4★ & up": the recipes you rated highly. */
    const val HIGHLY_RATED = 4

    private val newestFirst =
        compareByDescending<RecipeFacts> { it.addedAt }
            .thenBy { it.title.lowercase() }
            .thenBy { it.key }

    private val byRating = compareByDescending<RecipeFacts> { it.rating ?: 0 }.then(newestFirst)

    private val byName = compareBy<RecipeFacts> { it.title.lowercase() }.thenBy { it.title }.thenBy { it.key }

    /** Cooked most, then planned most, then rated best, then newest: "your usual", what the planner offers first. */
    private val usual =
        compareByDescending<RecipeFacts> { it.timesCooked }
            .thenByDescending { it.timesPlanned }
            .thenByDescending { it.rating ?: 0 }
            .then(newestFirst)

    fun <T> sort(
        rows: List<T>,
        sort: LibrarySort,
        facts: (T) -> RecipeFacts,
    ): List<T> {
        val c =
            when (sort) {
                LibrarySort.RECENT -> newestFirst
                LibrarySort.RATING -> byRating
                LibrarySort.PLANNED -> usual
                LibrarySort.NAME -> byName
            }
        return rows.sortedWith(compareBy(c) { facts(it) })
    }

    fun highlyRated(rating: Int?): Boolean = (rating ?: 0) >= HIGHLY_RATED
}
