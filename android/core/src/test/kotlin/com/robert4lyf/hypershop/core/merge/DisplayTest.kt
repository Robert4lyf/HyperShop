package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.catalog.CatalogLoader
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.QtyUnit
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DisplayTest {
    private val cat = CatalogLoader.default

    @Test
    fun rounding() {
        assertEquals("3", Display.count(Rational.of(5, 2), null, produce = false))
        assertEquals("2½", Display.count(Rational.of(9, 4), null, produce = true))
        assertEquals("3 cloves", Display.count(Rational.of(5, 2), QtyUnit.CLOVE, produce = false))
        assertEquals("1 tin", Display.count(Rational.ONE, QtyUnit.TIN, produce = false))
        assertEquals("700 ml", Display.volume(Rational.of(700), UnitsSetting.METRIC))
        assertEquals("1.5 l", Display.volume(Rational.of(1500), UnitsSetting.METRIC))
        assertEquals("2 tbsp", Display.volume(Rational.of(30), UnitsSetting.METRIC, QtyUnit.TBSP))
        assertEquals("½ tsp", Display.volume(Rational.of(5, 2), UnitsSetting.METRIC, QtyUnit.TSP))
        assertEquals("230 g", Display.mass(Rational.of(227), UnitsSetting.METRIC))
        assertEquals("1.1 kg", Display.mass(Rational.of(1050), UnitsSetting.METRIC))
        assertEquals("8 oz", Display.mass(Rational.of(2268, 10), UnitsSetting.IMPERIAL))
        assertEquals("1¼ cups", Display.volume(Rational.of(300), UnitsSetting.IMPERIAL))
        assertEquals("⅓", Display.fraction(Rational.of(1, 3)))
    }

    @Test
    fun compoundAmountsUnlessTheCatalogueKnowsTheDensity() {
        val q = QtyBase.of(Rational.ONE, null, UnitLocale.METRIC) + QtyBase.of(Rational.of(200), QtyUnit.G, UnitLocale.METRIC)
        assertEquals("1 + 200 g", Display.amount(q, produce = true))
        val flour = QtyBase.of(Rational.of(100), QtyUnit.G, UnitLocale.METRIC) + QtyBase.of(Rational.ONE, QtyUnit.CUP, UnitLocale.US)
        assertEquals("230 g", Display.amount(flour, gramsPerCup = cat.byId.getValue("flour").gramsPerCup))
    }

    @Test
    fun rowsShowPluralsPackNotesAndPartialTicks() {
        var (l, _) = add(ShoppingList(), listOf("2 onions", "250g butter"))
        val onion = l.itemByKey("cat:onion")!!
        val butter = l.itemByKey("cat:butter")!!
        assertEquals(RowText("2 onions", null), Display.row(l, onion, cat.byId["onion"]))
        assertEquals(RowText("250 g butter", "1 × 250 g block"), Display.row(l, butter, cat.byId["butter"]))
        l = l.tick(onion.id, 1)!!
        l = add(l, listOf("1 onion")).first
        assertEquals(RowText("1 more onion", "2 already ticked"), Display.row(l, l.items.getValue(onion.id), cat.byId["onion"]))
        val salt = add(ShoppingList(), listOf("1 tsp salt")).first
        assertEquals("salt", Display.row(salt, salt.liveItems.single(), cat.byId["salt"]).main)
    }
}
