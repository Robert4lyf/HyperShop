package com.robert4lyf.hypershop.core.sync

import com.robert4lyf.hypershop.core.sync.WatchProtocol.Key

/**
 * Turns Connect IQ payloads into typed messages and back.
 *
 * **Watch input is untrusted** (PLAN §3.6): every field is type- and
 * range-checked, list sizes are capped, and anything malformed decodes to
 * `null` rather than half a message. Numbers arrive as whatever numeric type
 * the watch used, so they are read through [Number].
 *
 * Connect IQ dictionaries cannot carry nulls; absent fields are left out.
 * Only String, Int, Long, Boolean, List and Map are put in an outbound payload.
 */
object WatchCodec {
    const val MAX_OPS_PER_BATCH = 32

    /** The largest probe either way: well past anything a watch is thought to take. */
    const val MAX_PROBE_BYTES = 8192
    private const val MAX_TS = 4_102_444_800L // 2100-01-01

    fun decode(payload: Map<*, *>): WatchMessage? {
        val version = int(payload[Key.VERSION]) ?: return null
        if (version != WatchProtocol.VERSION) return null
        val epoch = int(payload[Key.EPOCH]) ?: return null
        val install = int(payload[Key.INSTALL_ID])?.takeIf { it != 0 } ?: return null
        return when (payload[Key.TYPE] as? String) {
            WatchProtocol.Inbound.HELLO ->
                WatchMessage.Hello(
                    epoch = epoch,
                    installId = install,
                    listRev = int(payload[Key.LIST_REV])?.takeIf { it >= 0 } ?: return null,
                    watchNow = long(payload[Key.WATCH_NOW])?.takeIf { it in 0..MAX_TS } ?: return null,
                    ackedThrough = int(payload[Key.ACKED_THROUGH])?.takeIf { it >= 0 } ?: return null,
                )
            WatchProtocol.Inbound.OPS -> {
                val raw = payload[Key.OPS] as? List<*> ?: return null
                if (raw.isEmpty() || raw.size > MAX_OPS_PER_BATCH) return null
                val ops = raw.map { op(it) ?: return null }
                // Seqs in a batch must be consecutive and increasing.
                if (ops.zipWithNext().any { (a, b) -> b.seq != a.seq + 1 }) return null
                WatchMessage.Ops(epoch, install, ops)
            }
            WatchProtocol.Inbound.BYE -> WatchMessage.Bye(epoch, install)
            else -> null
        }
    }

    private fun op(any: Any?): WatchOp? {
        val a = any as? List<*> ?: return null
        if (a.size != 5) return null
        return WatchOp(
            seq = int(a[0])?.takeIf { it >= 1 } ?: return null,
            itemId = int(a[1])?.takeIf { it >= 1 } ?: return null,
            done = a[2] as? Boolean ?: return null,
            seenListRev = int(a[3])?.takeIf { it >= 0 } ?: return null,
            ts = long(a[4])?.takeIf { it in 0..MAX_TS } ?: return null,
        )
    }

    /** A probe request, or null for anything else or anything malformed. */
    fun decodeProbe(payload: Map<*, *>): ProbeRequest? {
        if (int(payload[Key.VERSION]) != WatchProtocol.VERSION) return null
        if (payload[Key.TYPE] != WatchProtocol.Inbound.PROBE) return null
        val install = int(payload[Key.INSTALL_ID])?.takeIf { it != 0 } ?: return null
        val want = int(payload[Key.PROBE_SIZE])?.takeIf { it in 0..MAX_PROBE_BYTES } ?: return null
        val pad = payload[Key.PAD]
        if (pad != null && (pad !is String || pad.length > MAX_PROBE_BYTES)) return null
        return ProbeRequest(install, PayloadSize.of(payload), want)
    }

    /**
     * The reply to a probe: padded so that [PayloadSize] counts about
     * [ProbeRequest.want] bytes, carrying what the phone measured of the
     * request (`n`) and of this reply (`sd`).
     */
    fun encodeProbeReply(r: ProbeRequest): Map<String, Any> {
        fun build(
            pad: Int,
            down: Int,
        ) = mapOf<String, Any>(
            Key.VERSION to WatchProtocol.VERSION,
            Key.TYPE to WatchProtocol.Outbound.PROBE,
            Key.PROBE_SIZE to r.received,
            Key.SIZE_DOWN to down,
            Key.PAD to "x".repeat(pad),
        )
        val bare = PayloadSize.of(build(0, 0))
        val pad = (r.want - bare).coerceAtLeast(0)
        return build(pad, PayloadSize.of(build(pad, 0)))
    }

