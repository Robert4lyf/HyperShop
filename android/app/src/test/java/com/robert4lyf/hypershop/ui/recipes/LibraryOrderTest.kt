package com.robert4lyf.hypershop.ui.recipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T.3: the orders of "Saved" and of the planner's picks, ties included. */
class LibraryOrderTest {
    private fun r(
        key: String,
        title: String = key,
        addedAt: Long = 0,
        rating: Int? = null,
        planned: Int = 0,
    ) = RecipeFacts(key, title, addedAt, rating, planned)

    private fun keys(
        rows: List<RecipeFacts>,
        sort: LibrarySort,
    ) = LibraryOrder.sort(rows, sort) { it }.map { it.key }

    private val rows =
        listOf(
            r("dal", "Red lentil dal", addedAt = 100, rating = 4, planned = 6),
            r("chilli", "Weeknight chilli", addedAt = 300, rating = 5, planned = 2),
            r("pie", "fish pie", addedAt = 200, rating = null, planned = 9),
            r("soup", "Soup", addedAt = 400, rating = 3, planned = 0),
        )

    @Test
    fun recentlyAddedIsNewestFirst() = assertEquals(listOf("soup", "chilli", "pie", "dal"), keys(rows, LibrarySort.RECENT))

    @Test
    fun highestRatedPutsUnratedLast() = assertEquals(listOf("chilli", "dal", "soup", "pie"), keys(rows, LibrarySort.RATING))

    @Test
    fun mostPlannedThenBestRated() = assertEquals(listOf("pie", "dal", "chilli", "soup"), keys(rows, LibrarySort.PLANNED))

    @Test
    fun alphabeticalIgnoresCase() = assertEquals(listOf("pie", "dal", "soup", "chilli"), keys(rows, LibrarySort.NAME))

    @Test
    fun equalRecipesKeepOnePredictableOrder() {
        // Same rating: the newer first. Same everything but the key: the key decides, in any input order.
        val tied = listOf(r("b", "Same", 5, 4), r("a", "Same", 5, 4), r("c", "Older", 1, 4))
        assertEquals(listOf("a", "b", "c"), keys(tied, LibrarySort.RATING))
        assertEquals(listOf("a", "b", "c"), keys(tied.reversed(), LibrarySort.RATING))
        assertEquals(keys(tied, LibrarySort.PLANNED), keys(tied.shuffled(java.util.Random(7)), LibrarySort.PLANNED))
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
                r("k$it", "Recipe ${rnd.nextInt(300)}", rnd.nextInt(500).toLong(), rnd.nextInt(6).takeIf { it > 0 }, rnd.nextInt(4))
            }
        for (s in LibrarySort.entries) {
            val once = keys(big, s)
            assertEquals(s.name, once, keys(big.shuffled(rnd), s))
            assertEquals(2000, once.toSet().size)
        }
    }
}
