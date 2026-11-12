package com.robert4lyf.hypershop.core.parse

import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.Rational
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class QuantityInputTest {
    @Test
    fun readsCompletePositiveAmountsWithoutDiscardingText() {
        assertEquals(Rational.of(500), QuantityInput.parse("1/2 kg")?.get(BaseKey.MASS))
        assertEquals(Rational.of(250), QuantityInput.parse("0.25 l")?.get(BaseKey.VOLUME))
        for (bad in listOf("", "0", "-1 g", "some", "half of 500 g", "200 g flour", "1-2 kg", "300 g plus 20 ml")) {
            assertNull(QuantityInput.parse(bad), bad)
        }
    }
}