    fun encode(m: WatchMessage): Map<String, Any> =
        buildMap {
            put(Key.VERSION, WatchProtocol.VERSION)
            put(Key.EPOCH, m.epoch)
            put(Key.INSTALL_ID, m.installId)
            when (m) {
                is WatchMessage.Hello -> {
                    put(Key.TYPE, WatchProtocol.Inbound.HELLO)
                    put(Key.LIST_REV, m.listRev)
                    put(Key.WATCH_NOW, m.watchNow)
                    put(Key.ACKED_THROUGH, m.ackedThrough)
                }
                is WatchMessage.Ops -> {
                    put(Key.TYPE, WatchProtocol.Inbound.OPS)
                    put(Key.OPS, m.ops.map { listOf(it.seq, it.itemId, it.done, it.seenListRev, it.ts) })
                }
                is WatchMessage.Bye -> put(Key.TYPE, WatchProtocol.Inbound.BYE)
            }
        }

    fun encodePage(p: SnapshotPage): Map<String, Any> =
        buildMap {
            put(Key.VERSION, WatchProtocol.VERSION)
            put(Key.TYPE, WatchProtocol.Outbound.SNAPSHOT)
            put(Key.EPOCH, p.epoch)
            put(Key.LIST_REV, p.listRev)
            put(Key.ACKED_THROUGH, p.ackedThrough)
            if (p.ackExceptions.isNotEmpty()) put(Key.ACK_EXCEPTIONS, p.ackExceptions.map { listOf(it.first, it.second.wire) })
            put(Key.PAGE, p.page)
            put(Key.PAGES, p.pages)
            // [base, [code, name, left, hidden]...]: the slice's first index rides as element 0.
            if (p.aisles.isNotEmpty()) {
                put(
                    Key.AISLES,
                    listOf<Any>(p.aisleBase) + p.aisles.map { listOf(it.code, it.name, it.left, it.hidden) },
                )
            }
            put(Key.ITEMS, p.items.map { listOf(it.id, it.name, it.sub, it.aisleIdx, it.done, it.rev) })
            if (p.more > 0) put(Key.MORE, p.more)
            if (p.build != 0) put(Key.BUILD, p.build)
        }

    /** The watch's decoder for a page; also used by tests and the Run 2 fake device. */
    fun decodePage(payload: Map<*, *>): SnapshotPage? {
        if (int(payload[Key.VERSION]) != WatchProtocol.VERSION) return null
        if (payload[Key.TYPE] != WatchProtocol.Outbound.SNAPSHOT) return null
        val ax =
            (payload[Key.ACK_EXCEPTIONS] as? List<*>).orEmpty().map {
                val pair = it as? List<*> ?: return null
                val seq = int(pair.getOrNull(0)) ?: return null
                val r = OpResult.fromWire(pair.getOrNull(1) as? String ?: return null) ?: return null
                seq to r
            }
        val aiRaw = payload[Key.AISLES] as? List<*>
        val aisleBase = if (aiRaw.isNullOrEmpty()) 0 else int(aiRaw[0]) ?: return null
        val aisles =
            aiRaw.orEmpty().drop(1).map {
                val a = it as? List<*> ?: return null
                SnapAisle(
                    a.getOrNull(0) as? String ?: return null,
                    a.getOrNull(1) as? String ?: return null,
                    int(a.getOrNull(2)) ?: return null,
                    a.getOrNull(3) as? Boolean ?: return null,
                )
            }
        val items =
            (payload[Key.ITEMS] as? List<*> ?: return null).map {
                val a = it as? List<*> ?: return null
                SnapItem(
                    int(a.getOrNull(0)) ?: return null,
                    a.getOrNull(1) as? String ?: return null,
                    a.getOrNull(2) as? String ?: return null,
                    int(a.getOrNull(3)) ?: return null,
                    a.getOrNull(4) as? Boolean ?: return null,
                    int(a.getOrNull(5)) ?: return null,
                )
            }
        val page = int(payload[Key.PAGE])?.takeIf { it >= 0 } ?: return null
        val pages = int(payload[Key.PAGES])?.takeIf { it >= 0 } ?: return null
        // An ack-only page has pages = 0; any other page's index lies inside its count.
        if (pages > 0 && page >= pages) return null
        if (aisleBase < 0 || items.any { it.aisleIdx < 0 }) return null
        val more = if (Key.MORE in payload) int(payload[Key.MORE])?.takeIf { it >= 0 } ?: return null else 0
        // An absent or malformed build is "not said": such pages are assembled as before.
        val build = int(payload[Key.BUILD]) ?: 0
        return SnapshotPage(
            epoch = int(payload[Key.EPOCH]) ?: return null,
            listRev = int(payload[Key.LIST_REV])?.takeIf { it >= 0 } ?: return null,
            ackedThrough = int(payload[Key.ACKED_THROUGH])?.takeIf { it >= 0 } ?: return null,
            ackExceptions = ax,
            page = page,
            pages = pages,
            aisles = aisles,
            aisleBase = aisleBase,
            items = items,
            more = more,
            build = build,
        )
    }

    private fun int(a: Any?): Int? =
        when (a) {
            is Int -> a
            is Long -> if (a in Int.MIN_VALUE..Int.MAX_VALUE) a.toInt() else null
            is Short, is Byte -> (a as Number).toInt()
            else -> null
        }

    private fun long(a: Any?): Long? =
        when (a) {
            is Int, is Long, is Short, is Byte -> (a as Number).toLong()
            else -> null
        }
}
