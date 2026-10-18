package com.robert4lyf.hypershop.watch

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.merge.Display
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.sync.PayloadCap
import com.robert4lyf.hypershop.core.sync.PayloadSize
import com.robert4lyf.hypershop.core.sync.WatchCodec
import com.robert4lyf.hypershop.core.sync.rules.WatchSyncModel
import com.robert4lyf.hypershop.data.EpochMarker
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.testutil.TestContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * Step 7.3: every sync vector end to end. The watch is the Kotlin reference
 * model ([WatchSyncModel], which the Monkey C port is held to by its traces);
 * the phone is the real thing — [WatchSyncCoordinator] over [FakeWatchLink],
 * the codec, the pager and [com.robert4lyf.hypershop.data.ListRepository] on
 * Room. Vector steps that act on the phone become repository calls:
 *
 * | vector | repository |
 * |---|---|
 * | `phone.add` / `addContribution` | `manualAdd` ("Add item") |
 * | `phone.removeContribution` | `editQuantity` down by one |
 * | `phone.tick` / `untick` / `delete` | `tick` / `untick` / `softDelete` |
 * | `phone.rekey {from, to}` | `swapItem(from, to's name)`: merges with an ItemAlias |
 * | `phone.newEpoch` | `checkEpoch` with a fresh marker (a restore) |
 * | `phone.push` | `WatchSyncCoordinator.push(force = true)` |
 *
 * Vector item ids are the order items were added; epochs are labels mapped to
 * the real random ones. Messages cross as Connect IQ maps, as on a watch.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DeviceSyncVectorTest {
    @Test
    fun everyVectorEndToEnd() {
        val dir = File("../../testdata/sync")
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
        assertTrue("expected the vectors in ${dir.absolutePath}", files.size >= 15)
        val failures = mutableListOf<String>()
        for (f in files) {
            try {
                runBlocking { Device(Json.parseToJsonElement(f.readText()).jsonObject).run() }
            } catch (e: AssertionError) {
                failures += "${f.nameWithoutExtension}: ${e.message}"
            }
        }
        assertTrue(failures.joinToString("\n\n"), failures.isEmpty())
    }

    private class Device(
        private val vector: JsonObject,
    ) {
        private var nowMs = 1_760_000_000_000L
        private val c: AppContainer = TestContainer.create(clock = { nowMs })
        private val link = c.watchLink as FakeWatchLink
        private val watch =
            WatchSyncModel(
                installId = 77,
                epoch = 0,
                outboxCap = vector["outboxCap"]?.jsonPrimitive?.int ?: 32,
                batchSize = vector["batchSize"]?.jsonPrimitive?.int ?: 8,
            )
        private val toPhone = mutableListOf<Map<String, Any>>()
        private val toWatch = mutableListOf<Map<String, Any>>()

        /** Vector item id (add order, from 1) → real item id. */
        private val ids = mutableListOf<Long>()
        private val names = mutableListOf<String>()
        private val counts = mutableListOf<Int>()

        /** Vector epoch label → real epoch. */
        private val epochs = HashMap<Int, Int>()
        private var lastToggle: Boolean? = null
        private val log = mutableListOf<String>()

        /**
         * A vector's cap is sized for its bare names ("Onion", no sub-label). The
         * phone sends "1 onion" and a pack note, so the cap grows by exactly the
         * difference, set at the first hello: the same items then fit the same
         * pages, and the truncation order is what is under test.
         */
        private suspend fun fitCap() {
            val cap = vector["cap"]?.jsonObject ?: return
            val list = c.repository.snapshot()
            val units = c.settings.units.first()
            var delta = 0
            for ((i, id) in ids.withIndex()) {
                val item = list.items.getValue(id)
                val text = Display.row(list, item, item.catalogId?.let { c.catalog.byId[it] }, units)
                delta += PayloadSize.of(listOf(id.toInt(), text.main, text.sub.orEmpty(), 0, false, 1)) -
                    PayloadSize.of(listOf(id.toInt(), names[i], "", 0, false, 1))
            }
            c.watchSync.cap = PayloadCap(cap.getValue("bytes").jsonPrimitive.int + delta, cap.getValue("maxPages").jsonPrimitive.int)
        }

        private var capFitted = false

        private suspend fun hello() {
            if (!capFitted) {
                fitCap()
                capFitted = true
            }
            toPhone += WatchCodec.encode(watch.hello(nowMs / 1000))
        }

        private fun real(vectorId: Int): Long = ids[vectorId - 1]

        private fun vectorId(realId: Int): Int = ids.indexOf(realId.toLong()) + 1

        suspend fun run() {
            epochs[0] = 0
            epochs[vector["epoch"]?.jsonPrimitive?.int ?: 1] = c.repository.listEpoch().toInt()
            for ((i, step) in vector.getValue("steps").jsonArray.withIndex()) {
                val s = step.jsonObject
                log += "#$i $s"
                if ("expect" in s) expect(s.getValue("expect").jsonObject, i) else perform(s)
            }
        }

        private suspend fun add(index: Int) {
            val p = c.ruleParser.parse("1 ${names[index]}")
            c.repository.manualAdd(p.name, p.shopBase(UnitLocale.METRIC), p.shopUnit)
        }

        private suspend fun perform(s: JsonObject) {
            fun int(k: String) = s.getValue(k).jsonPrimitive.int

            fun idx() = s["index"]?.jsonPrimitive?.int ?: 0
            s["clockMin"]?.let { nowMs = 1_760_000_000_000L + it.jsonPrimitive.long * 60_000L }
            when (val op = s.getValue("do").jsonPrimitive.content) {
                "phone.add" -> {
                    val name = s.getValue("name").jsonPrimitive.content
                    val n = s["contribs"]?.jsonPrimitive?.int ?: 1
                    names += name
                    counts += 1
                    val p = c.ruleParser.parse("1 $name")
                    ids += c.repository.manualAdd(p.name, p.shopBase(UnitLocale.METRIC), p.shopUnit)
                    repeat(n - 1) { addContribution(names.size) }
                }
                "phone.addContribution" -> addContribution(int("item"))
                "phone.removeContribution" -> {
                    val i = int("item") - 1
                    counts[i]--
                    val p = c.ruleParser.parse("${counts[i]} ${names[i]}")
                    c.repository.editQuantity(ids[i], p.shopBase(UnitLocale.METRIC))
                }
                "phone.tick" -> c.repository.tick(real(int("item")))
                "phone.untick" -> c.repository.untick(real(int("item")))
                "phone.delete" -> c.repository.softDelete(real(int("item")))
                "phone.rekey" -> c.repository.swapItem(real(int("from")), names[int("to") - 1])
                "phone.newEpoch" -> {
                    c.repository.checkEpoch(EpochMarker(Files.createTempDirectory("epoch").toFile()))
                    epochs[int("epoch")] = c.repository.listEpoch().toInt()
                }
                "phone.push" -> {
                    c.watchSync.push(force = true)
                    drainPhone()
                }
                "watch.hello" -> hello()
                "watch.toggle" -> lastToggle = watch.toggle(real(int("item")).toInt(), nowMs / 1000)
                "watch.send" -> watch.nextBatch()?.let { toPhone += WatchCodec.encode(it) }
                "watch.timeout" -> watch.timeout()
                "watch.rawOps" ->
                    toPhone +=
                        mapOf(
                            "v" to 1,
                            "t" to "ops",
                            "ep" to watch.epoch,
                            "ii" to watch.installId,
                            "op" to
                                s.getValue("ops").jsonArray.map { o ->
                                    val a = o.jsonArray
                                    listOf(
                                        a[0].jsonPrimitive.int,
                                        real(a[1].jsonPrimitive.int).toInt(),
                                        a[2].jsonPrimitive.boolean,
                                        watch.listRev,
                                        a[4].jsonPrimitive.int,
                                    )
                                },
                        )
                "deliverAll" -> {
                    val to = s.getValue("to").jsonPrimitive.content
                    while (queue(to).isNotEmpty()) deliver(to, 0)
                }
                "deliver" -> repeat(s["n"]?.jsonPrimitive?.int ?: 1) { deliver(s.getValue("to").jsonPrimitive.content, idx()) }
                "drop" -> queue(s.getValue("to").jsonPrimitive.content).removeAt(idx())
                "dup" -> queue(s.getValue("to").jsonPrimitive.content).let { it.add(idx() + 1, it[idx()]) }
                "settle" -> settle()
                else -> error("unknown step $op")
            }
        }

        private suspend fun addContribution(vectorId: Int) {
            counts[vectorId - 1]++
            add(vectorId - 1)
        }

        private fun queue(to: String) = if (to == "phone") toPhone else toWatch

        /** What the coordinator sent goes on the wire to the watch. */
        private fun drainPhone() {
            toWatch += link.sent
            link.sent.clear()
        }

        private suspend fun deliver(
            to: String,
            index: Int,
        ) {
            if (to == "phone") {
                c.watchSync.handle(toPhone.removeAt(index))
                drainPhone()
            } else {
                watch.receive(requireNotNull(WatchCodec.decodePage(toWatch.removeAt(index))) { "the watch could not decode a page" })
            }
        }

        private suspend fun settle() {
            repeat(100) {
                if (watch.helloDue) hello()
                watch.nextBatch()?.let { toPhone += WatchCodec.encode(it) }
                if (toPhone.isEmpty() && toWatch.isEmpty()) {
                    if (watch.outbox.isEmpty() && !watch.helloDue) return
                    watch.timeout()
                    return@repeat
                }
                while (toPhone.isNotEmpty()) deliver("phone", 0)
                while (toWatch.isNotEmpty()) deliver("watch", 0)
            }
            throw AssertionError("did not settle")
        }

        private suspend fun expect(
            e: JsonObject,
            step: Int,
        ) {
            fun fail(msg: String): Nothing = throw AssertionError("step $step: $msg\n${log.takeLast(12).joinToString("\n")}")

            fun eq(
                what: String,
                expected: Any?,
                actual: Any?,
            ) {
                if (expected != actual) fail("$what: expected $expected, got $actual")
            }
            val list = c.repository.snapshot()
            for ((k, v) in e) {
                when (k) {
                    "phone.done" ->
                        v.jsonObject.forEach { (id, d) ->
                            val item = list.resolve(real(id.toInt()))
                            eq("phone done[$id]", d.jsonPrimitive.boolean, item != null && list.isDone(item.id))
                        }
                    "phone.uncovered" ->
                        v.jsonObject.forEach { (id, n) ->
                            eq(
                                "phone uncovered[$id]",
                                n.jsonPrimitive.int,
                                list.contributionsOf(real(id.toInt())).count { it.coveredBy == null },
                            )
                        }
                    "phone.ticks" -> eq("phone ticks", v.jsonPrimitive.int, list.ticks.size)
                    "phone.lastSeq" -> eq("phone lastSeq", v.jsonPrimitive.int, c.db.dao().install(watch.installId)?.lastSeq)
                    "watch.view" ->
                        v.jsonObject.forEach { (id, d) ->
                            eq("watch view[$id]", d.jsonPrimitive.boolean, watch.view()[real(id.toInt()).toInt()])
                        }
                    "watch.items" ->
                        eq(
                            "watch items",
                            v.jsonArray.map { it.jsonPrimitive.int }.toSet(),
                            watch.items.keys.map(::vectorId).toSet(),
                        )
                    "watch.outbox" -> eq("watch outbox", v.jsonPrimitive.int, watch.outbox.size)
                    "watch.ackedThrough" -> eq("watch ackedThrough", v.jsonPrimitive.int, watch.ackedThrough)
                    "watch.nextSeq" -> eq("watch nextSeq", v.jsonPrimitive.int, watch.nextSeq)
                    "watch.epoch" -> eq("watch epoch", epochs[v.jsonPrimitive.int], watch.epoch)
                    "watch.listRev" ->
                        if (v.jsonPrimitive.int == 0) {
                            eq(
                                "watch listRev",
                                0,
                                watch.listRev,
                            )
                        } else {
                            fail("only listRev 0 maps to a real listRev")
                        }
                    "watch.listRevIsPhones" -> if (v.jsonPrimitive.boolean) eq("watch listRev", list.listRev.toInt(), watch.listRev)
                    "watch.result" ->
                        v.jsonObject.forEach { (seq, r) -> eq("result[$seq]", r.jsonPrimitive.content, watch.results[seq.toInt()]?.wire) }
                    "watch.notice" ->
                        if (watch.notices.none { it == v.jsonPrimitive.content }) {
                            fail(
                                "no notice '${v.jsonPrimitive.content}' in ${watch.notices}",
                            )
                        }
                    "watch.helloDue" -> eq("watch helloDue", v.jsonPrimitive.boolean, watch.helloDue)
                    "watch.toggled" -> eq("last toggle accepted", v.jsonPrimitive.boolean, lastToggle)
                    "watch.seqs" ->
                        eq("outbox seqs", v.jsonArray.map { it.jsonPrimitive.content.toIntOrNull() }, watch.outbox.map { it.seq })
                    "queue.phone" -> eq("messages to phone", v.jsonPrimitive.int, toPhone.size)
                    "queue.watch" -> eq("messages to watch", v.jsonPrimitive.int, toWatch.size)
                    "converged" ->
                        if (v.jsonPrimitive.boolean) {
                            eq("watch outbox", 0, watch.outbox.size)
                            eq("watch listRev", list.listRev.toInt(), watch.listRev)
                            val phoneView = list.liveItems.associate { it.id.toInt() to list.isDone(it.id) }
                            eq("watch view", phoneView, watch.view())
                        }
                    else -> fail("unknown expectation $k")
                }
            }
        }
    }
}
