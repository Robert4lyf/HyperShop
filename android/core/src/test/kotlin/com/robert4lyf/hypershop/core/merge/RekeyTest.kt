package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.Normaliser
import io.kotest.property.arbitrary.list
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RekeyTest {
    @Test
    fun nameKeyedItemsMoveToTheCatalogueAndMerge() {
        val n = Normaliser()

        // Two items the old catalogue did not know as one: "courgettes" (name-keyed) and "zucchini" (catalogue).
        fun n(k: Int) = QtyBase.of(Rational.of(k), null, UnitLocale.METRIC)
        var l = ShoppingList()
        l = l.manualAdd(NewItem("cat:courgette", "courgette", "courgette", "courgette", AisleCode.PRODUCE), n(1), null, 1).first
        l = l.manualAdd(NewItem("name:zucchini", null, "zucchini", "zucchini", AisleCode.OTHER), n(2), null, 2).first
        l = l.manualAdd(NewItem("name:courgette", null, "courgette", "courgette", AisleCode.OTHER), n(1), null, 3).first
        val survivor = l.itemByKey("cat:courgette")!!
        val loser = l.itemByKey("name:zucchini")!!
        val r = Rekey.rekey(l, n)
        assertEquals(1, r.list.liveItems.size)
        val s = r.list.liveItems.single()
        assertEquals(survivor.id, s.id) // lower id survives
        assertEquals(AisleCode.PRODUCE, s.aisle) // survivor's aisle wins
        assertEquals(Rational.of(4), r.list.total(s.id)[BaseKey.ITEMS])
        assertEquals(survivor.id, r.list.resolve(loser.id)?.id) // ItemAlias
        assertTrue(s.rev > maxOf(survivor.rev, loser.rev))
        assertEquals(mapOf(loser.id to survivor.id), r.merged.filterKeys { it == loser.id })
    }
}
