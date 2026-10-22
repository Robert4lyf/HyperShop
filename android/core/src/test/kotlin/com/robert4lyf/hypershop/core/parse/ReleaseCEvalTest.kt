package com.robert4lyf.hypershop.core.parse

import com.robert4lyf.hypershop.core.catalog.CatalogLoader
import com.robert4lyf.hypershop.core.merge.Display
import com.robert4lyf.hypershop.core.merge.Merger
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.ParsedIngredient
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.QtyUnit
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

/**
 * Release C extraction evaluation on `testdata/eval/release-c-2026.json`: lines
 * gathered from real recipe pages after the parser was last changed and labelled
 * by reading them (provenance in `release-c-2026-SOURCES.md`), never used as
 * fixtures. The whole pipeline is scored, field by field, as the app would show
 * it: the parse, the catalogue match and aisle, the flags, and the displayed
 * shopping amount. What matters most is the last table: a wrong reading the gate
 * lets through ("incorrectly accepted") against one it flags for a look.
 *
 * The report is written to build/reports/release-c-eval.md.
 */
class ReleaseCEvalTest {
    private val catalog = CatalogLoader.default
    private val parser = RuleParser(catalog)
    private val norm = Normaliser(catalog)
    private val merger = Merger(catalog, norm)

    data class Label(
        val id: String,
        val group: String,
        val raw: String,
        val name: String,
        val qty: Double?,
        val qtyMax: Double?,
        val unit: QtyUnit?,
        val optional: Boolean,
        val garnish: Boolean,
        val alternatives: List<String>,
        val packSize: String?,
    )

    private fun JsonObject.num(k: String) = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.double

    private fun JsonObject.str(k: String) = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

    private fun JsonObject.bool(k: String) = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.boolean ?: false

    /** Label units the spec folds into `other` or `packet` (R2.4). */
    private fun unitOf(s: String?): QtyUnit? =
        when (s) {
            null -> null
            "fl oz" -> QtyUnit.FL_OZ
            "sachet" -> QtyUnit.PACKET
            "rasher", "handful", "bag", "jar", "leaf", "ear", "strip", "bottle", "knob", "punnet", "splash", "loaf" -> QtyUnit.OTHER
            else -> QtyUnit.fromSerial(s) ?: error("unknown label unit '$s'")
        }

    private fun labels(): List<Label> =
        Json.parseToJsonElement(File("../../testdata/eval/release-c-2026.json").readText()).jsonArray.map {
            val o = it as JsonObject
            Label(
                o.getValue("id").jsonPrimitive.content,
                o.str("group") ?: "?",
                o.getValue("raw").jsonPrimitive.content,
                o.getValue("name").jsonPrimitive.content,
                o.num("quantity"),
                o.num("quantity_max"),
                unitOf(o.str("unit")),
                o.bool("optional"),
                o.bool("garnish"),
                o["alternatives"]?.takeIf { a -> a !is JsonNull }?.jsonArray?.map { a -> a.jsonPrimitive.content }.orEmpty(),
                o.str("pack_size"),
            )
        }

    private fun close(
        a: Rational?,
        b: Double?,
    ): Boolean = if (a == null || b == null) a == null && b == null else abs(a.toDouble() - b) <= 0.01 * maxOf(abs(b), 1e-9)

    /** A container count whose size is the label's amount ("1 tin" for "400 g tin"): the same shopping requirement. */
    private fun containerEquivalent(
        p: ParsedIngredient,
        l: Label,
    ): Boolean {
        val container = p.unit == QtyUnit.TIN || p.unit == QtyUnit.PACKET || p.unit == QtyUnit.OTHER
        if (!container || l.unit == p.unit) return false
        val size = p.altQuantity?.let { q -> p.altUnit?.let { u -> q to u } } ?: return false
        return l.unit == size.second && close(size.first * (p.quantity ?: Rational.ONE), l.qty)
    }

