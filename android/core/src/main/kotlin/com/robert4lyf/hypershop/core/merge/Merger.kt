package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.catalog.Catalog
import com.robert4lyf.hypershop.core.catalog.CatalogLoader
import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.ParsedIngredient
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.QtyUnit
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.Normaliser
import kotlin.math.ceil

/** PARTIAL is no longer written (it was "I have some"); kept so stored decisions still read. */
enum class LineStatus { ADDED, IN_STOCK, PARTIAL, EXCLUDED }

data class IncomingLine(
    val recipeLineId: Long,
    val parsed: ParsedIngredient,
    val target: NewItem,
)

data class Incoming(
    val recipeId: Long?,
    val servingsOriginal: Int?,
    val servings: Int?,
    val locale: UnitLocale,
    val lines: List<IncomingLine>,
) {
    /** servings / servingsOriginal, or 1 when either is unknown. */
    val scale: Rational
        get() =
            if (servings != null && servingsOriginal != null && servings > 0 && servingsOriginal > 0) {
                Rational.of(servings.toLong(), servingsOriginal.toLong())
            } else {
                Rational.ONE
            }
}

/** The review screen's choice for one row (one mergeKey). */
data class RowDecision(
    val inStock: Boolean = false,
    val excluded: Boolean = false,
)

data class PreviewContribution(
    val recipeLineId: Long,
    val mergeKey: String,
    val qty: QtyBase,
    val noScale: Boolean,
    val unitHint: QtyUnit?,
)

/** One review row: what this recipe adds to one item, and what the list will then hold. */
data class PreviewRow(
    val target: NewItem,
    val existing: ListItem?,
    val lineIds: List<Long>,
    val adding: QtyBase,
    val onListAfter: QtyBase,
    val decision: RowDecision,
)

data class Preview(
    val incoming: Incoming,
    val rows: List<PreviewRow>,
    val contributions: List<PreviewContribution>,
    val lineStatus: Map<Long, LineStatus>,
    /**
     * The id and rev of every item shown (null: not on the list yet), re-checked
     * on confirm. The id matters: a deleted and re-added item can carry the same rev.
     */
    val shownRevs: Map<String, Pair<Long, Long>?>,
)

sealed interface ApplyResult {
    data class Applied(
        val list: ShoppingList,
        val useId: Long,
        val added: Int,
    ) : ApplyResult

    /** Something shown changed: "List changed — review updated". */
    data object Stale : ApplyResult
}

/**
 * Review-before-adding maths (PLAN §3.1, §3.3). [preview] is pure and is what
 * the review screen shows; [apply] re-checks the revs shown and writes.
 */
