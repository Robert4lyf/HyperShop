package com.robert4lyf.hypershop.core.parse

import com.robert4lyf.hypershop.core.model.QtyUnit
import com.robert4lyf.hypershop.core.model.Rational
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

/**
 * Step 2 check (PLAN): on the simple + comments_parens strata of the frozen
 * golden set, lines not held out, name ≥ 90% and qty ≥ 95% (spec R11). The
 * other strata and the UK set are reported for information only.
 *
 * The report is written to build/reports/parser-golden.md.
 */
class GoldenScoreTest {
    private val parser = RuleParser()
    private val norm = Normaliser()

    data class Row(
        val id: String,
        val stratum: String,
        val heldOut: Boolean,
        val raw: String,
        val nameOk: Boolean,
        val qtyOk: Boolean,
        val got: String,
        val want: String,
    )

    private fun load(f: String): List<JsonObject> =
        Json.parseToJsonElement(
            File("../../testdata/ingredients/$f").readText(),
        ).jsonArray.map {
            it.jsonObject
        }

    private fun num(
        o: JsonObject,
        k: String,
    ): Double? = o[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.double

    private fun close(
        a: Rational?,
        b: Double?,
    ): Boolean = if (a == null || b == null) a == null && b == null else abs(a.toDouble() - b) <= 0.01 * maxOf(abs(b), 1e-9)

    fun score(): List<Row> =
        (load("frozen.json") + load("synthetic-uk.json")).map { g ->
            val e = g.getValue("expected").jsonObject
            val raw = e.getValue("raw").jsonPrimitive.content
            val p = parser.parse(raw)
            val wantName = e.getValue("name").jsonPrimitive.content
            val wantUnit = e["unit"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let { QtyUnit.fromSerial(it) }
            val nameOk = norm.compareKey(p.name) == norm.compareKey(wantName)
            val qtyOk = close(p.quantity, num(e, "quantity")) && close(p.quantityMax, num(e, "quantity_max")) && p.unit == wantUnit
            Row(
                g.getValue("id").jsonPrimitive.content,
                g.getValue("stratum").jsonPrimitive.content,
                g.getValue("heldOut").jsonPrimitive.boolean,
                raw,
                nameOk,
                qtyOk,
                "${p.name} | ${p.quantity}${p.quantityMax?.let { "-$it" } ?: ""} ${p.unit?.serial ?: ""}",
                "$wantName | ${num(e, "quantity") ?: ""}${num(e, "quantity_max")?.let { "-$it" } ?: ""} ${wantUnit?.serial ?: ""}",
            )
        }

    @Test
    fun goldenGate() {
        val rows = score()
        val report = StringBuilder("# Rule parser on the golden set\n\n| stratum | lines | name | qty |\n|---|---|---|---|\n")

        fun pct(
            n: Int,
            d: Int,
        ) = if (d == 0) "—" else "%.0f%%".format(100.0 * n / d)
        val groups = rows.groupBy { if (it.heldOut) "${it.stratum} (held out)" else it.stratum }
        for ((k, rs) in groups.toSortedMap()) report.append(
            "| $k | ${rs.size} | ${pct(rs.count { it.nameOk }, rs.size)} | ${pct(rs.count { it.qtyOk }, rs.size)} |\n",
        )
        val gate = rows.filter { !it.heldOut && it.stratum in setOf("simple", "comments_parens") }
        val nameRate = gate.count { it.nameOk }.toDouble() / gate.size
        val qtyRate = gate.count { it.qtyOk }.toDouble() / gate.size
        report.append(
            "\n**Gate** (simple + comments_parens, not held out, ${gate.size} lines): name ${pct(
                gate.count { it.nameOk },
                gate.size,
            )} (≥ 90%), qty ${pct(
                gate.count { it.qtyOk },
                gate.size,
            )} (≥ 95%).\n\n## Misses\n\n| id | line | got | want |\n|---|---|---|---|\n",
        )
        for (r in rows.filter { !it.nameOk || !it.qtyOk }) report.append(
            "| ${r.id} | ${r.raw.replace("|", "/")} | ${r.got} | ${r.want} |\n",
        )
        File("build/reports").mkdirs()
        File("build/reports/parser-golden.md").writeText(report.toString())
        println(report)
        assertTrue(nameRate >= 0.90, "name accuracy ${"%.1f".format(nameRate * 100)}% < 90%; see build/reports/parser-golden.md")
        assertTrue(qtyRate >= 0.95, "qty accuracy ${"%.1f".format(qtyRate * 100)}% < 95%; see build/reports/parser-golden.md")
    }
}
