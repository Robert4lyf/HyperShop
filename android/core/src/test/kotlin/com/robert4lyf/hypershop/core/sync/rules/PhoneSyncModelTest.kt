package com.robert4lyf.hypershop.core.sync.rules

import com.robert4lyf.hypershop.core.sync.OpResult
import com.robert4lyf.hypershop.core.sync.PagerAisle
import com.robert4lyf.hypershop.core.sync.PagerItem
import com.robert4lyf.hypershop.core.sync.PayloadCap
import com.robert4lyf.hypershop.core.sync.SnapshotInput
import com.robert4lyf.hypershop.core.sync.SnapshotPage
import com.robert4lyf.hypershop.core.sync.SnapshotPager
import com.robert4lyf.hypershop.core.sync.WatchMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The phone and watch rules joined by the real pager under the default payload
 * cap, which the sync vectors leave out (their ack-only pages are uncapped).
 */
class PhoneSyncModelTest {
    private val phone = PhoneSyncModel(epoch = 9)
    private val watch = WatchSyncModel(installId = 1, epoch = 9)
    private val aisles = listOf(PagerAisle("PRODUCE", "Produce", false))

    private fun pages(reply: PhoneSyncModel.Reply): List<SnapshotPage> {
        val items =
            phone.items.values.filter { !it.deleted }.map {
                PagerItem(it.id, it.name, "", "PRODUCE", it.done, it.rev, phone.tickedAt(it.id))
            }
        return SnapshotPager.build(
            SnapshotInput(phone.epoch, phone.listRev, reply.ackedThrough, reply.ackExceptions, aisles, items, nowMs = 0),
            PayloadCap(),
        )
    }

    private fun deliver(m: WatchMessage) {
        val reply = phone.receive(m) ?: return
        pages(reply).forEach(watch::receive)
    }

    /**
     * Eight ticks made offline, seven of which the phone had already ticked:
     * seven `noop`s do not fit one page's ack budget. Before the fix every reply
     * carried all seven, the pager lowered `ackedThrough` below the batch, and
     * the watch resent its last op for ever.
     */
    @Test
    fun aBatchOfNoopsDrainsInsteadOfResendingForEver() {
        val ids = (1..8).map { phone.addItem("Item $it") }
        deliver(watch.hello(0))
        assertEquals(8, watch.items.size)
        ids.drop(1).forEach { phone.phoneTick(it) }
        ids.forEach { assertTrue(watch.toggle(it, 0)) }
        var rounds = 0
        while (true) {
            val batch = watch.nextBatch() ?: break
            rounds++
            assertTrue(rounds <= 4, "the outbox should drain in a few rounds, not resend for ever")
            deliver(batch)
        }
        assertTrue(watch.outbox.isEmpty())
        assertEquals(8, watch.ackedThrough)
        assertEquals(OpResult.OK, watch.results[1])
        (2..8).forEach { assertEquals(OpResult.NOOP, watch.results[it]) }
        // Settled seqs are not asked about again, so only the last batch's results are kept.
        assertTrue(phone.installs.getValue(1).results.keys.all { it >= 8 })
    }

    @Test
    fun aDuplicateAfterALostAckIsStillAnsweredFromHistory() {
        val id = phone.addItem("Onion")
        deliver(watch.hello(0))
        assertTrue(watch.toggle(id, 0))
        val batch = watch.nextBatch()!!
        val lost = phone.receive(batch)!!
        assertEquals(1, lost.ackedThrough)
        // The reply never arrived: the watch resends the same seq, and the phone re-acks it without re-applying.
        watch.timeout()
        val again = watch.nextBatch()!!
        assertEquals(batch, again)
        val reply = phone.receive(again)!!
        assertEquals(1, reply.ackedThrough)
        assertEquals(1, phone.ticks.size)
        pages(reply).forEach(watch::receive)
        assertTrue(watch.outbox.isEmpty())
    }
}
