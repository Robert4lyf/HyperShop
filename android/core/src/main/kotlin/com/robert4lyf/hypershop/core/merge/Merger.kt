package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.catalog.Catalog
import com.robert4lyf.hypershop.core.catalog.CatalogLoader
import com.robert4lyf.hypershop.core.model.ParsedIngredient
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.QtyUnit
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.Normaliser

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
    /** "I have some": subtracted earliest line first. */
    val haveSome: QtyBase? = null,
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
    /** The rev of every item shown (null: not on the list yet), re-checked on confirm. */
    val shownRevs: Map<String, Long?>,
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
            lines.map { (id, p) -> IncomingLine(id, p, target(p.name, p.aisleSuggestion, overrides)) },
        )

    fun target(
        name: String,
        claudeAisle: com.robert4lyf.hypershop.core.model.AisleCode? = null,
        overrides: Map<String, com.robert4lyf.hypershop.core.model.AisleCode> = emptyMap(),
    ): NewItem {
        val entry = normaliser.entry(name)
        val norm = normaliser.normName(name)
        return NewItem(
            mergeKey = normaliser.mergeKey(name),
            catalogId = entry?.id,
            normName = norm,
            displayName = entry?.name ?: norm,
            aisle = categoriser.aisle(name, overrides, claudeAisle).first,
        )
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
            var have = d.haveSome ?: QtyBase.PRESENCE
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
                var q = l.parsed.shopBase(incoming.locale)
                if (!noScale) q = q.times(scale)
                var lineStatus = LineStatus.ADDED
                if (!have.isPresence && !q.isPresence) {
                    // Subtract what you have from this line, earliest line first.
                    val after = q.minusClamped(have)
                    have = have.minusClamped(q)
                    lineStatus = LineStatus.PARTIAL
                    q = after
                    if (q.isPresence) {
                        status[l.recipeLineId] = LineStatus.PARTIAL
                        continue
                    }
                }
                status[l.recipeLineId] = lineStatus
                contributions += PreviewContribution(l.recipeLineId, key, q, noScale || q.isPresence, l.parsed.shopUnit)
                adding += q
            }
            val onList = (existing?.let { list.total(it.id) } ?: QtyBase.PRESENCE) + adding
            rows += PreviewRow(lines.first().target, existing, lines.map { it.recipeLineId }, adding, onList, d)
        }
        val shown = rows.associate { it.target.mergeKey to it.existing?.rev }
        return Preview(incoming, rows, contributions, status, shown)
    }

    /** One transaction: re-check every rev shown, then insert. */
    fun apply(
        list: ShoppingList,
        preview: Preview,
        now: Long,
    ): ApplyResult {
        for ((key, rev) in preview.shownRevs) {
            if (list.itemByKey(key)?.rev != rev) return ApplyResult.Stale
        }
        val use = RecipeUse(list.nextId, preview.incoming.recipeId, UseKind.RECIPE, preview.incoming.servings ?: 1, now)
        var l = list.copy(uses = list.uses + (use.id to use), nextId = list.nextId + 1)
        val createdRev = list.listRev + 1
        val touched = HashSet<Long>()
        val targets = preview.rows.associate { it.target.mergeKey to it.target }
        for (pc in preview.contributions) {
            val (l2, itemId) = l.ensureItem(targets.getValue(pc.mergeKey))
            val c = Contribution(l2.nextId, use.id, pc.recipeLineId, itemId, pc.qty, pc.noScale, createdRev, unitHint = pc.unitHint)
            l = l2.copy(contributions = l2.contributions + c, nextId = l2.nextId + 1)
            touched += itemId
        }
        return ApplyResult.Applied(l.bumped(touched), use.id, touched.size)
    }
}
