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
        assertEquals(RowText("1 bulb garlic", "3 cloves in the recipe"), row("3 garlic cloves", key = "garlic"))
        assertEquals(RowText("2 bulbs garlic", "14 cloves in the recipe"), row("8 garlic cloves", "6 cloves garlic", key = "garlic"))
        assertEquals("1 bulb garlic", row("1 garlic bulb", key = "garlic").main)
    }

    @Test
    fun halfAnOnionIsOneAndAWeightIsCounted() {
        assertEquals(RowText("1 onion", "½ in the recipe"), row("½ onion", key = "onion"))
        // 450 g at USDA's medium onion (110 g): five, rounded up, never fewer than needed.
        assertEquals(RowText("5 onions", "450g in the recipe"), row("450g onions", key = "onion"))
        assertEquals(RowText("2 onions", null), row("2 onions", key = "onion"))
    }

    @Test
    fun aPotIsCountedFromItsSizeWithTheNeedBesideIt() {
        assertEquals(RowText("1 pot double cream", "150ml needed"), row("150ml double cream", key = "double_cream"))
        assertEquals(RowText("2 pots double cream", "500ml needed"), row("500ml double cream", key = "double_cream"))
    }

    @Test
    fun aPackSizedInAnotherDimensionIsNeverGuessedShort() {
        // W.5 review: a 500 g carton cannot hold "700ml" without a density, so the row stays as written.
        assertEquals("700ml passata", row("700ml passata", key = "passata").main)
        assertEquals(RowText("2 cartons passata", "700g needed"), row("700g passata", key = "passata"))
        // Bought for its presence: one pack, the amount beside it.
        assertEquals("1 pack basil", row("4 cups basil leaves", key = "basil").main)
    }

    @Test
    fun aContainerNamesThePlural() {
        assertEquals("1 punnet cherry tomatoes", row("200g cherry tomatoes", key = "cherry_tomato").main)
        assertEquals("2 sprigs rosemary", row("2 sprigs rosemary", key = "rosemary").main)
    }

    @Test
    fun meatAndFishKeepTheirWeight() {
        // X.3: packs come in too many sizes for a pack count to help.
        assertEquals("800g chicken breast", row("800g chicken breast", key = "chicken_breast").main)
        assertEquals("500g beef mince", row("500g beef mince", key = "beef_mince").main)
    }

    @Test
    fun aWholeBirdIsNotBoughtForAWeight() {
        assertEquals("500g chicken", row("500g chicken", key = "chicken").main)
    }

    @Test
    fun chocolateSpreadIsWeighedAndJamIsAJar() {
        // X.1: Nutella is bought by weight in a jar (a cup is weighed; spoons stay spoons); jam is one jar.
        assertEquals("160g chocolate spread", row("1/2 cup Nutella", key = "chocolate_spread").main)
        assertEquals("200g chocolate spread", row("200g Nutella", key = "chocolate_spread").main)
        assertEquals(RowText("1 jar jam", "2tbsp needed"), row("2 tbsp strawberry jam", key = "jam"))
    }

    @Test
    fun aReviewOrAtHomeRowSaysWhatTheListWill() {
        val l = add(ShoppingList(), listOf("200g bananas")).first
        val item = l.itemByKey("cat:banana")!!
        assertEquals("2" to "bananas", Display.brief(l.total(item.id), cat.byId["banana"], "banana"))
        assertEquals(
            "1 jar" to "jam",
            Display.brief(
                add(ShoppingList(), listOf("2 tbsp jam")).first.let {
                    it.total(it.itemByKey("cat:jam")!!.id)
                },
                cat.byId["jam"],
                "jam",
            ),
        )
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
