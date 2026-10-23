package com.robert4lyf.hypershop.core.parse

import com.robert4lyf.hypershop.core.catalog.CatalogLoader
import com.robert4lyf.hypershop.core.merge.Display
import com.robert4lyf.hypershop.core.merge.Merger
import com.robert4lyf.hypershop.core.merge.UnitsSetting
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.UnitLocale
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Lines where a wrong reading means buying the wrong thing or the wrong amount,
 * each pinned exactly through the whole pipeline: the line, the parse, the
 * catalogue item and aisle it lands on, and the amount the list shows in metric
 * and imperial. The expectations are what a shopper needs, written from the
 * product and the arithmetic, not copied from the parser's output. An aggregate
 * accuracy can stay high while one of these goes wrong; this cannot.
 */
class CriticalLinesTest {
    private val catalog = CatalogLoader.default
    private val parser = RuleParser(catalog)
    private val merger = Merger(catalog, Normaliser(catalog))

    private data class Case(
        val line: String,
        val key: String,
        val metric: String?,
        val imperial: String? = null,
        val locale: UnitLocale = UnitLocale.METRIC,
        val aisle: AisleCode? = null,
    )

    private val cases =
        listOf(
            // Look-alike products: one word apart on the page, different things on the shelf.
            Case("1 tsp bicarbonate of soda", "cat:bicarb", "1tsp"),
            Case("1 tsp baking soda", "cat:bicarb", "1tsp", locale = UnitLocale.US),
            Case("1 tsp baking powder", "cat:baking_powder", "1tsp"),
            Case("200g self-raising flour", "cat:self_raising_flour", "200g"),
            Case("200g plain flour", "cat:flour", "200g"),
            Case("100g icing sugar", "cat:icing_sugar", "100g"),
            Case("100g caster sugar", "cat:caster_sugar", "100g"),
            Case("1 tsp ground ginger", "cat:ginger_ground", "1tsp"),
            Case("2cm piece ginger", "cat:ginger", "1 piece", aisle = AisleCode.PRODUCE),
            Case("1 tsp garlic powder", "cat:garlic_powder", "1tsp"),
            Case("2 garlic cloves", "cat:garlic", "2 cloves", aisle = AisleCode.PRODUCE),
            Case("400ml can coconut milk", "cat:coconut_milk", "1 tin", aisle = AisleCode.TINS_JARS),
            Case("300ml milk", "cat:milk", "300ml", aisle = AisleCode.DAIRY_EGGS),
            Case("2 tbsp tomato purée", "cat:tomato_puree", "2tbsp"),
            Case("500g passata", "cat:passata", "500g"),
            Case("1 tsp chilli flakes", "cat:chilli_flakes", "1tsp"),
            Case("1 red chilli", "cat:chilli", "1", aisle = AisleCode.PRODUCE),
            Case("250ml double cream", "cat:double_cream", "250ml"),
            Case("150ml single cream", "cat:single_cream", "150ml"),
            Case("100g dark chocolate", "cat:dark_chocolate", "100g"),
            Case("2 tbsp cocoa powder", "cat:cocoa", "2tbsp"),
            // Tins and packs: the container is what is bought, never its weight as loose produce.
            Case("400g tin chopped tomatoes", "cat:chopped_tomatoes", "1 tin", aisle = AisleCode.TINS_JARS),
            Case("2 x 400g tins chopped tomatoes", "cat:chopped_tomatoes", "2 tins", aisle = AisleCode.TINS_JARS),
            Case("2 tins chickpeas", "cat:chickpeas", "2 tins", aisle = AisleCode.TINS_JARS),
            Case("4 tomatoes", "cat:tomato", "4", aisle = AisleCode.PRODUCE),
            // Meat: mince is a different product from a joint or a steak.
            Case("500g beef mince", "cat:beef_mince", "500g", "1¼lb"),
            Case("1 lb ground beef", "cat:beef_mince", "460g", "1lb", locale = UnitLocale.US),
            Case("500g lamb mince", "cat:lamb_mince", "500g"),
            // Spoons: a teaspoon is a third of a tablespoon, and neither is ever converted.
            Case("1 tsp salt", "cat:salt", "1tsp"),
            Case("1 tbsp salt", "cat:salt", "1tbsp"),
            Case("1 tbsp olive oil", "cat:olive_oil", "1tbsp"),
            // Counts, ranges and juice: the shopper buys the upper end, and the fruit.
            Case("2-3 carrots", "cat:carrot", "3", aisle = AisleCode.PRODUCE),
            Case("Juice of 1 lemon", "cat:lemon", "1", aisle = AisleCode.PRODUCE),
            Case("6 eggs", "cat:egg", "6"),
            Case("1 dozen eggs", "cat:egg", "12"),
            Case("1kg potatoes", "cat:potato", "1kg", "2¼lb"),
            // US cups of solids are bought by weight where the catalogue knows the density.
            Case("1/2 cup butter", "cat:butter", "120g", "4¼oz", locale = UnitLocale.US),
            Case("1 cup grated parmesan", "cat:parmesan", "100g", locale = UnitLocale.US),
            Case("2 cups milk", "cat:milk", "480ml", "2 cups", locale = UnitLocale.US),
            // A recipe's own metric figure is what is shopped by.
            Case("1/2 cup (113g) butter", "cat:butter", "120g", locale = UnitLocale.US),
        )

    private fun show(
        c: Case,
        units: UnitsSetting,
    ): Pair<String, String?> {
        val p = parser.parse(c.line)
        val t = merger.target(p.name)
        val entry = catalog.byId[t.catalogId ?: ""]
        val shown =
            Display.amount(
                p.shopBase(c.locale),
                units,
                produce = t.aisle == AisleCode.PRODUCE,
                hint = p.shopUnit,
                gramsPerCup = entry?.gramsPerCup,
                policy = entry?.displayPolicy,
            )
        return "${t.mergeKey} ${t.aisle}" to shown
    }

    @Test
    fun everyCriticalLineReadsExactly() {
        val wrong =
            cases.mapNotNull { c ->
                val (target, metric) = show(c, UnitsSetting.METRIC)
                val imperial = c.imperial?.let { show(c, UnitsSetting.IMPERIAL).second }
                val keyOk = target.startsWith("${c.key} ") && (c.aisle == null || target.endsWith(" ${c.aisle}"))
                val ok = keyOk && metric == c.metric && (c.imperial == null || imperial == c.imperial)
                val want = "${c.key} ${c.aisle ?: ""} / ${c.metric} / ${c.imperial}"
                if (ok) null else "\"${c.line}\": got $target / $metric / $imperial, want $want"
            }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun noCriticalLineIsWaivedThroughAsUncertain() {
        // These are plain lines: none should need a look or a paid second opinion. ("Juice of" is
        // flagged on purpose, R7: the fruit is listed, so the shopper confirms it.)
        val flagged =
            cases
                .filter { !it.line.startsWith("Juice") && Uncertainty.reason(parser.parse(it.line)) != null }
                .map { it.line }
        assertTrue(flagged.isEmpty(), "flagged: $flagged")
    }
}
