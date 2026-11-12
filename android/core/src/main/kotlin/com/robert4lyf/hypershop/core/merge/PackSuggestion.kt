package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.Rational

/** A purchase suggestion, never a new requirement or a purchase record. */
data class PackSuggestion(val packs: Long, val size: QtyBase) {
    companion object {
        fun forNeed(
            need: QtyBase,
            size: QtyBase,
        ): PackSuggestion? {
            if (need.parts.size != 1 || size.parts.size != 1 || need.keys != size.keys) return null
            val amount = need.parts.values.single()
            val pack = size.parts.values.single()
            if (amount <= Rational.ZERO || pack <= Rational.ZERO) return null
            val ratio = amount / pack
            val count = ratio.num / ratio.den + if (ratio.num % ratio.den == 0L) 0L else 1L
            return PackSuggestion(count, size)
        }
    }
}
