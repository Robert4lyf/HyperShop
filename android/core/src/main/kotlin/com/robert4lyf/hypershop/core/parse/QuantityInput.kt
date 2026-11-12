package com.robert4lyf.hypershop.core.parse

import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.UnitLocale

/** A whole user-entered amount, not an ingredient line with an ignored tail. */
object QuantityInput {
    fun parse(text: String): QtyBase? {
        if (text.length > 100) return null
        val p = RuleParser().parse(text.trim() + " hypershopquantity")
        if (p.name != "hypershopquantity" || p.check || p.quantityMax != null || p.note != null) return null
        return p.base(UnitLocale.METRIC).takeUnless { it.isPresence }
    }
}
