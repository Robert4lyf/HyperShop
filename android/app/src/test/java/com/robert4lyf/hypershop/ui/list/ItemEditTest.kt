package com.robert4lyf.hypershop.ui.list

import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.Dimension
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.QtyUnit
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.RuleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The amount field's text reads back as the same total, and a unit chip keeps the number. */
class ItemEditTest {
    private fun q(
        key: BaseKey,
        n: Rational,
    ) = QtyBase(mapOf(key.encode() to n))

    @Test
    fun theTotalAsTextReadsBackAsTheSameTotal() {
        val totals =
            listOf(
                q(BaseKey.MASS, Rational.of(400)),
                q(BaseKey.MASS, Rational.of(1500)),
                q(BaseKey.VOLUME, Rational.of(250)),
                q(BaseKey.ITEMS, Rational.of(3)),
                q(BaseKey(Dimension.COUNT, QtyUnit.JAR), Rational.of(2)),
            )
        assertEquals(listOf("400 g", "1.5 kg", "250 ml", "3", "2 jar"), totals.map(ItemEdit::text))
        for (t in totals) assertEquals(t, RuleParser().parse(ItemEdit.text(t) + " x").shopBase(UnitLocale.METRIC))
        assertEquals("", ItemEdit.text(q(BaseKey.ITEMS, Rational.of(2)) + q(BaseKey.MASS, Rational.of(200))))
        assertEquals("", ItemEdit.text(QtyBase.PRESENCE))
    }

    @Test
    fun aUnitChipSwapsTheUnitAndKeepsTheNumber() {
        assertEquals("400 kg", ItemEdit.withUnit("400 g", "kg"))
        assertEquals("1 1/2 tin", ItemEdit.withUnit("1 1/2 cups", "tin"))
        assertEquals("2", ItemEdit.withUnit("2 packets", ItemEdit.EACH))
        assertEquals("1 jar", ItemEdit.withUnit("", "jar"))
        assertEquals("packet", ItemEdit.unitOf("2 packets"))
        assertEquals(ItemEdit.EACH, ItemEdit.unitOf("3"))
        assertEquals("bunch", ItemEdit.unitOf("2 bunches"))
        assertNull(ItemEdit.unitOf("2 cups"))
        assertNull(ItemEdit.unitOf("lots"))
    }
}
