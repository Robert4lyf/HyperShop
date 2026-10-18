package com.robert4lyf.hypershop.core.sync.rules

import com.robert4lyf.hypershop.core.sync.PagerAisle
import com.robert4lyf.hypershop.core.sync.PagerItem
import com.robert4lyf.hypershop.core.sync.PayloadCap
import com.robert4lyf.hypershop.core.sync.SnapshotInput
import com.robert4lyf.hypershop.core.sync.SnapshotPage
import com.robert4lyf.hypershop.core.sync.SnapshotPager
import com.robert4lyf.hypershop.core.sync.WatchCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File

/**
 * Replays a sync vector (testdata/sync/<name>.json) through [PhoneSyncModel],
 * [WatchSyncModel], the real [WatchCodec] and [SnapshotPager], over two
 * message queues the vector can drop, duplicate and reorder.
 *
 * Messages cross the "wire" as encoded Connect IQ maps, so the codec is part
 * of every vector. Vector format: docs/garmin-protocol.md, "Test vectors".
 */
class SyncHarness(
    private val vector: JsonObject,
) {
    private val cap =
        vector["cap"]?.jsonObject?.let {
            PayloadCap(it.getValue("bytes").jsonPrimitive.int, it.getValue("maxPages").jsonPrimitive.int)
        } ?: PayloadCap()
    val phone = PhoneSyncModel(epoch = vector["epoch"]?.jsonPrimitive?.int ?: 1)
    val watch =
        WatchSyncModel(
            installId = 77,
            epoch = 0,
            outboxCap = vector["outboxCap"]?.jsonPrimitive?.int ?: 32,
            batchSize = vector["batchSize"]?.jsonPrimitive?.int ?: 8,
        )
    val toPhone = mutableListOf<Map<String, Any>>()
    val toWatch = mutableListOf<Map<String, Any>>()
    private var nowMs = 0L
    private var lastToggle: Boolean? = null
    private val aisles = listOf(PagerAisle("PRODUCE", "Produce", false), PagerAisle("DAIRY_EGGS", "Dairy & Eggs", false))
    val log = mutableListOf<String>()

    /**
     * Every call into the watch model and the state after it, for
     * garmin/test/SyncTraces.mc ([WatchTraceTest]): the Monkey C port
     * replays the same calls and must end up in the same state.
     */
    val trace =
        mutableListOf<List<Any?>>(
            listOf("config", vector["batchSize"]?.jsonPrimitive?.int ?: 8, vector["outboxCap"]?.jsonPrimitive?.int ?: 32),
        )

    private fun traced(vararg event: Any?) {
        trace += event.toList()
        trace += watchState()
    }

    private fun watchState(): List<Any?> {
        val last = watch.notices.lastOrNull()
        val (code, count) =
            when {
                last == null -> null to 0
                last == "1 more" -> "more" to 1
                last == "Changed on phone" -> "changed" to 1
                last.endsWith(" watch ticks discarded (list replaced)") -> "discarded" to last.substringBefore(' ').toInt()
                else -> error("unmapped notice $last")
            }
        return listOf(
            "state",
            watch.view().toSortedMap(),
            watch.outbox.map { it.seq ?: 0 },
            watch.ackedThrough,
            watch.nextSeq,
            watch.epoch,
            watch.listRev,
            watch.helloDue,
            code,
            count,
            watch.results.toSortedMap().mapValues { it.value.wire },
        )
    }

    private fun watchHello() {
        val h = watch.hello(nowMs / 1000)
        traced("hello", (nowMs / 1000).toInt(), h.listRev, h.ackedThrough, h.epoch)
        toPhone += WatchCodec.encode(h)
    }

    private fun watchSend() {
        val batch = watch.nextBatch()
        val encoded = batch?.let { WatchCodec.encode(it) }
        traced("send", encoded?.get("op"))
        encoded?.let { toPhone += it }
    }

    private fun watchTimeout() {
        watch.timeout()
        traced("timeout")
    }

    fun run() {
        for ((i, step) in vector.getValue("steps").jsonArray.withIndex()) {
            val s = step.jsonObject
            log += "#$i $s"
            if ("expect" in s) expect(s.getValue("expect").jsonObject, i) else perform(s)
        }
    }

    private fun perform(s: JsonObject) {
        fun int(k: String) = s.getValue(k).jsonPrimitive.int

        fun idx() = s["index"]?.jsonPrimitive?.int ?: 0
        s["clockMin"]?.let { nowMs = it.jsonPrimitive.long * 60_000L }
        when (val op = s.getValue("do").jsonPrimitive.content) {
            "phone.add" -> phone.addItem(s.getValue("name").jsonPrimitive.content, s["contribs"]?.jsonPrimitive?.int ?: 1)
            "phone.addContribution" -> phone.addContribution(int("item"))
            "phone.removeContribution" -> phone.removeContribution(int("item"))
            "phone.tick" -> phone.phoneTick(int("item"), nowMs)
            "phone.untick" -> phone.phoneUntick(int("item"))
            "phone.delete" -> phone.deleteItem(int("item"))
            "phone.rekey" -> phone.rekey(int("from"), int("to"))
            "phone.newEpoch" -> phone.newEpoch(int("epoch"))
            "phone.push" -> pushSnapshot()
            "watch.hello" -> watchHello()
            "watch.toggle" -> {
                lastToggle = watch.toggle(int("item"), nowMs / 1000)
                traced("toggle", int("item"), (nowMs / 1000).toInt(), lastToggle)
            }
            "watch.send" -> watchSend()
            "watch.timeout" -> watchTimeout()
            "watch.rawOps" ->
                toPhone +=
                    mapOf(
                        "v" to 1,
                        "t" to "ops",
                        "ep" to watch.epoch,
                        "ii" to watch.installId,
                        "op" to
                            s.getValue("ops").jsonArray.map {
                                    o ->
                                o.jsonArray.mapIndexed { i, x -> if (i == 2) x.jsonPrimitive.boolean else x.jsonPrimitive.int }
                            },
                    )
            "deliverAll" -> while (queue(
                    s.getValue("to").jsonPrimitive.content,
                ).isNotEmpty()
                ) deliver(s.getValue("to").jsonPrimitive.content, 0)
            "deliver" -> repeat(s["n"]?.jsonPrimitive?.int ?: 1) { deliver(s.getValue("to").jsonPrimitive.content, idx()) }
            "drop" -> queue(s.getValue("to").jsonPrimitive.content).removeAt(idx())
            "dup" -> queue(s.getValue("to").jsonPrimitive.content).let { it.add(idx() + 1, it[idx()]) }
            "settle" -> settle()
            else -> error("unknown step $op")
        }
    }

    private fun queue(to: String) =
        when (to) {
            "phone" -> toPhone
            "watch" -> toWatch
            else -> error("queue $to")
        }

    private fun deliver(
        to: String,
        index: Int,
    ) {
        if (to == "phone") {
            val m = requireNotNull(WatchCodec.decode(toPhone.removeAt(index))) { "phone could not decode" }
            phone.now = nowMs
            val reply = phone.receive(m) ?: return
            if (reply.fullSnapshot) pushSnapshot(reply) else toWatch += WatchCodec.encodePage(ackOnly(reply))
        } else {
            val payload = toWatch.removeAt(index)
            val page = requireNotNull(WatchCodec.decodePage(payload)) { "watch could not decode" }
            watch.receive(page)
            traced("recv", payload)
        }
    }

    private fun ackOnly(r: PhoneSyncModel.Reply) =
        SnapshotPage(phone.epoch, phone.listRev, r.ackedThrough, r.ackExceptions, 0, 0, emptyList(), 0, emptyList(), 0)

    /** The phone sends its whole snapshot (on hello, after a change, or a `phone.push`). */
    private fun pushSnapshot(reply: PhoneSyncModel.Reply? = null) {
        val install = phone.installs[watch.installId]
        val input =
            SnapshotInput(
                epoch = phone.epoch,
                listRev = phone.listRev,
                ackedThrough = reply?.ackedThrough ?: install?.lastSeq ?: 0,
                ackExceptions = reply?.ackExceptions ?: emptyList(),
                aisles = aisles,
                items =
                    phone.items.values.filter { !it.deleted }.map {
                        PagerItem(
                            it.id,
                            it.name,
                            "",
                            if (it.id % 2 == 1) "PRODUCE" else "DAIRY_EGGS",
                            it.done,
                            it.rev,
                            phone.tickedAt(it.id),
                        )
                    }.sortedBy { if (it.aisleCode == "PRODUCE") 0 else 1 },
                nowMs = nowMs,
            )
        for (p in SnapshotPager.build(input, cap)) toWatch += WatchCodec.encodePage(p)
    }

    /** Send, deliver and retry until nothing is left to do (no faults). */
    private fun settle() {
        repeat(100) {
            if (watch.helloDue) watchHello()
            watchSend()
            if (toPhone.isEmpty() && toWatch.isEmpty()) {
                if (watch.outbox.isEmpty() && !watch.helloDue) return
                watchTimeout()
                return@repeat
            }
            while (toPhone.isNotEmpty()) deliver("phone", 0)
            while (toWatch.isNotEmpty()) deliver("watch", 0)
        }
        error("did not settle")
    }

    private fun expect(
        e: JsonObject,
        step: Int,
    ) {
        fun fail(msg: String): Nothing = throw AssertionError("${vector["name"]} step $step: $msg\n${log.joinToString("\n")}")

        fun eq(
            what: String,
            expected: Any?,
            actual: Any?,
        ) {
            if (expected != actual) fail("$what: expected $expected, got $actual")
        }
        for ((k, v) in e) {
            when (k) {
                "phone.done" ->
                    v.jsonObject.forEach {
                            (id, d) ->
                        eq("phone done[$id]", d.jsonPrimitive.boolean, phone.resolve(id.toInt())?.done)
                    }
                "phone.uncovered" ->
                    v.jsonObject.forEach {
                            (id, n) ->
                        eq("phone uncovered[$id]", n.jsonPrimitive.int, phone.items.getValue(id.toInt()).uncovered)
                    }
                "phone.ticks" -> eq("phone ticks", v.jsonPrimitive.int, phone.ticks.size)
                "phone.lastSeq" -> eq("phone lastSeq", v.jsonPrimitive.int, phone.installs[watch.installId]?.lastSeq)
                "watch.view" -> v.jsonObject.forEach { (id, d) -> eq("watch view[$id]", d.jsonPrimitive.boolean, watch.view()[id.toInt()]) }
                "watch.items" -> eq("watch items", v.jsonArray.map { it.jsonPrimitive.int }.toSet(), watch.items.keys)
                "watch.outbox" -> eq("watch outbox", v.jsonPrimitive.int, watch.outbox.size)
                "watch.ackedThrough" -> eq("watch ackedThrough", v.jsonPrimitive.int, watch.ackedThrough)
                "watch.nextSeq" -> eq("watch nextSeq", v.jsonPrimitive.int, watch.nextSeq)
                "watch.epoch" -> eq("watch epoch", v.jsonPrimitive.int, watch.epoch)
                "watch.listRev" -> eq("watch listRev", v.jsonPrimitive.int, watch.listRev)
                "watch.listRevIsPhones" -> if (v.jsonPrimitive.boolean) eq("watch listRev", phone.listRev, watch.listRev)
                "watch.result" ->
                    v.jsonObject.forEach {
                            (seq, r) ->
                        eq("result[$seq]", r.jsonPrimitive.content, watch.results[seq.toInt()]?.wire)
                    }
                "watch.notice" ->
                    if (watch.notices.none {
                            it == v.jsonPrimitive.content
                        }
                    ) {
                        fail("no notice '${v.jsonPrimitive.content}' in ${watch.notices}")
                    }
                "watch.helloDue" -> eq("watch helloDue", v.jsonPrimitive.boolean, watch.helloDue)
                "watch.toggled" -> eq("last toggle accepted", v.jsonPrimitive.boolean, lastToggle)
                "watch.seqs" -> eq("outbox seqs", v.jsonArray.map { it.toIntOrNull() }, watch.outbox.map { it.seq })
                "queue.phone" -> eq("messages to phone", v.jsonPrimitive.int, toPhone.size)
                "queue.watch" -> eq("messages to watch", v.jsonPrimitive.int, toWatch.size)
                "converged" ->
                    if (v.jsonPrimitive.boolean) {
                        eq("watch outbox", 0, watch.outbox.size)
                        eq("watch listRev", phone.listRev, watch.listRev)
                        eq("watch view", phone.doneView(), watch.view())
                    }
                else -> fail("unknown expectation $k")
            }
        }
    }

    private fun JsonElement.toIntOrNull(): Int? = if (this is kotlinx.serialization.json.JsonNull) null else jsonPrimitive.int

    companion object {
        val dir = File("../../testdata/sync")

        fun load(file: File): SyncHarness = SyncHarness(Json.parseToJsonElement(file.readText()).jsonObject)
    }
}
