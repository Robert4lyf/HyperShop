package com.robert4lyf.hypershop.core.parse

import org.junit.jupiter.api.Assertions.assertTrue

/**
 * The totals each set may not fall below, in lines (not percentages, so one
 * line is one line), held exactly: they only move up, and a rise must be written in. [EvalGate]
 * already fails on any single line, so these are what a reviewer reads when a
 * baseline update goes the wrong way: an update that loses lines breaks a floor.
 */
object EvalFloors {
    data class Floors(
        val right: Int,
        val name: Int,
        val quantity: Int,
        val unit: Int,
        val catalogue: Int,
        val shown: Int,
        /** Wrong readings the gate lets through: a ceiling, not a floor. */
        val acceptedWrongAtMost: Int,
    )

    val RELEASE_C = Floors(right = 262, name = 262, quantity = 284, unit = 281, catalogue = 261, shown = 282, acceptedWrongAtMost = 9)
    val FINAL = Floors(right = 116, name = 118, quantity = 135, unit = 135, catalogue = 118, shown = 134, acceptedWrongAtMost = 10)
    val T = Floors(right = 113, name = 113, quantity = 134, unit = 134, catalogue = 110, shown = 131, acceptedWrongAtMost = 15)

    fun check(
        eval: ExtractionEval,
        f: Floors,
    ) {
        val a = eval.all
        val got =
            Floors(
                right = a.count { it.right },
                name = a.count { it.nameOk },
                quantity = a.count { it.qtyOk },
                unit = a.count { it.unitOk || it.unitEquivalent },
                catalogue = a.count { it.identityOk },
                shown = a.count { it.displayOk },
                acceptedWrongAtMost = eval.acceptedWrong.size,
            )
        println("${eval.path}: $got of ${a.size}")
        // Exactly: lower is a regression, higher is slack the floor must be raised to.
        assertTrue(got == f, "totals moved: $got against the floors $f; a fall is a regression, a rise means raising the floors")
    }
}
