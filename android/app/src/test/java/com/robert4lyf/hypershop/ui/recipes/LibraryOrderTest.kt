package com.robert4lyf.hypershop.ui.recipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T.3: the orders of "Saved", ties included. Step AA: "Most used" is the default. */
class LibraryOrderTest {
    private fun r(
        key: String,
        title: String = key,
        addedAt: Long = 0,
        rating: Int? = null,
        added: Int = 0,
        lastAdded: Long? = null,
    ) = RecipeFacts(key, title, addedAt, rating, added, lastAdded)

    private fun keys(
        rows: List<RecipeFacts>,
        sort: LibrarySort,
    ) = LibraryOrder.sort(rows, sort) { it }.map { it.key }

    private val rows =
        listOf(
            r("dal", "Red lentil dal", addedAt = 100, rating = 4, added = 2, lastAdded = 900),
            r("chilli", "Weeknight chilli", addedAt = 300, rating = 5, added = 2, lastAdded = 950),
            r("pie", "fish pie", addedAt = 200, rating = null, added = 9, lastAdded = 500),
            r("soup", "Soup", addedAt = 400, rating = 3),
            r("stew", "Stew", addedAt = 50, rating = 5),
        )

    @Test
    fun recentlySavedIsNewestFirst() = assertEquals(listOf("soup", "chilli", "pie", "dal", "stew"), keys(rows, LibrarySort.RECENT))

    @Test
    fun highestRatedPutsUnratedLast() = assertEquals(listOf("chilli", "stew", "dal", "soup", "pie"), keys(rows, LibrarySort.RATING))

    @Test
    fun mostUsedIsMostOftenThenMostLatelyThenSavedAndNeverAddedLast() =
        // The pie most often; the chilli and the dal twice each, the chilli more lately; never added: newest saved first.
        assertEquals(listOf("pie", "chilli", "dal", "soup", "stew"), keys(rows, LibrarySort.MOST_USED))

    @Test
    fun mostUsedIsTheDefault() = assertEquals(LibrarySort.MOST_USED, RecipesUi().sort)

    @Test
    fun alphabeticalIgnoresCase() = assertEquals(listOf("pie", "dal", "soup", "stew", "chilli"), keys(rows, LibrarySort.NAME))

    @Test
    fun equalRecipesKeepOnePredictableOrder() {
        // Same rating: the newer first. Same everything but the key: the key decides, in any input order.
        val tied = listOf(r("b", "Same", 5, 4), r("a", "Same", 5, 4), r("c", "Older", 1, 4))
        assertEquals(listOf("a", "b", "c"), keys(tied, LibrarySort.RATING))
        assertEquals(listOf("a", "b", "c"), keys(tied.reversed(), LibrarySort.RATING))
        assertEquals(keys(tied, LibrarySort.MOST_USED), keys(tied.shuffled(java.util.Random(7)), LibrarySort.MOST_USED))
    }

    @Test
    fun highlyRatedIsFourOrFive() {
        assertTrue(LibraryOrder.highlyRated(4))
        assertTrue(LibraryOrder.highlyRated(5))
        assertFalse(LibraryOrder.highlyRated(3))
        assertFalse(LibraryOrder.highlyRated(null))
    }

    @Test
    fun aLargeLibrarySortsTheSameWayEveryTime() {
        val rnd = java.util.Random(42)
        val big =
            (0 until 2000).map {
                r(
                    "k$it",
                    "Recipe ${rnd.nextInt(300)}",
                    rnd.nextInt(500).toLong(),
                    rnd.nextInt(6).takeIf { it > 0 },
                    rnd.nextInt(4),
                    rnd.nextInt(3).takeIf { it > 0 }?.toLong(),
                )
            }
        for (s in LibrarySort.entries) {
            val once = keys(big, s)
            assertEquals(s.name, once, keys(big.shuffled(rnd), s))
            assertEquals(2000, once.toSet().size)
        }
    }
}
