package com.robert4lyf.hypershop.core.sync.rules

import com.robert4lyf.hypershop.core.sync.SnapAisle
import com.robert4lyf.hypershop.core.sync.SnapItem
import com.robert4lyf.hypershop.core.sync.SnapshotPage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** KNOWN_ISSUES #22: a page from another build of the same listRev voids the pages held so far. */
class WatchSyncModelBuildTest {
    private fun page(
        page: Int,
        build: Int,
        items: List<SnapItem>,
    ) = SnapshotPage(
        epoch = 1,
        listRev = 5,
        ackedThrough = 0,
        ackExceptions = emptyList(),
        page = page,
        pages = 2,
        aisles = if (page == 0) listOf(SnapAisle("PRODUCE", "Produce", 2, false)) else emptyList(),
        aisleBase = 0,
        items = items,
        more = 0,
        build = build,
    )

    private fun item(id: Int) = SnapItem(id, "item $id", "", 0, false, 1)

    @Test
    fun pagesOfTwoBuildsAreNeverAssembledIntoOneList() {
        val w = WatchSyncModel(installId = 1, epoch = 1)
        // Build A: items 1–2 on page 0, 3–4 on page 1. Page 1 is lost. Build B, later, slices 1–3 / 4.
        w.receive(page(0, build = 100, items = listOf(item(1), item(2))))
        w.receive(page(1, build = 200, items = listOf(item(4))))
        assertTrue(w.items.isEmpty(), "a page of build B must not complete build A's snapshot")
        assertEquals(0, w.listRev)
        w.receive(page(0, build = 200, items = listOf(item(1), item(2), item(3))))
        assertEquals(setOf(1, 2, 3, 4), w.items.keys)
        assertEquals(5, w.listRev)
    }

    @Test
    fun pagesThatDoNotSayTheirBuildStillAssemble() {
        val w = WatchSyncModel(installId = 1, epoch = 1)
        w.receive(page(0, build = 0, items = listOf(item(1))))
        w.receive(page(1, build = 0, items = listOf(item(2))))
        assertEquals(setOf(1, 2), w.items.keys)
    }
}
