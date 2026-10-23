package com.robert4lyf.hypershop.core.parse

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Release C extraction evaluation on `testdata/eval/release-c-2026.json`: lines
 * gathered from real recipe pages after the parser was last changed and labelled
 * by reading them (provenance in `release-c-2026-SOURCES.md`), never used as
 * fixtures. The whole pipeline is scored, field by field, as the app would show
 * it ([ExtractionEval]); the report goes to build/reports/release-c-eval.md.
 *
 * It is a gate as well as a report: every line is held to its recorded reading
 * ([EvalGate], `release-c-2026-baseline.json`), and the totals to floors that
 * only move up.
 */
class ReleaseCEvalTest {
    private val eval = ExtractionEval("testdata/eval/release-c-2026.json")

    @Test
    fun report() {
        val text = eval.report("Release C extraction evaluation")
        File("build/reports").mkdirs()
        File("build/reports/release-c-eval.md").writeText(text)
        println(text)
    }

    @Test
    fun everyLineReadsAsRecorded() {
        val problems = EvalGate.check(eval, "testdata/eval/release-c-2026-baseline.json", LABELS_AT_GATE)
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun totalsHoldTheirFloors() {
        EvalFloors.check(eval, EvalFloors.RELEASE_C)
    }

    private companion object {
        /** The labels' checksum when the gate was set (Q.2b); a later change needs a recorded correction. */
        const val LABELS_AT_GATE = "0ab2c23c7752"
    }
}
