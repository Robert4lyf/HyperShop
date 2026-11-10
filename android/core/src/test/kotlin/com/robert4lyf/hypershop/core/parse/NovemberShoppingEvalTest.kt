package com.robert4lyf.hypershop.core.parse

import com.robert4lyf.hypershop.core.catalog.CatalogLoader
import com.robert4lyf.hypershop.core.model.Dimension
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.UnitLocale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/** Observational first run: these labels were authored independently of parser output, before tuning. */
class NovemberShoppingEvalTest {
    @Test
    fun reportConsequentialSilentErrorsAndCorrectionCounts() {
        val catalog = CatalogLoader.default
        val parser = RuleParser(catalog)
        val labels = Json.parseToJsonElement(File("../../testdata/eval/2026-11-shopping/lines.json").readText()).jsonArray
        var silent = 0
        var corrections = 0
        var unflaggedMismatches = 0
        val rows =
            labels.map { value ->
                val l = value.jsonObject
                val raw = l.getValue("line").jsonPrimitive.content
                val p = parser.parse(raw)
                val wantedName = l["ingredient"]?.jsonPrimitive?.content
                val identity = wantedName == null || catalog.lookup(wantedName)?.id == catalog.lookup(p.name)?.id
                val packAmount = p.altQuantity
                val qty =
                    if (p.unit?.dimension == Dimension.COUNT && packAmount != null &&
                        p.altUnit?.dimension in setOf(Dimension.MASS, Dimension.VOLUME)
                    ) {
                        QtyBase.of(p.quantity?.times(packAmount), p.altUnit, UnitLocale.METRIC)
                    } else {
                        p.shopBase(UnitLocale.METRIC)
                    }
                val wanted = l["base"]?.jsonPrimitive?.content?.toDouble()
                val dimension = l["dimension"]?.jsonPrimitive?.content
                val amount =
                    wanted == null || (
                        qty.parts.size == 1 && qty.parts.keys.single() == dimension &&
                            kotlin.math.abs(qty.parts.values.single().toDouble() - wanted) < 0.001
                    )
                val review = l.getValue("review").jsonPrimitive.content == "true"
                val correct = identity && amount && (!review || p.check)
                if (!correct) corrections++
                if (!correct && !p.check) unflaggedMismatches++
                if ((!identity || !amount) && !p.check) silent++
                "| ${raw.replace("|", "/")} | ${p.name} | ${qty.parts} | ${p.check} | $correct |"
            }
        val report =
            "# November synthetic shopping evaluation\n\n" +
                "${labels.size} lines · $corrections label mismatches · " +
                "$unflaggedMismatches unflagged label mismatches.\n\n" +
                "Unflagged identity/quantity mismatches: $silent. " +
                "Explicit container sizes are expanded; no density is assumed.\n\n" +
                "Rule parser only. No live Claude results or user timing measurements. " +
                "Labels are model-authored, not human-adjudicated.\n\n" +
                "| Source | Ingredient | Base amount | Flagged | Meets label |\n|---|---|---|---|---|\n" + rows.joinToString("\n")
        File("build/reports").mkdirs()
        File("build/reports/november-shopping.md").writeText(report)
        println(report)
        assertEquals(
            File("../../testdata/eval/2026-11-shopping/rule-baseline.md").readText(),
            report,
            "The regression reading changed; review every changed line against the unchanged labels.",
        )
    }
}
