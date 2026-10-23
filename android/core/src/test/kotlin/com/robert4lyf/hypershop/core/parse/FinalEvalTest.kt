package com.robert4lyf.hypershop.core.parse

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The final held-out set, `testdata/eval/final-2026.json`: 137 real lines from
 * ten recipe pages not in any other set, labelled and committed before a parser
 * was run on them (`final-2026-SOURCES.md`). Scored and gated as
 * [ReleaseCEvalTest] is; the report goes to build/reports/final-eval.md.
 */
class FinalEvalTest {
    private val eval = ExtractionEval("testdata/eval/final-2026.json")

    @Test
    fun report() {
        val text = eval.report("Final held-out extraction evaluation")
        File("build/reports").mkdirs()
        File("build/reports/final-eval.md").writeText(text)
        println(text)
    }

    @Test
    fun everyLineReadsAsRecorded() {
        val problems = EvalGate.check(eval, "testdata/eval/final-2026-baseline.json", LABELS_AT_GATE)
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun totalsHoldTheirFloors() {
        EvalFloors.check(eval, EvalFloors.FINAL)
    }

    private companion object {
        /** The labels' checksum when the gate was set (Q.2b); a later change needs a recorded correction. */
        const val LABELS_AT_GATE = "a9faf2c9a75d"
    }
}
