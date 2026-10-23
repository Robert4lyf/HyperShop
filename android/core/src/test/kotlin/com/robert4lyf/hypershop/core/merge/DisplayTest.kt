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
        assertEquals("700ml", Display.volume(Rational.of(700), UnitsSetting.METRIC))
        assertEquals("1.5l", Display.volume(Rational.of(1500), UnitsSetting.METRIC))
        assertEquals("2tbsp", Display.volume(Rational.of(30), UnitsSetting.METRIC, QtyUnit.TBSP))
        assertEquals("½tsp", Display.volume(Rational.of(5, 2), UnitsSetting.METRIC, QtyUnit.TSP))
        assertEquals("230g", Display.mass(Rational.of(227), UnitsSetting.METRIC))
        assertEquals("1.1kg", Display.mass(Rational.of(1050), UnitsSetting.METRIC))
        assertEquals("8oz", Display.mass(Rational.of(2268, 10), UnitsSetting.IMPERIAL))
        assertEquals("1¼ cups", Display.volume(Rational.of(300), UnitsSetting.IMPERIAL))
        assertEquals("⅓", Display.fraction(Rational.of(1, 3)))
    }

    @Test
    fun compoundAmountsUnlessTheCatalogueKnowsTheDensity() {
        val q = QtyBase.of(Rational.ONE, null, UnitLocale.METRIC) + QtyBase.of(Rational.of(200), QtyUnit.G, UnitLocale.METRIC)
        assertEquals("1 + 200g", Display.amount(q, produce = true))
        val flour = QtyBase.of(Rational.of(100), QtyUnit.G, UnitLocale.METRIC) + QtyBase.of(Rational.ONE, QtyUnit.CUP, UnitLocale.US)
        assertEquals("230g", Display.amount(flour, gramsPerCup = cat.byId.getValue("flour").gramsPerCup))
    }

    @Test
    fun cupsBecomeMetricAndSpoonsStaySpoons() {
        fun amount(
            q: QtyBase,
            id: String,
            hint: QtyUnit? = null,
        ) = cat.byId.getValue(id).let { Display.amount(q, hint = hint, gramsPerCup = it.gramsPerCup, policy = it.displayPolicy) }
        // Cups of a solid: weighed with the catalogue's density (250 ml or 240 ml cups).
        assertEquals("120g", amount(QtyBase.of(Rational.ONE, QtyUnit.CUP, UnitLocale.METRIC), "cheddar", QtyUnit.CUP))
        assertEquals("85g", amount(QtyBase.of(Rational.of(3, 4), QtyUnit.CUP, UnitLocale.US), "mozzarella", QtyUnit.CUP))
        assertEquals("300g", amount(QtyBase.of(Rational.of(2), QtyUnit.CUP, UnitLocale.METRIC), "chicken", QtyUnit.CUP))
        // Frozen diced onions are sold by weight: "1 cup (115g) frozen diced onions".
        for (name in listOf("frozen diced onions", "frozen chopped onion", "frozen onions")) {
            assertEquals("frozen_diced_onion", cat.lookup(name)?.id, name)
        }
        // 113 g, rounded.
        assertEquals("120g", amount(QtyBase.of(Rational.ONE, QtyUnit.CUP, UnitLocale.US), "frozen_diced_onion", QtyUnit.CUP))
        // No density known for something bought by weight or count: the recipe's cups, not millilitres of okra
        // (quality pass Q.3: a conversion nobody shops by, and grams would be a guess). A metric cup is "1 cup" too.
        assertEquals("1 cup", amount(QtyBase.of(Rational.ONE, QtyUnit.CUP, UnitLocale.US), "okra", QtyUnit.CUP))
        assertEquals("1 cup", amount(QtyBase.of(Rational.ONE, QtyUnit.CUP, UnitLocale.METRIC), "okra", QtyUnit.CUP))
        assertEquals("1½ cups", amount(QtyBase.of(Rational.of(3, 2), QtyUnit.CUP, UnitLocale.US), "chickpeas", QtyUnit.CUP))
        // An amount that rounds up to a kilogram or a litre is said in kilograms or litres (2.2 lb of mascarpone).
        assertEquals("1kg", Display.mass(Rational.of(998), UnitsSetting.METRIC))
        assertEquals("1l", Display.volume(Rational.of(995), UnitsSetting.METRIC))
        assertEquals("990g", Display.mass(Rational.of(985), UnitsSetting.METRIC))
        // A liquid, or an amount not written in cups, is unchanged.
        assertEquals("240ml", amount(QtyBase.of(Rational.ONE, QtyUnit.CUP, UnitLocale.US), "milk", QtyUnit.CUP))
        assertEquals("200ml", amount(QtyBase.of(Rational.of(200), QtyUnit.ML, UnitLocale.METRIC), "okra", QtyUnit.ML))
        // Liquids in ml; spoons as written, even large amounts of a solid.
        assertEquals("500ml", amount(QtyBase.of(Rational.of(500), QtyUnit.ML, UnitLocale.METRIC), "milk", QtyUnit.ML))
        assertEquals("4tbsp", amount(QtyBase.of(Rational.of(4), QtyUnit.TBSP, UnitLocale.US), "butter", QtyUnit.TBSP))
        assertEquals("6tbsp", Display.volume(Rational.of(90), UnitsSetting.METRIC, QtyUnit.TBSP))
        // Mixed spoons stay spoons: 1 tbsp + 1 tsp of maple syrup isn't 20ml.
        assertEquals(QtyUnit.TBSP, Display.hintOf(listOf(QtyUnit.TBSP, QtyUnit.TSP, null)))
        assertEquals(null, Display.hintOf(listOf(QtyUnit.TBSP, QtyUnit.ML)))
        // Grams from one recipe and spoons from another: the spoons stay spoons.
        assertEquals(QtyUnit.TBSP, Display.hintOf(listOf(QtyUnit.G, QtyUnit.TBSP)))
        val mixed = QtyBase.of(Rational.of(75), QtyUnit.G, UnitLocale.METRIC) + QtyBase.of(Rational.of(4), QtyUnit.TBSP, UnitLocale.METRIC)
        assertEquals("75g + 4tbsp", Display.amount(mixed, hint = Display.hintOf(listOf(QtyUnit.G, QtyUnit.TBSP))))
        // Bought by the pack: "a handful" or "2 tbsp" is one pack; a count of two is two.
        assertEquals("1 pack", Display.packs(QtyBase.of(Rational.ONE, null, UnitLocale.METRIC), "pack"))
        assertEquals("1 pack", Display.packs(QtyBase.of(Rational.of(2), QtyUnit.TBSP, UnitLocale.METRIC), "pack"))
        assertEquals("2 bunches", Display.packs(QtyBase.of(Rational.of(2), null, UnitLocale.METRIC), "bunch"))
        assertEquals("1", Display.packs(QtyBase.of(Rational.ONE, null, UnitLocale.METRIC), "each"))
        assertEquals("1tbsp + 1tsp", Display.volume(Rational.of(20), UnitsSetting.METRIC, QtyUnit.TBSP))
        assertEquals("1½tbsp", Display.volume(Rational.of(45, 2), UnitsSetting.METRIC, QtyUnit.TBSP))
        assertEquals("2tsp", Display.volume(Rational.of(10), UnitsSetting.METRIC, QtyUnit.TBSP))
        // "As written": ml stays ml, a cup stays a cup.
        assertEquals("30ml", Display.volume(Rational.of(30), UnitsSetting.AS_WRITTEN, QtyUnit.ML))
        assertEquals("2tbsp", Display.volume(Rational.of(30), UnitsSetting.IMPERIAL, QtyUnit.ML))
        assertEquals("½tbsp", Display.volume(Rational.of(15, 2), UnitsSetting.METRIC, QtyUnit.TBSP))
    }

    @Test
    fun rowsShowPluralsPackNotesAndPartialTicks() {
        var (l, _) = add(ShoppingList(), listOf("2 onions", "250g butter"))
        val onion = l.itemByKey("cat:onion")!!
        val butter = l.itemByKey("cat:butter")!!
        assertEquals(RowText("2 onions", null), Display.row(l, onion, cat.byId["onion"]))
        assertEquals(RowText("250g butter", "1 × 250g block"), Display.row(l, butter, cat.byId["butter"]))
        l = l.tick(onion.id, 1)!!
        l = add(l, listOf("1 onion")).first
        assertEquals(RowText("1 more onion", "2 already bought"), Display.row(l, l.items.getValue(onion.id), cat.byId["onion"]))
        val salt = add(ShoppingList(), listOf("1 tsp salt")).first
        assertEquals("salt", Display.row(salt, salt.liveItems.single(), cat.byId["salt"]).main)
    }

    @Test
    fun threeTeaspoonsCarryIntoATablespoon() {
        // "1 tbsp" scaled by 1.95 servings: 29.25 ml is two tablespoons, not "1tbsp + 3tsp".
        assertEquals("2tbsp", Display.volume(Rational.of(117, 4), UnitsSetting.METRIC, QtyUnit.TBSP))
        assertEquals("1tbsp", Display.volume(Rational.of(14), UnitsSetting.METRIC, QtyUnit.TBSP))
        assertEquals("3tbsp", Display.volume(Rational.of(44), UnitsSetting.METRIC, QtyUnit.TBSP))
        assertEquals("1tbsp", Display.volume(Rational.of(14), UnitsSetting.IMPERIAL))
        assertEquals("2¾tsp", Display.volume(Rational.of(27, 2), UnitsSetting.METRIC, QtyUnit.TBSP))
    }

    @Test
    fun decimalsUseAPointWhateverTheLocale() {
        val before = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.GERMANY)
        try {
            assertEquals("1.1kg", Display.mass(Rational.of(1050), UnitsSetting.METRIC))
            assertEquals("0.1", Display.fraction(Rational.of(1, 8)))
        } finally {
            java.util.Locale.setDefault(before)
        }
    }
}
