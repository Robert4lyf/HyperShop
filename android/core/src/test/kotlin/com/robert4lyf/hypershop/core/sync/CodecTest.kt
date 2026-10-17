package com.robert4lyf.hypershop.core.sync

import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodecTest {
    private val hello = WatchMessage.Hello(epoch = 7, installId = 42, listRev = 3, watchNow = 1_760_000_000, ackedThrough = 5)
    private val ops =
        WatchMessage.Ops(
            7,
            42,
            listOf(WatchOp(6, 10, true, 3, 1_760_000_001), WatchOp(7, 11, false, 3, 1_760_000_002)),
        )

    @Test
    fun watchMessagesRoundTrip() {
        for (m in listOf(hello, ops, WatchMessage.Bye(7, 42))) {
            assertEquals(m, WatchCodec.decode(WatchCodec.encode(m)))
        }
    }

    @Test
    fun keysAreShort() {
        for (k in WatchCodec.encode(ops).keys + WatchCodec.encodePage(page()).keys) assertTrue(k.length <= 3, k)
    }

    @Test
    fun versionMismatchIsDropped() {
        val p = WatchCodec.encode(hello).toMutableMap()
        p[WatchProtocol.Key.VERSION] = 2
        assertNull(WatchCodec.decode(p))
    }

    @Test
    fun hostileInputDecodesToNull() {
        fun withOps(v: Any) = WatchCodec.encode(ops).toMutableMap().apply { put(WatchProtocol.Key.OPS, v) }
        assertNull(WatchCodec.decode(withOps("x")))
        assertNull(WatchCodec.decode(withOps(emptyList<Any>())))
        assertNull(WatchCodec.decode(withOps(List(33) { listOf(it + 1, 1, true, 0, 0L) })))
        assertNull(WatchCodec.decode(withOps(listOf(listOf(1, 1, "yes", 0, 0L)))))
        assertNull(WatchCodec.decode(withOps(listOf(listOf(1, -5, true, 0, 0L)))))
        assertNull(WatchCodec.decode(withOps(listOf(listOf(1, 1, true, 0)))))
        // Not consecutive.
        assertNull(WatchCodec.decode(withOps(listOf(listOf(1, 1, true, 0, 0L), listOf(3, 1, false, 0, 0L)))))
        assertNull(WatchCodec.decode(mapOf("v" to 1, "t" to "hello")))
        assertNull(WatchCodec.decode(mapOf("v" to "1")))
        assertNull(WatchCodec.decode(WatchCodec.encode(hello) + (WatchProtocol.Key.INSTALL_ID to 0)))
        assertNull(WatchCodec.decode(WatchCodec.encode(hello) + (WatchProtocol.Key.TYPE to "drop table")))
        assertNull(WatchCodec.decode(WatchCodec.encode(hello) + (WatchProtocol.Key.LIST_REV to Long.MAX_VALUE)))
    }

    @Test
    fun pageRoundTrips() {
        val p = page()
        assertEquals(p, WatchCodec.decodePage(WatchCodec.encodePage(p)))
    }

    @Test
    fun onlyConnectIqTypesInPayloads() {
        fun check(v: Any?) {
            when (v) {
                is String, is Int, is Long, is Boolean -> Unit
                is List<*> -> v.forEach(::check)
                is Map<*, *> ->
                    v.forEach { (k, x) ->
                        check(k)
                        check(x)
                    }
                else -> throw AssertionError("not a Connect IQ type: $v")
            }
        }
        check(WatchCodec.encodePage(page()))
        check(WatchCodec.encode(ops))
    }

    private fun page() =
        SnapshotPage(
            epoch = 7,
            listRev = 3,
            ackedThrough = 5,
            ackExceptions = listOf(4 to OpResult.PARTIAL),
            page = 0,
            pages = 2,
            aisles = listOf(SnapAisle("PRODUCE", "Produce", 2, false)),
            aisleBase = 0,
            items = listOf(SnapItem(10, "Onion", "2", 0, false, 4)),
            more = 1,
        )
}

class PagerTest {
    private val aisles =
        listOf(
            PagerAisle("PRODUCE", "Produce", false),
            PagerAisle("DAIRY_EGGS", "Dairy & Eggs", false),
            PagerAisle("BAKING", "Baking", false),
        )

    private fun item(
        id: Int,
        aisle: String = "PRODUCE",
        done: Boolean = false,
        tickedAt: Long? = null,
        name: String = "Item number $id with a long name",
    ) = PagerItem(id, name, "1 × 500 g bag", aisle, done, id, tickedAt)

    private fun input(
        items: List<PagerItem>,
        current: String? = null,
    ) = SnapshotInput(9, 12, 4, listOf(3 to OpResult.NOOP), aisles, items, nowMs = 100 * 60_000L, currentAisle = current)

    private fun assembled(pages: List<SnapshotPage>): Pair<List<SnapAisle>, List<SnapItem>> {
        val ai = pages.flatMap { it.aisles }
        val it = pages.flatMap { it.items }
        return ai to it
    }

