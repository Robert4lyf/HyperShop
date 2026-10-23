package com.robert4lyf.hypershop.core.parse

import org.junit.jupiter.api.Assertions.assertTrue

/**
 * The totals each set may not fall below, in lines (not percentages, so one
 * line is one line). Set from the measured reading; they only move up. [EvalGate]
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

    val RELEASE_C = Floors(right = 259, name = 259, quantity = 284, unit = 281, catalogue = 258, shown = 282, acceptedWrongAtMost = 11)
    val FINAL = Floors(right = 115, name = 118, quantity = 134, unit = 135, catalogue = 118, shown = 133, acceptedWrongAtMost = 11)

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
        val low =
            listOfNotNull(
                "right".takeIf { got.right < f.right },
                "name".takeIf { got.name < f.name },
                "quantity".takeIf { got.quantity < f.quantity },
                "unit".takeIf { got.unit < f.unit },
                "catalogue".takeIf { got.catalogue < f.catalogue },
                "shown".takeIf { got.shown < f.shown },
                "incorrectly accepted".takeIf { got.acceptedWrongAtMost > f.acceptedWrongAtMost },
            )
        assertTrue(low.isEmpty(), "below the floor on ${low.joinToString()}: $got against $f")
    }
}