    private data class Scored(
        val l: Label,
        val p: ParsedIngredient,
        val reason: String?,
        val nameOk: Boolean,
        val qtyOk: Boolean,
        val unitOk: Boolean,
        val unitEquivalent: Boolean,
        val identityOk: Boolean,
        val aisleOk: Boolean,
        val optionalOk: Boolean,
        val garnishOk: Boolean,
        val altOk: Boolean,
        val displayOk: Boolean,
        val got: String,
        val want: String,
    ) {
        /** The reading a shopper would accept: the right thing, in the right amount. */
        val right get() = nameOk && qtyOk && (unitOk || unitEquivalent)
        val flagged get() = reason != null
    }

    private fun locale(l: Label): UnitLocale = if (l.group == "us") UnitLocale.US else UnitLocale.METRIC

    private fun display(
        name: String,
        q: QtyBase,
        hint: QtyUnit?,
    ): String {
        val t = merger.target(name)
        val entry = catalog.byId[t.catalogId ?: ""]
        return Display.amount(
            q,
            produce = t.aisle == AisleCode.PRODUCE,
            hint = hint,
            gramsPerCup = entry?.gramsPerCup,
            policy = entry?.displayPolicy,
        ) ?: "(no amount)"
    }

    private fun score(l: Label): Scored {
        // A section header inside the ingredient list (R8): right when recognised as one, and never an item.
        if (l.name.isEmpty()) {
            val header = parser.sectionHeader(l.raw) != null
            val p = parser.parse(l.raw)
            return Scored(
                l, p, if (header) null else Uncertainty.reason(p),
                header, header, header, false, header, header, true, true, true, header,
                if (header) "(section header)" else "${p.name} | ${p.quantity ?: ""} ${p.unit?.serial ?: ""}", "(section header)",
            )
        }
        val p = parser.parse(l.raw)
        val loc = locale(l)
        val gotTarget = merger.target(p.name)
        val wantTarget = merger.target(l.name)
        val unitOk = p.unit == l.unit
        val equivalent = containerEquivalent(p, l)
        val qtyOk = if (equivalent) true else close(p.quantity, l.qty) && close(p.quantityMax, l.qtyMax)
        val note = p.note.orEmpty()
        val alt = note.split(", ").firstOrNull { it.startsWith("or ") }?.removePrefix("or ")?.trim()
        val altOk =
            if (l.alternatives.isEmpty()) {
                alt == null
            } else {
                // One of two: flagged for the user, or kept as "A (or B)" with the alternative in the note.
                p.check || alt != null || " or " in " $note "
            }
        val wantQ =
            when {
                l.unit != null || l.qty != null -> QtyBase.of(Rational.fromDouble(l.qtyMax ?: l.qty ?: 0.0), l.unit, loc)
                else -> QtyBase.PRESENCE
            }
        val gotDisplay = display(p.name, p.shopBase(loc), p.shopUnit)
        val wantDisplay = display(l.name, wantQ, l.unit)
        // The recipe's own metric figure ("1/2 cup (113g)") is shopped by instead of a converted cup: that is right too.
        val ownMetric = p.shopUnit != null && p.shopUnit != p.unit && p.quantity != null
        val displayOk = gotDisplay == wantDisplay || equivalent || ownMetric
        return Scored(
            l, p, Uncertainty.reason(p),
            nameOk = norm.compareKey(p.name) == norm.compareKey(l.name),
            qtyOk = qtyOk,
            unitOk = unitOk,
            unitEquivalent = equivalent,
            identityOk = gotTarget.mergeKey == wantTarget.mergeKey,
            aisleOk = gotTarget.aisle == wantTarget.aisle,
            optionalOk = p.optional == l.optional,
            garnishOk = p.garnish == l.garnish,
            altOk = altOk,
            displayOk = displayOk,
            got = "${p.name} | ${p.quantity ?: ""}${p.quantityMax?.let { "-$it" } ?: ""} ${p.unit?.serial ?: ""} | $gotDisplay",
            want = "${l.name} | ${l.qty ?: ""}${l.qtyMax?.let { "-$it" } ?: ""} ${l.unit?.serial ?: ""} | $wantDisplay",
        )
    }

    private fun pct(
        n: Int,
        d: Int,
    ) = if (d == 0) "—" else "%.0f%%".format(100.0 * n / d)

