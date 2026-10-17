package com.robert4lyf.hypershop.core.sync

/**
 * Wire protocol shared with the Forerunner 245 app (`garmin/source/Protocol.mc`,
 * Run 2). Declared twice; `tools/check_protocol_parity.py` fails CI if the two
 * drift. See docs/garmin-protocol.md.
 *
 * Connect IQ messages to older devices may be capped in the low hundreds of
 * bytes and dropped silently when larger, so keys are 1–3 characters and
 * snapshots are paged (PLAN §3.6).
 *
 * Versioning follows BangGym's rule: a payload whose `v` differs is dropped.
 * A new key or message type does not bump the version; only a change in the
 * shape or meaning of an existing message does.
 */
object WatchProtocol {
    const val VERSION = 1

    /** Must equal the `id` in garmin/manifest.xml (parity-checked). */
    const val WATCH_APP_ID = "41c79afd24ac47168df768ef92d1bac0"

    object Key {
        const val VERSION = "v"
        const val TYPE = "t"
        const val EPOCH = "ep"
        const val INSTALL_ID = "ii"
        const val LIST_REV = "lr"
        const val WATCH_NOW = "wn"
        const val ACKED_THROUGH = "at"
        const val ACK_EXCEPTIONS = "ax"
        const val PAGE = "pg"
        const val PAGES = "pgs"
        const val ITEMS = "it"
        const val AISLES = "ai"
        const val MORE = "mo"
        const val OPS = "op"
    }

    /** Watch → phone. */
    object Inbound {
        const val HELLO = "hello"
        const val OPS = "ops"
        const val BYE = "bye"
    }

    /** Phone → watch. */
    object Outbound {
        const val SNAPSHOT = "snap"
    }

    /** Op results, carried in `ax` for every acked seq whose result is not `ok`. */
    object Result {
        const val OK = "ok"
        const val NOOP = "noop"
        const val PARTIAL = "partial"
        const val STILL_DONE = "still_done"
        const val REJECTED = "rejected"
        const val EPOCH = "epoch"
    }
}
