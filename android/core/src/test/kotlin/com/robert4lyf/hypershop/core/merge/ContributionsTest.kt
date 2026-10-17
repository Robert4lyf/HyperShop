package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.QtyUnit
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.Normaliser
import io.kotest.property.arbitrary.list
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContributionsTest {
    @Test
    fun mustNotMergePairsStaySeparate() {
        val pairs =
            listOf(
                "onion" to "red onion", "butter" to "peanut butter", "egg" to "eggplant", "milk" to "coconut milk",
                "cream" to "cream cheese", "black pepper" to "bell pepper", "paprika" to "smoked paprika",
                "flour" to "self-raising flour", "sugar" to "icing sugar", "olive oil" to "olives", "lemon" to "lemon juice",
                "lime" to "lime juice", "tomato" to "chopped tomatoes", "chicken" to "chicken stock", "garlic" to "garlic powder",
                "rice" to "rice vinegar", "coconut milk" to "coconut cream", "ginger" to "ground ginger",
            )
        val n = Normaliser()
        for ((a, b) in pairs) assertNotEquals(n.mergeKey(a), n.mergeKey(b), "$a / $b merged")
        val (l, _) = add(ShoppingList(), pairs.flatMap { listOf("1 ${it.first}", "1 ${it.second}") })
        assertEquals(pairs.flatMap { listOf(it.first, it.second) }.map { n.mergeKey(it) }.toSet().size, l.liveItems.size)
    }

    @Test
    fun samethingMergesAcrossSpellings() {
        val (l, _) = add(ShoppingList(), listOf("1 aubergine", "2 eggplants", "200 ml milk"))
        val (l2, _) = add(l, listOf("300 ml milk"))
        assertEquals(2, l2.liveItems.size)
        val milk = l2.itemByKey("cat:milk")!!
        assertEquals(Rational.of(500), l2.total(milk.id)[BaseKey.VOLUME])
        assertEquals(Rational.of(3), l2.total(l2.itemByKey("cat:aubergine")!!.id)[BaseKey.ITEMS])
    }

    @Test
    fun scalingNoScaleAndPresence() {
        val (l, _) = add(ShoppingList(), listOf("2 onions", "Salt, to taste"), servings = 8)
        assertEquals(Rational.of(4), l.total(l.itemByKey("cat:onion")!!.id)[BaseKey.ITEMS])
        assertTrue(l.total(l.itemByKey("cat:salt")!!.id).isPresence)
        val rescaled = l.rescaleUse(l.uses.keys.first(), 2)
        assertEquals(Rational.of(1), rescaled.total(rescaled.itemByKey("cat:onion")!!.id)[BaseKey.ITEMS])
    }

    @Test
    fun iHaveSomeIsSubtractedEarliestLineFirstAndNeverStored() {
        val lines = listOf("200 ml milk", "300 ml milk")
        val have = QtyBase.of(Rational.of(250), QtyUnit.ML, UnitLocale.METRIC)
        val (l, _) = add(ShoppingList(), lines) { mapOf("cat:milk" to RowDecision(haveSome = have)) }
        val milk = l.itemByKey("cat:milk")!!
        val cs = l.contributionsOf(milk.id)
        assertEquals(1, cs.size) // the first line was used up entirely
        assertEquals(Rational.of(250), cs.single().qty[BaseKey.VOLUME])
        val p = merger.preview(ShoppingList(), recipe(lines), mapOf("cat:milk" to RowDecision(haveSome = have)))
        assertEquals(LineStatus.PARTIAL, p.lineStatus[1L])
        assertEquals(LineStatus.PARTIAL, p.lineStatus[2L])
    }

    @Test
    fun inStockAndDefaults() {
        val inc = recipe(listOf("Salt, to taste", "2 onions", "parsley, to garnish"))
        val d = merger.defaultDecisions(inc, staples = setOf("cat:salt"))
        assertTrue(d.getValue("cat:salt").inStock)
        assertTrue(d.getValue("cat:parsley").inStock)
        assertFalse(d.getValue("cat:onion").inStock)
        val r = merger.apply(ShoppingList(), merger.preview(ShoppingList(), inc, d), 1) as ApplyResult.Applied
        assertEquals(listOf("cat:onion"), r.list.liveItems.map { it.mergeKey })
    }

    @Test
    fun manualAddAndEditQuantity() {
        val n = Normaliser()
        var (l, _) = add(ShoppingList(), listOf("3 onions"))
        val onion = l.itemByKey("cat:onion")!!
        l = l.editQuantity(onion.id, QtyBase.of(Rational.of(2), null, UnitLocale.METRIC), 5)
        assertEquals(Rational.of(2), l.total(onion.id)[BaseKey.ITEMS])
        l = l.manualAdd(merger.target("onion"), QtyBase.of(Rational.ONE, null, UnitLocale.METRIC), null, 6).first
        assertEquals(Rational.of(3), l.total(onion.id)[BaseKey.ITEMS])
        assertEquals(1, l.liveItems.size)
        assertEquals("cat:onion", n.mergeKey("Onion"))
    }
}
