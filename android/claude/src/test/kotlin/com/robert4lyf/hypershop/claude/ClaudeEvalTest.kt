package com.robert4lyf.hypershop.claude

import com.robert4lyf.hypershop.core.model.ParsedIngredient
import com.robert4lyf.hypershop.core.model.QtyUnit
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.parse.Normaliser
import com.robert4lyf.hypershop.core.parse.RuleParser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

/**
 * Step 4.6 (tools/eval-claude): Claude Haiku 5.5, Sonnet 5.5, Opus 5.5 and the
 * rule parser on the frozen 80 golden lines, the 25 UK lines and the page
 * fixtures; per-field accuracy (spec R11), cost and output tokens. Run once:
 * `tools/eval-claude/run.sh` (needs HYPERSHOP_ANTHROPIC_API_KEY; spends about $0.10).
 * Without -Deval=true it is skipped.
 */
class ClaudeEvalTest {
    private val norm = Normaliser()

    private data class Gold(
        val set: String,
        val raw: String,
        val name: String,
        val qty: Double?,
        val qtyMax: Double?,
        val unit: QtyUnit?,
    )

    private fun load(f: String): List<Gold> =
        Json.parseToJsonElement(File("../../testdata/ingredients/$f").readText()).jsonArray.map {
            val e = it.jsonObject.getValue("expected").jsonObject

            fun d(k: String) = e[k]?.takeIf { x -> x !is JsonNull }?.jsonPrimitive?.double
            Gold(
                if (f.startsWith("frozen")) "nyt-80" else "uk-25",
                e.getValue("raw").jsonPrimitive.content,
                e.getValue("name").jsonPrimitive.content,
                d("quantity"),
                d("quantity_max"),
                e["unit"]?.takeIf { x -> x !is JsonNull }?.jsonPrimitive?.content?.let { QtyUnit.fromSerial(it) },
            )
        }

    private fun close(
        a: Rational?,
        b: Double?,
    ) = if (a == null || b == null) a == null && b == null else abs(a.toDouble() - b) <= 0.01 * maxOf(abs(b), 1e-9)

    private data class Score(
        var n: Int = 0,
        var name: Int = 0,
        var qty: Int = 0,
        var unit: Int = 0,
    )

    private fun score(
        gold: List<Gold>,
        got: List<ParsedIngredient?>,
    ): Score {
        val s = Score()
        for ((g, p) in gold.zip(got)) {
            s.n++
            if (p == null) continue
            if (norm.compareKey(p.name) == norm.compareKey(g.name)) s.name++
            if (close(p.quantity, g.qty) && close(p.quantityMax, g.qtyMax)) s.qty++
            if (p.unit == g.unit) s.unit++
        }
        return s
    }

    @Test
    fun eval() {
        assumeTrue(System.getProperty("eval") == "true", "run with tools/eval-claude/run.sh")
        val key = System.getenv("HYPERSHOP_ANTHROPIC_API_KEY")
        assumeTrue(!key.isNullOrBlank(), "HYPERSHOP_ANTHROPIC_API_KEY not set")
        val gold = load("frozen.json") + load("synthetic-uk.json")
        val client = ClaudeClient(apiKey = { key })
        val rows =
            StringBuilder("| parser | set | lines | name | qty | unit | cost (USD) | output tokens |\n|---|---|---|---|---|---|---|---|\n")

        fun pct(
            a: Int,
            n: Int,
        ) = "%.0f%%".format(100.0 * a / n)

        val rules = RuleParser()
        for ((set, g) in gold.groupBy { it.set }) {
            val s = score(g, g.map { rules.parse(it.raw) })
            rows.append("| rules | $set | ${s.n} | ${pct(s.name, s.n)} | ${pct(s.qty, s.n)} | ${pct(s.unit, s.n)} | 0 | 0 |\n")
        }
        for (m in ClaudeModel.entries) {
            for ((set, g) in gold.groupBy { it.set }) {
                // One call per batch of 15 lines, as an import would send them.
                var cost = 0.0
                var out = 0L
                val got = ArrayList<ParsedIngredient?>()
                for (chunk in g.chunked(15)) {
                    val r = runBlocking { client.parse(ClaudeInput.Lines(chunk.map { it.raw }), m) }
                    r.usage?.let {
                        cost += m.costUsd(it)
                        out += it.outputTokens
                    }
                    val ings = (r as? ClaudeResult.Parsed)?.recipe?.ingredients.orEmpty()
                    // Claude may split "X and Y"; match each golden line by its raw text.
                    got += chunk.map { c -> ings.firstOrNull { it.raw.trim() == c.raw.trim() } }
                }
                val s = score(g, got)
                rows.append(
                    "| ${m.id} | $set | ${s.n} | ${pct(
                        s.name,
                        s.n,
                    )} | ${pct(s.qty, s.n)} | ${pct(s.unit, s.n)} | ${"%.4f".format(cost)} | $out |\n",
                )
            }
        }
        val report = "# Claude eval (Step 4.6)\n\nScoring: docs/parser-spec.md R11. Page fixtures: see testdata/pages (Step 5.1).\n\n$rows"
        File("../../docs/reports").mkdirs()
        File("../../docs/reports/eval-claude.md").writeText(report)
        println(report)
    }
}
