package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.parse.QuantityInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PackSuggestionTest {
    @Test
    fun roundsSuggestedPacksUpWithoutChangingTheRequirement() {
        val need = QuantityInput.parse("650 g")!!
        val pack = QuantityInput.parse("400 g")!!
        assertEquals(2L, PackSuggestion.forNeed(need, pack)?.packs)
        assertEquals(1L, PackSuggestion.forNeed(pack, pack)?.packs)
        assertEquals(QuantityInput.parse("650 g"), need)
        assertNull(PackSuggestion.forNeed(need, QuantityInput.parse("400 ml")!!))
        assertNull(PackSuggestion.forNeed(need, QuantityInput.parse("2 packets")!!))
        assertNull(PackSuggestion.forNeed(QtyBase.PRESENCE, pack))
    }
}
