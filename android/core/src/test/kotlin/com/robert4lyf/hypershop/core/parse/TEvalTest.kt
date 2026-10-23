package com.robert4lyf.hypershop.core.parse

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Step T's held-out set, `testdata/eval/t-2026.json`: 135 real lines from ten
 * recipe pages in no other set, labelled and committed (T.5a) before a parser was
 * run on them (`t-2026-SOURCES.md`), so T's parser changes are measured on lines
 * they were not tuned to. Scored and gated as [FinalEvalTest] is; the report goes
 * to build/reports/t-eval.md.
 */
class TEvalTest {
    private val eval = ExtractionEval("testdata/eval/t-2026.json")

    @Test
    fun report() {
        val text = eval.report("Step T held-out extraction evaluation")
        File("build/reports").mkdirs()
        File("build/reports/t-eval.md").writeText(text)
        println(text)
    }

    @Test
    fun everyLineReadsAsRecorded() {
        val problems = EvalGate.check(eval, "testdata/eval/t-2026-baseline.json", LABELS_AT_GATE)
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun totalsHoldTheirFloors() {
        EvalFloors.check(eval, EvalFloors.T)
    }

    private companion object {
        /** The labels' checksum when the gate was set (T.5b); a later change needs a recorded correction. */
        const val LABELS_AT_GATE = "c296b1273101"
    }
}
