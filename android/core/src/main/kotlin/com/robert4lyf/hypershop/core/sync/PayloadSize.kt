package com.robert4lyf.hypershop.core.sync

/**
 * A conservative estimate of a payload's size on the Connect IQ wire.
 *
 * The real serialiser is not documented, so this over-counts: a type tag per
 * value, 4 bytes per Int, 8 per Long, a length prefix and terminator per
 * string, a count per container, plus a fixed envelope overhead. The true
 * limit for the Forerunner 245 is measured by the payload probe at Hand-off B
 * and fed back in as [PayloadCap.bytes].
 */
object PayloadSize {
    const val ENVELOPE_OVERHEAD = 16

    fun of(payload: Any?): Int = ENVELOPE_OVERHEAD + value(payload)

    private fun value(v: Any?): Int =
        1 +
            when (v) {
                null -> 0
                is Boolean -> 1
                is Int, is Short, is Byte, is Float -> 4
                is Long, is Double -> 8
                is String -> 2 + v.toByteArray(Charsets.UTF_8).size + 1
                is List<*> -> 4 + v.sumOf { value(it) }
                is Map<*, *> -> 4 + v.entries.sumOf { value(it.key) + value(it.value) }
                else -> throw IllegalArgumentException("not a Connect IQ type: ${v::class}")
            }
}

/** The largest payload the phone will send, and how many pages a snapshot may use. */
data class PayloadCap(
    val bytes: Int = DEFAULT_BYTES,
    val maxPages: Int = DEFAULT_MAX_PAGES,
) {
    companion object {
        /** "Low hundreds of bytes" (BangGym's notes); replaced by the probe's measurement. */
        const val DEFAULT_BYTES = 384
        const val DEFAULT_MAX_PAGES = 16
    }
}
