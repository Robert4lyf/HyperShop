package com.robert4lyf.hypershop.core.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WrongReportTest {
    @Test
    fun readsAsPlainLinesWithNothingSpanningTwo() {
        val r =
            WrongReport(
                "Review",
                "olives",
                listOf("½ cup soft pitted\nblack olives" to "½ cup soft"),
                "olives: +120ml (new)",
                "  ",
                "2ff919ec09cb",
                "2026-10-24T10:00:00Z",
            )
        assertEquals(
            """
            --- This is wrong (Review, 2026-10-24T10:00:00Z, catalogue 2ff919ec09cb)
            Item: olives
            Line: ½ cup soft pitted black olives
              read as: ½ cup soft
            Showed: olives: +120ml (new)
            Expected: (not said)

            """.trimIndent(),
            r.text(),
        )
    }
}
