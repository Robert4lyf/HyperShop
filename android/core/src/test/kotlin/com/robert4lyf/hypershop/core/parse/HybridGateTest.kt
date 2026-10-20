package com.robert4lyf.hypershop.core.parse

import com.robert4lyf.hypershop.core.model.QtyUnit
import com.robert4lyf.hypershop.core.model.Rational
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

/**
 * The evidence for the hybrid pipeline (docs/design/simplify.md phase 3): on
 * the frozen golden lines, the UK lines and the 2026 held-out lines
 * (testdata/eval, written after the parser), how the rule parser does on the
 * lines [Uncertainty] passes versus the ones it flags. The gate: the passed
 * lines must score at least as well as Claude Haiku did on the same golden
 * sets (name 94 %, qty 92 %, docs/reports/eval-claude.md), or the app keeps
 * asking Claude for every import.
 *
 * The report is written to build/reports/hybrid-gate.md.
 */
class HybridGateTest {
    private val parser = RuleParser()
    private val norm = Normaliser()

    data class Line(
        val set: String,
        val id: String,
        val raw: String,
        val name: String,
        val qty: Double?,
        val qtyMax: Double?,
        val unit: QtyUnit?,
    )

    private fun JsonObject.num(k: String) = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.double

    private fun JsonObject.unit() = this["unit"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let { QtyUnit.fromSerial(it) }

    private fun golden(f: String): List<Line> =
        Json.parseToJsonElement(File("../../testdata/ingredients/$f").readText()).jsonArray.map {
            val e = it.jsonObject.getValue("expected").jsonObject
            Line(
                f.removeSuffix(".json"),
                it.jsonObject.getValue("id").jsonPrimitive.content,
                e.getValue("raw").jsonPrimitive.content,
                e.getValue("name").jsonPrimitive.content,
                e.num("quantity"),
                e.num("quantity_max"),
                e.unit(),
            )
        }

    private fun heldOut(): List<Line> =
        Json.parseToJsonElement(File("../../testdata/eval/held-out-2026.json").readText()).jsonArray.map {
            val o = it.jsonObject
            Line(
                "held-out-2026",
                o.getValue("id").jsonPrimitive.content,
                o.getValue("raw").jsonPrimitive.content,
                o.getValue("name").jsonPrimitive.content,
                o.num("quantity"),
                o.num("quantity_max"),
                o.unit(),
            )
        }

    private fun close(
        a: Rational?,
        b: Double?,
    ): Boolean = if (a == null || b == null) a == null && b == null else abs(a.toDouble() - b) <= 0.01 * maxOf(abs(b), 1e-9)

    private data class Scored(
        val line: Line,
        val reason: String?,
        val nameOk: Boolean,
        val qtyOk: Boolean,
        val got: String,
    )

    private fun score(lines: List<Line>): List<Scored> =
        lines.map { l ->
            val p = parser.parse(l.raw)
            Scored(
                l,
                Uncertainty.reason(p),
                norm.compareKey(p.name) == norm.compareKey(l.name),
                close(p.quantity, l.qty) && close(p.quantityMax, l.qtyMax) && p.unit == l.unit,
                "${p.name} | ${p.quantity}${p.quantityMax?.let { "-$it" } ?: ""} ${p.unit?.serial ?: ""}",
            )
        }

    private fun pct(
        n: Int,
        d: Int,
    ) = if (d == 0) "—" else "%.0f%%".format(100.0 * n / d)

    @Test
    fun passedLinesScoreAtLeastAsWellAsClaude() {
        val all = score(golden("frozen.json") + golden("synthetic-uk.json") + heldOut())
        val report = StringBuilder("# Hybrid gate: the rule parser on the lines it would keep\n\n")
        report.append("Uncertainty = the parser's `check` flag, no name, a digit with no amount read, or a sentence-length name.\n\n")
        report.append(
            "| set | lines | passed | passed name | passed qty | flagged | flagged name | flagged qty |\n" +
                "|---|---|---|---|---|---|---|---|\n",
        )
        for ((set, rows) in all.groupBy { it.line.set }.toSortedMap() + ("all" to all)) {
            val (flagged, passed) = rows.partition { it.reason != null }
            report.append(
                "| $set | ${rows.size} | ${passed.size} | ${pct(passed.count { it.nameOk }, passed.size)} | ${pct(
                    passed.count { it.qtyOk },
                    passed.size,
                )} | ${flagged.size} | ${pct(
                    flagged.count { it.nameOk },
                    flagged.size,
                )} | ${pct(flagged.count { it.qtyOk }, flagged.size)} |\n",
            )
        }
        report.append(
            "\n## Passed lines the rule parser still got wrong (what the gate would miss)\n\n" +
                "| set | id | line | got | want |\n|---|---|---|---|---|\n",
        )
        for (r in all.filter { it.reason == null && (!it.nameOk || !it.qtyOk) }) {
            report.append(
                "| ${r.line.set} | ${r.line.id} | ${r.line.raw.replace(
                    "|",
                    "/",
                )} | ${r.got} | ${r.line.name} / ${r.line.qty} ${r.line.unit?.serial ?: ""} |\n",
            )
        }
        report.append("\n## Flagged lines, with the reason\n\n| set | id | line | reason | right? |\n|---|---|---|---|---|\n")
        for (r in all.filter { it.reason != null }) {
            report.append(
                "| ${r.line.set} | ${r.line.id} | ${r.line.raw.replace(
                    "|",
                    "/",
                )} | ${r.reason} | ${if (r.nameOk && r.qtyOk) "yes" else "no"} |\n",
            )
        }
        File("build/reports").mkdirs()
        File("build/reports/hybrid-gate.md").writeText(report.toString())
        println(report)
        // The golden sets alone (the held-out lines are reported, not gated: they were written for this test).
        val gold = all.filter { it.line.set != "held-out-2026" && it.reason == null }
        val name = gold.count { it.nameOk }.toDouble() / gold.size
        val qty = gold.count { it.qtyOk }.toDouble() / gold.size
        assertTrue(name >= 0.94, "passed lines: name ${"%.1f".format(name * 100)}% < Haiku's 94%; see build/reports/hybrid-gate.md")
        assertTrue(qty >= 0.92, "passed lines: qty ${"%.1f".format(qty * 100)}% < Haiku's 92%; see build/reports/hybrid-gate.md")
    }
}
