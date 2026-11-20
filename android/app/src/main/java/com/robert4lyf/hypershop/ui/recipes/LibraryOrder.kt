package com.robert4lyf.hypershop.ui.recipes

/** How "Saved" is ordered. Step AA: "Most used" first, and the default. */
enum class LibrarySort(
    val label: String,
) {
    /** Added to the list most often, then most lately, then saved most recently. */
    MOST_USED("Most used"),
    RECENT("Recently saved"),
    RATING("Highest rated"),
    NAME("A–Z"),
}

/** What ordering a saved recipe needs to know about it. */
data class RecipeFacts(
    val key: String,
    val title: String,
    /** When it was saved (or last put on the list before Step Z): the library's own date. */
    val addedAt: Long,
    val rating: Int?,
    /** How many times it has gone on the list (its planned meals). */
    val timesAdded: Int = 0,
    /** When it last went on the list; null when it never has. */
    val lastAddedToList: Long? = null,
)

/**
 * The one ordering of saved recipes. Every order ends in the same tie-breaks — the most recently saved, then the title,
 * then the key — so equal recipes never swap places from one frame to the next. Ordering is the screen's only: nothing
 * it decides is stored or changes what is planned or bought.
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

    /** Step AA: added most often, then most lately (never added sorts last), then saved most recently. */
    private val mostUsed =
        compareByDescending<RecipeFacts> { it.timesAdded }
            .thenByDescending { it.lastAddedToList ?: Long.MIN_VALUE }
            .then(newestFirst)

    fun <T> sort(
        rows: List<T>,
        sort: LibrarySort,
        facts: (T) -> RecipeFacts,
    ): List<T> {
        val c =
            when (sort) {
                LibrarySort.MOST_USED -> mostUsed
                LibrarySort.RECENT -> newestFirst
                LibrarySort.RATING -> byRating
                LibrarySort.NAME -> byName
            }
        return rows.sortedWith(compareBy(c) { facts(it) })
    }

    fun highlyRated(rating: Int?): Boolean = (rating ?: 0) >= HIGHLY_RATED
}