class Merger(
    private val catalog: Catalog = CatalogLoader.default,
    private val normaliser: Normaliser = Normaliser(catalog),
    private val categoriser: Categoriser = Categoriser(catalog, normaliser),
) {
    /** Turns parsed lines into incoming lines with their item identity and aisle. */
    fun incoming(
        recipeId: Long?,
        servingsOriginal: Int?,
        servings: Int?,
        locale: UnitLocale,
        lines: List<Pair<Long, ParsedIngredient>>,
        overrides: Map<String, com.robert4lyf.hypershop.core.model.AisleCode> = emptyMap(),
    ): Incoming =
        Incoming(
            recipeId,
            servingsOriginal,
            servings,
            locale,
            lines.map { (id, p) ->
                juiceAsFruit(oneCountUnit(p), locale).let { f -> IncomingLine(id, f, target(f.name, f.aisleSuggestion, overrides, f.raw)) }
            },
        )

    /**
     * V.6: a stalk of celery and a stick of celery are the same thing, but two count units, so "2 stalks" and
     * "2 sticks" from two recipes stood side by side on one row. Celery and rhubarb count in sticks, the
     * word a British shopper uses. A stick of butter or cinnamon is something else and is left alone.
     */
    fun oneCountUnit(p: ParsedIngredient): ParsedIngredient {
        if (p.unit != QtyUnit.STALK || catalogEntry(p.name)?.id !in STICKS) return p
        return p.copy(unit = QtyUnit.STICK, quantityText = null)
    }

    /**
     * V.2: lemon or lime juice in a recipe is bought as the fruit, unless the recipe says it comes bottled. A lemon
     * gives about 3 tbsp (45 ml) of juice and a lime about 2 tbsp (30 ml); each line rounds up to whole fruit, so
     * "2 tbsp lime juice" is 1 lime. The recipe's own line is unchanged: only what goes on the list is the fruit.
     * A lemon written by the spoon ("2 tbsp lemon") is the same thing.
     */
    fun juiceAsFruit(
        p: ParsedIngredient,
        locale: UnitLocale,
    ): ParsedIngredient {
        val (fruit, yieldMl) = JUICE[catalogEntry(p.name)?.id] ?: return p
        val said = (p.raw + " " + p.note.orEmpty()).lowercase()
        if (BOTTLED.any { it in said }) return p
        val q = p.shopBase(locale)
        val liquid = q[BaseKey.VOLUME] + q[BaseKey.MASS]
        val whole = q[BaseKey.ITEMS]
        val n =
            when {
                !liquid.isZero -> Rational.of(ceil(liquid.toDouble() / yieldMl - SLACK).toLong().coerceAtLeast(1))
                !whole.isZero -> return p // already counted as fruit: "juice of 2 lemons"
                q.isPresence && !p.name.endsWith("juice") -> return p // "lemon wedges, to serve"
                else -> Rational.ONE
            }
        val part = if (p.name.endsWith("juice")) "juice" else p.name.substringAfter(' ', "").ifEmpty { "it" }
        val amount = p.quantity?.let { q -> listOfNotNull(p.quantityMax?.let { "$q-$it" } ?: "$q", p.unit?.serial).joinToString(" ") }
        val juice = amount?.let { "for $it $part" } ?: "for the $part"
        return p.copy(
            name = fruit,
            quantity = n,
            quantityMax = null,
            quantityText = null,
            unit = null,
            altQuantity = null,
            altUnit = null,
            note = listOfNotNull(juice, p.note).joinToString(", "),
        )
    }

    fun target(
        name: String,
        claudeAisle: com.robert4lyf.hypershop.core.model.AisleCode? = null,
        overrides: Map<String, com.robert4lyf.hypershop.core.model.AisleCode> = emptyMap(),
        raw: String = name,
    ): NewItem {
        val found = normaliser.entry(name)
        // "Always use instead" (ingredient library): the line goes straight to the replacement.
        found?.replaceWith?.let { catalog.byId[it] }?.let { r ->
            return NewItem(r.mergeKey, r.id, normaliser.normName(r.name), r.name, categoriser.aisle(r.name, overrides, claudeAisle).first)
        }
        val entry = found
        val norm = normaliser.normName(name)
        return NewItem(
            mergeKey = normaliser.mergeKey(name, raw),
            catalogId = entry?.id,
            normName = norm,
            displayName = entry?.name ?: norm,
            aisle = categoriser.aisle(name, overrides, claudeAisle).first,
        )
    }

    /**
     * The catalogue entry [target] would file [name] under (after "Always use instead"),
     * without its key and aisle: the recipe search needs only this, for every line it indexes.
     */
    fun catalogEntry(name: String): com.robert4lyf.hypershop.core.catalog.CatalogEntry? {
        val found = normaliser.entry(name)
        return found?.replaceWith?.let { catalog.byId[it] } ?: found
    }

    /** Pre-ticked "in stock" rows: staples, and rows whose lines are all optional or garnish. */
    fun defaultDecisions(
        incoming: Incoming,
        staples: Set<String>,
    ): Map<String, RowDecision> =
        incoming.lines.groupBy { it.target.mergeKey }.mapValues { (key, ls) ->
            RowDecision(inStock = key in staples || ls.all { it.parsed.optional || it.parsed.garnish })
        }

    fun preview(
        list: ShoppingList,
        incoming: Incoming,
        decisions: Map<String, RowDecision>,
    ): Preview {
        val scale = incoming.scale
        val contributions = ArrayList<PreviewContribution>()
        val status = LinkedHashMap<Long, LineStatus>()
        val rows = ArrayList<PreviewRow>()
        for ((key, lines) in incoming.lines.groupBy { it.target.mergeKey }) {
            val d = decisions[key] ?: RowDecision()
            val existing = list.itemByKey(key)
            var adding = QtyBase.PRESENCE
            for (l in lines) {
                if (d.excluded) {
                    status[l.recipeLineId] = LineStatus.EXCLUDED
                    continue
                }
                if (d.inStock) {
                    status[l.recipeLineId] = LineStatus.IN_STOCK
                    continue
                }
                val noScale = l.parsed.noScale
                val q = l.parsed.shopBase(incoming.locale).let { if (noScale) it else it.times(scale) }
                status[l.recipeLineId] = LineStatus.ADDED
                contributions += PreviewContribution(l.recipeLineId, key, q, noScale || q.isPresence, l.parsed.shopUnit)
                adding += q
            }
            val onList = (existing?.let { list.total(it.id) } ?: QtyBase.PRESENCE) + adding
            rows += PreviewRow(lines.first().target, existing, lines.map { it.recipeLineId }, adding, onList, d)
        }
        val shown = rows.associate { it.target.mergeKey to it.existing?.let { e -> e.id to e.rev } }
        return Preview(incoming, rows, contributions, status, shown)
    }

    /**
     * One transaction: re-check every rev shown, then insert. [kind] MANUAL puts the
     * lines in a hand-added use, with no recipe behind it (an edit of "Added by hand").
     */
    fun apply(
        list: ShoppingList,
        preview: Preview,
        now: Long,
        kind: UseKind = UseKind.RECIPE,
    ): ApplyResult {
        for ((key, shown) in preview.shownRevs) {
            if (list.itemByKey(key)?.let { it.id to it.rev } != shown) return ApplyResult.Stale
        }
        val manual = kind == UseKind.MANUAL
        val use = RecipeUse(list.nextId, preview.incoming.recipeId.takeIf { !manual }, kind, preview.incoming.servings ?: 1, now)
        var l = list.copy(uses = list.uses + (use.id to use), nextId = list.nextId + 1)
        val createdRev = list.listRev + 1
        val touched = HashSet<Long>()
        val targets = preview.rows.associate { it.target.mergeKey to it.target }
        for (pc in preview.contributions) {
            val (l2, itemId) = l.ensureItem(targets.getValue(pc.mergeKey))
            val line = pc.recipeLineId.takeIf { !manual }
            val c = Contribution(l2.nextId, use.id, line, itemId, pc.qty, pc.noScale, createdRev, unitHint = pc.unitHint)
            l = l2.copy(contributions = l2.contributions + c, nextId = l2.nextId + 1)
            touched += itemId
        }
        return ApplyResult.Applied(l.bumped(touched), use.id, touched.size)
    }

    private companion object {
        /** Catalogue id → the fruit bought for it, and the juice one gives (ml). */
        val JUICE =
            mapOf(
                "lemon_juice" to ("lemon" to 45.0),
                "lime_juice" to ("lime" to 30.0),
                "lemon" to ("lemon" to 45.0),
                "lime" to ("lime" to 30.0),
            )

        /** Fruit vary: a line a little over a whole fruit's juice (¼ cup of lime) is still that many fruit. */
        const val SLACK = 0.15

        /** Produce counted in sticks, whichever of "stalk" or "stick" the recipe says. */
        val STICKS = setOf("celery", "rhubarb")

        /** Words saying the juice is bought as juice. */
        val BOTTLED = listOf("bottle", "concentrate", "squeezy", "jif", "carton", "shop-bought", "store-bought")
    }
}
