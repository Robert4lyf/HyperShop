package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.catalog.CatalogLoader
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.RuleParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * W.0: the list measured, not the parse. Each recipe in `testdata/eval/basket-2026.json` goes through the
 * app's own pipeline — the rule parser, `Merger.incoming`, a list, `Display.row` — and every basket entry is
 * scored on what the list would say: the right item, bought the right way (each / weight / jar / …), in an
 * amount a shopper would accept. The labels were written from the pages alone, before Step W changed
 * anything (`basket-2026-SOURCES.md`); they are never revised to improve a score. The report goes to
 * build/reports/basket-eval.md.
 */
class BasketEvalTest {
    private val catalog = CatalogLoader.default
    private val parser = RuleParser(catalog)
    private val merger = Merger(catalog)

    private class Accept(
        val buy: String,
        val min: Double?,
        val max: Double?,
    )

    private class Entry(
        val recipe: String,
        val item: String,
        val accept: List<Accept>,
        val optional: Boolean,
        val lines: List<Int>,
    )

    /** What the list says for one item: how it is bought, and how much. */
    private data class Shown(
        val buy: String,
        val amount: Double?,
        val text: String,
    )

    private data class Scored(
        val entry: Entry,
        val shown: Shown?,
        val found: Boolean,
        /** Filed as the label's item (the catalogue's same thing): what makes two recipes' rows one. */
        val named: Boolean,
        val wayOk: Boolean,
        val amountOk: Boolean,
    ) {
        val right get() = found && wayOk && amountOk
    }

