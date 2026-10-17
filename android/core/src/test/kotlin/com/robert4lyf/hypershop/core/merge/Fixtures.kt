package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.RuleParser
import io.kotest.property.arbitrary.list

internal val parser = RuleParser()
internal val merger = Merger()

internal val LINES =
    listOf(
        "2 onions, chopped", "1 red onion", "200 ml milk", "500ml whole milk", "3 garlic cloves", "1 tbsp olive oil",
        "Salt, to taste", "400g tin chopped tomatoes", "2 x 400g tins chickpeas", "100g butter", "2 eggs", "1 cup flour",
        "½ tsp smoked paprika", "1 bunch coriander", "250g spinach", "2 carrots", "1 lemon", "2 tbsp soy sauce",
    )

internal fun recipe(
    lines: List<String>,
    servings: Int = 4,
    original: Int = 4,
): Incoming = merger.incoming(1, original, servings, UnitLocale.METRIC, lines.mapIndexed { i, l -> (i + 1L) to parser.parse(l) })

internal fun add(
    list: ShoppingList,
    lines: List<String>,
    servings: Int = 4,
    decisions: (Incoming) -> Map<String, RowDecision> = { emptyMap() },
): Pair<ShoppingList, Long> {
    val inc = recipe(lines, servings)
    val r = merger.apply(list, merger.preview(list, inc, decisions(inc)), now = 1000) as ApplyResult.Applied
    return r.list to r.useId
}

/** Everything but the revs, for "equal apart from revs". */
internal fun ShoppingList.shape() =
    Triple(
        liveItems.map { it.copy(rev = 0) }.sortedBy { it.id },
        contributions.sortedBy { it.id },
        ticks,
    )

/** Ids aside: items by key with their totals, and contributions by (line, key, amount). */
internal fun ShoppingList.semantic() =
    Pair(
        liveItems.associate { it.mergeKey to Triple(it.displayName, it.aisle, total(it.id)) },
        contributions.map { Triple(it.recipeLineId, items.getValue(it.itemId).mergeKey, it.qty to it.noScale) }.sortedBy { it.toString() },
    )
