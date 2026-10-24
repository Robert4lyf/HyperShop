package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.catalog.CatalogLoader
import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.QtyUnit
import com.robert4lyf.hypershop.core.model.Rational
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** V.6, from the phone: one thing written two ways adds up to one amount. */
class SameThingTest {
    @Test
    fun celeryStalksAndSticksAreOneCount() {
        val (l, _) = add(ShoppingList(), listOf("2 celery stalks", "2 sticks celery"))
        val item = l.itemByKey("cat:celery")!!
        val total = l.total(item.id)
        assertEquals(Rational.of(4), total[BaseKey(com.robert4lyf.hypershop.core.model.Dimension.COUNT, QtyUnit.STICK)])
        assertEquals(1, total.parts.size)
        // A stick of butter is not a stalk of anything.
        assertEquals(QtyUnit.STICK, recipe(listOf("1 stick butter")).lines.single().parsed.unit)
    }

    @Test
    fun aCupOfOlivesIsShownInGramsBesideGrams() {
        // 100 g from one recipe and ½ cup sliced from another: one weight, not "100g + 120ml".
        val (l, _) = add(ShoppingList(), listOf("100g pitted black olives", "1/2 cup black olive slices"))
        val item = l.itemByKey("cat:olive")!!
        val shown =
            Display.amount(
                l.total(item.id),
                gramsPerCup = CatalogLoader.default.byId.getValue("olive").gramsPerCup,
                policy = CatalogLoader.default.byId.getValue("olive").displayPolicy,
            )
        assertEquals(false, shown!!.contains("ml"), shown)
        assertEquals(true, shown.endsWith("g"), shown)
    }
}