    private fun num(
        o: JsonObject,
        k: String,
    ) = o[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.double

    /** Reads the list's amount text ("2", "500g", "1 jar", "3 cloves", "½ cup") as a way of buying and a number. */
    private fun shown(text: String?): Shown {
        if (text.isNullOrBlank()) return Shown("PRESENCE", null, "")
        val t = text.trim()
        val n = Regex("""^(\d+(?:\.\d+)?)?\s*([½¼¾⅓⅔⅛])?""").find(t)
        val whole = n?.groupValues?.get(1)?.toDoubleOrNull()
        val frac = FRACTIONS[n?.groupValues?.get(2)]
        val amount = if (whole == null && frac == null) null else (whole ?: 0.0) + (frac ?: 0.0)
        val unit = t.substring(n?.range?.last?.plus(1) ?: 0).trim().lowercase().substringBefore(' ').trimEnd('.')
        val way =
            when {
                unit == "kg" -> return Shown("WEIGHT", amount?.times(1000), t)
                unit == "g" -> "WEIGHT"
                unit in setOf("ml", "l", "tsp", "tbsp", "cup", "cups", "pint", "pints", "floz") -> "VOLUME"
                unit.startsWith("jar") -> "JAR"
                unit.startsWith("tin") || unit.startsWith("can") -> "TIN"
                PACKS.any { unit.startsWith(it) } -> "PACK"
                unit.startsWith("bottle") -> "BOTTLE"
                unit.startsWith("bunch") -> "BUNCH"
                else -> "EACH" // a plain number, or a counted unit (cloves, sticks, slices, pieces)
            }
        return Shown(way, amount, t)
    }

    private fun score(): List<Scored> {
        val root = Json.parseToJsonElement(File("../../testdata/eval/basket-2026.json").readText()).jsonArray
        val out = ArrayList<Scored>()
        for (r in root.map { it.jsonObject }) {
            val id = r.getValue("id").jsonPrimitive.content
            val us = r["group"]?.jsonPrimitive?.content == "us"
            val servings = r["servings"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int
            val lines = r.getValue("lines").jsonArray.map { it.jsonPrimitive.content }
            val parsed = parser.parseLines(lines).mapIndexed { i, p -> (i + 1L) to p }
            val locale = if (us) UnitLocale.US else UnitLocale.METRIC
            val inc = merger.incoming(1, servings, servings, locale, parsed)
            val list = (merger.apply(ShoppingList(), merger.preview(ShoppingList(), inc, emptyMap()), 1000) as ApplyResult.Applied).list
            for (b in r.getValue("basket").jsonArray.map { it.jsonObject }) {
                val e =
                    Entry(
                        id,
                        b.getValue("item").jsonPrimitive.content,
                        (b["accept"] as JsonArray).map { a ->
                            val o = a.jsonObject
                            Accept(o.getValue("buy").jsonPrimitive.content, num(o, "min"), num(o, "max"))
                        },
                        b["optional"]?.jsonPrimitive?.boolean ?: false,
                        b.getValue("lines").jsonArray.map { it.jsonPrimitive.int },
                    )
                // The list row the entry's own lines went to (the label's item first, when a line made two rows):
                // found by where it came from, so a label worded differently from the catalogue is not "missing".
                val key = merger.target(e.item).mergeKey
                val fromLines =
                    list.contributions.filter {
                        it.recipeLineId?.let {
                                id ->
                            (id - 1).toInt() in e.lines
                        } == true
                    }.map { it.itemId }
                val item =
                    fromLines.map { list.items.getValue(it) }.let { rows -> rows.firstOrNull { it.mergeKey == key } ?: rows.firstOrNull() }
                if (item == null) {
                    out += Scored(e, null, found = false, named = false, wayOk = false, amountOk = false)
                    continue
                }
                val named = item.mergeKey == key || item.displayName.equals(e.item, ignoreCase = true)
                val row = Display.row(list, item, catalog.byId[item.catalogId])
                val amountText =
                    row.main.removeSuffix(
                        item.displayName,
                    ).removeSuffix(catalog.byId[item.catalogId]?.plural ?: "\u0000").trim()
                val s = shown(amountText)
                val presence = e.accept.any { it.buy == "PRESENCE" }
                val ways = e.accept.filter { it.buy == s.buy }
                val wayOk = presence || ways.isNotEmpty()
                val amountOk =
                    presence && ways.isEmpty() ||
                        ways.any { a ->
                            s.amount == null && a.min == null ||
                                s.amount != null &&
                                (a.min == null || s.amount >= a.min - 1e-9) &&
                                (a.max == null || s.amount <= a.max + 1e-9)
                        }
                out += Scored(e, s, found = true, named = named, wayOk = wayOk, amountOk = amountOk)
            }
        }
        return out
    }

    @Test
    fun report() {
        val s = score()
        val req = s.filter { !it.entry.optional }
        val sb = StringBuilder("# Basket evaluation (testdata/eval/basket-2026.json)\n\n")
        sb.append("${s.size} basket entries (${req.size} required), 15 recipes, the rule parser's reading.\n\n")
        sb.append("| | entries | on the list | named as the label | bought the right way | right amount |\n|---|---|---|---|---|---|\n")

        fun row(
            name: String,
            v: List<Scored>,
        ) = sb.append(
            "| $name | ${v.size} | ${v.count { it.found }} | ${v.count { it.found && it.named }} | ${v.count { it.found && it.wayOk }} | " +
                "${v.count { it.right }} |\n",
        )
        row("required", req)
        row("all", s)
        sb.append("\n## Wrong, or named otherwise\n\n| recipe | item | list shows | wanted |\n|---|---|---|---|\n")
        for (x in s.filter { !it.right || !it.named }) {
            val want =
                x.entry.accept.joinToString(
                    " or ",
                ) { "${it.buy} ${it.min ?: ""}${if (it.max != it.min) "–${it.max ?: ""}" else ""}" }
            val got = if (!x.found) "(missing)" else "${x.shown!!.buy} '${x.shown.text}'${if (!x.named) " (named otherwise)" else ""}"
            sb.append("| ${x.entry.recipe} | ${x.entry.item}${if (x.entry.optional) " (optional)" else ""} | $got | $want |\n")
        }
        File("build/reports").mkdirs()
        File("build/reports/basket-eval.md").writeText(sb.toString())
        println(sb)
    }

    /** Exactly, as the extraction floors: a fall is a regression, a rise means raising these. */
    @Test
    fun totalsHold() {
        val s = score().filter { !it.entry.optional }
        val got = listOf(s.count { it.found }, s.count { it.found && it.named }, s.count { it.found && it.wayOk }, s.count { it.right })
        assertEquals(FLOORS, got, "required entries: on the list, named as the label, bought the right way, right")
    }

    private companion object {
        /** The label set's PACK covers every shop pack the catalogue names. */
        val PACKS = listOf("pack", "bag", "box", "pot", "tub", "carton", "punnet", "tube")

        val FRACTIONS = mapOf("½" to 0.5, "¼" to 0.25, "¾" to 0.75, "⅓" to 1.0 / 3, "⅔" to 2.0 / 3, "⅛" to 0.125)

        /**
         * W.0b baseline [165, 143, 136, 121]; W.2, rows in the way things are bought, [165, 143, 154, 152]; W.3, sourced
         * weights and packs: USDA's medium carrot and onion are smaller than a UK one, so a weight counts more of them.
         */
        val FLOORS = listOf(165, 143, 154, 149)
    }
}
