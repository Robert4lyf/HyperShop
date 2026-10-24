package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.catalog.CatalogLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** W.2: a row in the way the thing is bought, never less than the recipes need, and the recipes' amount beside it. */
class DisplayBuyTest {
    private val cat = CatalogLoader.default

    private fun row(
        vararg lines: String,
        key: String,
        units: UnitsSetting = UnitsSetting.METRIC,
    ): RowText {
        val l = add(ShoppingList(), lines.toList()).first
        val item = l.itemByKey("cat:$key")!!
        return Display.row(l, item, cat.byId[item.catalogId], units)
    }

    @Test
    fun garlicIsBoughtInBulbs() {
        assertEquals(RowText("1 bulb garlic", "3 cloves"), row("3 garlic cloves", key = "garlic"))
        assertEquals(RowText("2 bulbs garlic", "14 cloves"), row("8 garlic cloves", "6 cloves garlic", key = "garlic"))
        assertEquals("1 bulb garlic", row("1 garlic bulb", key = "garlic").main)
    }

    @Test
    fun halfAnOnionIsOneAndAWeightIsCounted() {
        assertEquals(RowText("1 onion", "½ in the recipe"), row("½ onion", key = "onion"))
        // 450 g at about 160 g an onion: three, rounded up, never fewer than needed.
        assertEquals(RowText("3 onions", "450g in the recipe"), row("450g onions", key = "onion"))
        assertEquals(RowText("2 onions", null), row("2 onions", key = "onion"))
    }

    @Test
    fun aPotIsCountedFromItsSizeWithTheNeedBesideIt() {
        assertEquals(RowText("1 pot double cream", "150ml needed"), row("150ml double cream", key = "double_cream"))
        assertEquals(RowText("2 pots double cream", "500ml needed"), row("500ml double cream", key = "double_cream"))
    }

    @Test
    fun herbsWithNoAmountAreOnePack() {
        assertEquals("1 pack coriander", row("Fresh coriander, to serve", key = "coriander").main)
        // Counted in its own container: as written.
        assertEquals("1 bunch coriander", row("1 bunch coriander", key = "coriander").main)
    }

    @Test
    fun thingsSoldInPacksOfNoFixedNumberStayCounted() {
        assertEquals("2 chicken breasts", row("2 chicken breasts", key = "chicken_breast").main)
    }

    @Test
    fun staplesAndAsWrittenAreUnchanged() {
        assertEquals("salt", row("1 tsp salt", key = "salt").main)
        assertEquals("½ onion", row("½ onion", key = "onion", units = UnitsSetting.AS_WRITTEN).main)
    }

    @Test
    fun aPartlyBoughtRowKeepsItsAlreadyBoughtLine() {
        var l = add(ShoppingList(), listOf("2 onions")).first
        val onion = l.itemByKey("cat:onion")!!
        l = l.tick(onion.id, 1)!!
        l = add(l, listOf("½ onion")).first
        assertEquals(RowText("1 more onion", "2 already bought"), Display.row(l, l.items.getValue(onion.id), cat.byId["onion"]))
    }
}