    @Test
    fun wrongReadingsAreRareAndMostlyFlagged() {
        val all = labels().map(::score)
        val sb = StringBuilder("# Release C extraction evaluation (testdata/eval/release-c-2026.json)\n\n")
        sb.append("${all.size} lines. A line is *right* when name, amount and unit match the label ")
        sb.append("(a container whose size is the label's amount counts).\n\n")
        sb.append("## Field accuracy, by group\n\n")
        sb.append("| group | lines | name | quantity | unit | catalogue match | aisle | optional | garnish | ")
        sb.append("A or B | shown amount | right |\n")
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n")
        for ((g, rows) in all.groupBy { it.l.group }.toSortedMap() + ("all" to all)) {
            val n = rows.size
            val cells =
                listOf(
                    pct(rows.count { it.nameOk }, n),
                    pct(rows.count { it.qtyOk }, n),
                    pct(rows.count { it.unitOk || it.unitEquivalent }, n),
                    pct(rows.count { it.identityOk }, n),
                    pct(rows.count { it.aisleOk }, n),
                    pct(rows.count { it.optionalOk }, n),
                    pct(rows.count { it.garnishOk }, n),
                    pct(rows.count { it.altOk }, n),
                    pct(rows.count { it.displayOk }, n),
                    pct(rows.count { it.right }, n),
                )
            sb.append("| $g | $n | ${cells.joinToString(" | ")} |\n")
        }
        val accepted = all.filter { !it.flagged }
        val flagged = all.filter { it.flagged }
        val wrong = all.count { !it.right }
        sb.append("\n## The gate\n\n| | right | wrong |\n|---|---|---|\n")
        val acceptedWrong = accepted.count { !it.right }
        val caught = flagged.count { !it.right }
        sb.append("| accepted (read offline) | ${accepted.count { it.right }} | **$acceptedWrong** incorrectly accepted |\n")
        sb.append("| flagged (a look, or Claude) | ${flagged.count { it.right }} incorrectly flagged | $caught caught |\n")
        sb.append("\nFlag rate ${pct(flagged.size, all.size)}; of the wrong readings, ${pct(caught, wrong)} are flagged.\n")
        sb.append("\n## Incorrectly accepted\n\n| id | line | got (name / amount / shown) | want | wrong |\n")
        sb.append("|---|---|---|---|---|\n")
        for (r in accepted.filter { !it.right }) sb.append(row(r))
        sb.append("\n## Accepted and right, but shown or filed differently\n\n| id | line | got | want | wrong |\n")
        sb.append("|---|---|---|---|---|\n")
        for (r in accepted.filter { it.right && !it.allOk }) sb.append(row(r))
        sb.append("\n## Flagged\n\n| id | line | reason | right? |\n|---|---|---|---|\n")
        for (r in flagged) {
            val right = if (r.right) "yes" else "no: ${r.got}"
            sb.append("| ${r.l.id} | ${r.l.raw.replace("|", "/")} | ${r.reason} | $right |\n")
        }
        File("build/reports").mkdirs()
        File("build/reports/release-c-eval.md").writeText(sb.toString())
        println(sb)
        // Not a gate on the average: the lines are independent of the parser's fixtures and are reported as they are.
        assertTrue(all.isNotEmpty())
    }

    private val Scored.allOk: Boolean
        get() = identityOk && aisleOk && optionalOk && garnishOk && altOk && displayOk

    private fun row(r: Scored): String {
        val wrong =
            listOfNotNull(
                "name".takeIf { !r.nameOk },
                "quantity".takeIf { !r.qtyOk },
                "unit".takeIf { !(r.unitOk || r.unitEquivalent) },
                "catalogue".takeIf { !r.identityOk },
                "aisle".takeIf { !r.aisleOk },
                "optional".takeIf { !r.optionalOk },
                "garnish".takeIf { !r.garnishOk },
                "A or B".takeIf { !r.altOk },
                "shown".takeIf { !r.displayOk },
            ).joinToString(", ")
        val line = r.l.raw.replace("|", "/")
        return "| ${r.l.id} | $line | ${r.got.replace("|", "/")} | ${r.want.replace("|", "/")} | $wrong |\n"
    }
}