    @Test
    fun everyPageUnderTheCapAndAssemblesInOrder() {
        runBlocking {
            checkAll(Arb.list(Arb.int(0..2), 0..60), Arb.int(200..1200)) { aisleIdx, capBytes ->
                val items =
                    aisleIdx.mapIndexed {
                            i,
                            a,
                        ->
                        item(i + 1, aisles[a].code)
                    }.sortedBy { i -> aisles.indexOfFirst { it.code == i.aisleCode } }
                val cap = PayloadCap(capBytes, 16)
                val pages = SnapshotPager.build(input(items), cap)
                for (p in pages) {
                    assertTrue(PayloadSize.of(WatchCodec.encodePage(p)) <= capBytes, "page ${p.page} over $capBytes")
                    assertEquals(pages.size, p.pages)
                    assertEquals(4, p.ackedThrough)
                    assertEquals(listOf(3 to OpResult.NOOP), p.ackExceptions)
                }
                assertEquals((0 until pages.size).toList(), pages.map { it.page })
                val (ai, its) = assembled(pages)
                assertEquals(items.size, its.size + pages[0].more)
                // aisleIdx points into the assembled aisle list
                for (i in its) assertEquals(items.first { it.id == i.id }.aisleCode, ai[i.aisleIdx].code)
            }
        }
    }

    @Test
    fun ackDataIsReservedEvenWhenNothingElseFits() {
        val pages = SnapshotPager.build(input(List(200) { item(it + 1) }), PayloadCap(200, 1))
        assertEquals(1, pages.size)
        assertEquals(4, pages[0].ackedThrough)
        assertEquals(200, pages[0].more + pages[0].items.size)
    }

    @Test
    fun manyAckExceptionsNeverBreakTheCap() {
        val ax = (1..64).map { it to if (it % 2 == 0) OpResult.REJECTED else OpResult.STILL_DONE }
        val items = List(20) { item(it + 1) }
        val pages = SnapshotPager.build(SnapshotInput(9, 12, 64, ax, aisles, items, nowMs = 0), PayloadCap())
        assertTrue(pages.isNotEmpty())
        for (p in pages) {
            assertTrue(PayloadSize.of(WatchCodec.encodePage(p)) <= PayloadCap.DEFAULT_BYTES)
            assertTrue(p.ackExceptions.isNotEmpty())
            // The newest ones, in order.
            assertEquals(ax.takeLast(p.ackExceptions.size), p.ackExceptions)
            assertEquals(pages[0].ackExceptions, p.ackExceptions)
        }
        assertTrue(assembled(pages).second.isNotEmpty(), "acks do not crowd out the list")
    }

    @Test
    fun truncationDropsOldDoneItemsFirst() {
        val now = 100 * 60_000L
        // 1: old DONE, dropped first. 2: recent DONE, kept. 3: DONE in the current aisle, kept.
        val items =
            listOf(
                item(1, done = true, tickedAt = now - 60 * 60_000L),
                item(2, done = true, tickedAt = now - 5 * 60_000L),
                item(3, "DAIRY_EGGS", done = true, tickedAt = 0),
                item(4),
                item(5, "BAKING"),
            )
        val full = SnapshotPager.build(input(items, current = "DAIRY_EGGS"), PayloadCap(4000, 1))
        assertEquals(5, assembled(full).second.size)
        // At the first cap where exactly one item has to go, it is item 1.
        val pages =
            (200..4000)
                .map { SnapshotPager.build(input(items, current = "DAIRY_EGGS"), PayloadCap(it, 1)) }
                .first { assembled(it).second.size == 4 }
        assertEquals(listOf(2, 3, 4, 5), assembled(pages).second.map { it.id })
        assertEquals(1, pages[0].more)
    }

    @Test
    fun thenShortensNamesThenDropsFromTheEnd() {
        val items = List(12) { item(it + 1, if (it < 6) "PRODUCE" else "BAKING") }
        val pages = SnapshotPager.build(input(items), PayloadCap(500, 2))
        val shown = assembled(pages).second
        assertTrue(shown.all { it.name.length <= SnapshotPager.SHORT_NAME }, "names shortened")
        // The survivors are a prefix of the store order.
        assertEquals((1..shown.size).toList(), shown.map { it.id })
        assertEquals(12 - shown.size, pages[0].more)
    }

    @Test
    fun leftCountsEveryUntickedItemInTheAisle() {
        val items = List(30) { item(it + 1) } + item(31, "BAKING", done = true, tickedAt = 0)
        val pages = SnapshotPager.build(input(items), PayloadCap(400, 2))
        val produce = assembled(pages).first.first { it.code == "PRODUCE" }
        assertEquals(30, produce.left)
    }

    @Test
    fun randomNamesNeverBreakTheCap() {
        runBlocking {
            checkAll(Arb.list(Arb.string(0..40), 1..30), Arb.boolean()) { names, done ->
                val items = names.mapIndexed { i, n -> item(i + 1, done = done && i % 2 == 0, name = n) }
                val pages = SnapshotPager.build(input(items), PayloadCap(300, 4))
                for (p in pages) assertTrue(PayloadSize.of(WatchCodec.encodePage(p)) <= 300)
            }
        }
    }
}
