package com.robert4lyf.hypershop.data

import com.robert4lyf.hypershop.core.merge.LineStatus
import com.robert4lyf.hypershop.core.merge.TickSource
import com.robert4lyf.hypershop.core.merge.UseKind
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.ParsedIngredient
import com.robert4lyf.hypershop.core.model.QtyBase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The database's user data, as one value (docs/design/simplify.md phase 4).
 * Written by [ListRepository.exportBackup], read back by
 * [ListRepository.restoreBackup] after [BackupValidator] has passed it.
 *
 * Left out on purpose: import jobs (transient), watch installs and op results
 * (they belong to the list epoch, which a restore replaces), and anything
 * secret (the key lives outside the database).
 */
@Serializable
data class BackupData(
    val listRev: Long,
    val nextId: Long,
    val catalogVersion: String,
    val manualTitle: String? = null,
    val items: List<ItemEntity> = emptyList(),
    val aliases: List<ItemAliasEntity> = emptyList(),
    val recipes: List<RecipeEntity> = emptyList(),
    val lines: List<RecipeLineEntity> = emptyList(),
    val uses: List<RecipeUseEntity> = emptyList(),
    val ticks: List<TickEntity> = emptyList(),
    val contributions: List<ContributionEntity> = emptyList(),
    val decisions: List<LineDecisionEntity> = emptyList(),
    val aisles: List<AisleEntity> = emptyList(),
    val stores: List<StoreProfileEntity> = emptyList(),
    val aisleOrder: List<AisleOrderEntity> = emptyList(),
    val overrides: List<CategoryOverrideEntity> = emptyList(),
    val staples: List<StapleEntity> = emptyList(),
    val usage: List<ClaudeUsageEntity> = emptyList(),
    val deleted: List<DeletedItemEntity> = emptyList(),
)

/**
 * Everything a restore checks before a single row is written: the file is a
 * backup, its ids are unique, every reference points at a row that is there,
 * every stored enum and JSON decodes, keys are unique among live items, and
 * nothing is absurdly large. A problem list; empty means safe to restore.
 */
object BackupValidator {
    const val MAX_ROWS = 50_000
    private const val MAX_TEXT = 2_000
    private const val MAX_JSON = 20_000
    private val json = Json { ignoreUnknownKeys = true }

    fun validate(b: BackupData): List<String> {
        val problems = ArrayList<String>()

        fun check(
            ok: Boolean,
            what: String,
        ) {
            if (!ok && problems.size < 20) problems += what
        }
        val tables =
            listOf(
                "items" to b.items.size,
                "recipes" to b.recipes.size,
                "lines" to b.lines.size,
                "uses" to b.uses.size,
                "ticks" to b.ticks.size,
                "contributions" to b.contributions.size,
                "decisions" to b.decisions.size,
            )
        for ((t, n) in tables) check(n <= MAX_ROWS, "$t: $n rows is more than a backup can hold")
        check(b.nextId >= 1, "nextId must be at least 1")
        check(b.listRev >= 1, "listRev must be at least 1")

        val itemIds = b.items.map { it.id }
        val recipeIds = b.recipes.map { it.id }
        val lineIds = b.lines.map { it.id }
        val useIds = b.uses.map { it.id }
        val tickIds = b.ticks.map { it.id }
        val contributionIds = b.contributions.map { it.id }
        for ((t, ids) in listOf(
            "items" to itemIds,
            "recipes" to recipeIds,
            "lines" to lineIds,
            "uses" to useIds,
            "ticks" to tickIds,
            "contributions" to contributionIds,
        )) {
            check(ids.size == ids.toSet().size, "$t: duplicate ids")
            check(ids.all { it >= 1 }, "$t: an id below 1")
        }
        val listIds = itemIds + useIds + tickIds + contributionIds
        check(listIds.all { it < b.nextId }, "nextId is not above every list id")
        val items = itemIds.toSet()
        val recipes = recipeIds.toSet()
        val lines = lineIds.toSet()
        val uses = useIds.toSet()
        val ticks = tickIds.toSet()
        val aisleNames = AisleCode.entries.map { it.name }.toSet()

        for (i in b.items) {
            check(i.aisleCode in aisleNames, "item ${i.id}: unknown aisle ${i.aisleCode}")
            check(
                i.mergeKey.length in 1..MAX_TEXT && i.displayName.length <= MAX_TEXT && i.normName.length <= MAX_TEXT,
                "item ${i.id}: text too long",
            )
            check(i.rev >= 1, "item ${i.id}: rev below 1")
        }
        val liveKeys = b.items.filter { it.deletedAt == null }.map { it.mergeKey }
        check(liveKeys.size == liveKeys.toSet().size, "two live items share a key")
        for (a in b.aliases) check(a.newId in items, "alias ${a.oldId}: points at a missing item ${a.newId}")
        check(b.aliases.map { it.oldId }.toSet().size == b.aliases.size, "duplicate aliases")
        check(b.deleted.map { it.id }.toSet().size == b.deleted.size, "duplicate deleted items")
        check(b.stores.map { it.id }.toSet().size == b.stores.size, "duplicate stores")
        val orderKeys = b.aisleOrder.map { it.storeId to it.aisleCode }
        check(orderKeys.size == orderKeys.toSet().size, "duplicate aisle order rows")
        val usageKeys = b.usage.map { it.date to it.model }
        check(usageKeys.size == usageKeys.toSet().size, "duplicate usage rows")
        for (r in b.recipes) {
            check((r.title?.length ?: 0) <= MAX_TEXT && (r.url?.length ?: 0) <= MAX_TEXT, "recipe ${r.id}: text too long")
            check(
                runCatching { com.robert4lyf.hypershop.core.model.UnitLocale.valueOf(r.unitLocale) }.isSuccess,
                "recipe ${r.id}: unknown locale",
            )
        }
        for (l in b.lines) {
            check(l.recipeId in recipes, "line ${l.id}: recipe ${l.recipeId} is missing")
            check(l.raw.length <= MAX_TEXT && l.parsedJson.length <= MAX_JSON, "line ${l.id}: text too long")
            check(
                runCatching { json.decodeFromString(ParsedIngredient.serializer(), l.parsedJson) }.isSuccess,
                "line ${l.id}: unreadable parse",
            )
        }
        for (u in b.uses) {
            val kind = runCatching { UseKind.valueOf(u.kind) }.getOrNull()
            check(kind != null, "use ${u.id}: unknown kind ${u.kind}")
            check(kind != UseKind.RECIPE || u.recipeId != null, "use ${u.id}: a recipe use without a recipe")
            check(u.recipeId == null || u.recipeId in recipes, "use ${u.id}: recipe ${u.recipeId} is missing")
            check(u.servings >= 1, "use ${u.id}: servings below 1")
        }
        for (t in b.ticks) {
            check(t.itemId in items, "tick ${t.id}: item ${t.itemId} is missing")
            check(runCatching { TickSource.valueOf(t.source) }.isSuccess, "tick ${t.id}: unknown source")
        }
        for (c in b.contributions) {
            check(c.useId in uses, "contribution ${c.id}: use ${c.useId} is missing")
            check(c.itemId in items, "contribution ${c.id}: item ${c.itemId} is missing")
            check(c.recipeLineId == null || c.recipeLineId in lines, "contribution ${c.id}: line ${c.recipeLineId} is missing")
            check(c.coveredBy == null || c.coveredBy in ticks, "contribution ${c.id}: tick ${c.coveredBy} is missing")
            check(
                c.qtyBase.length <= MAX_JSON &&
                    runCatching {
                        json.decodeFromString(QtyBase.serializer(), c.qtyBase)
                    }.isSuccess,
                "contribution ${c.id}: unreadable amount",
            )
        }
        for (d in b.decisions) {
            check(d.useId in uses && d.recipeLineId in lines, "decision ${d.useId}/${d.recipeLineId}: missing use or line")
            check(runCatching { LineStatus.valueOf(d.status) }.isSuccess, "decision ${d.useId}/${d.recipeLineId}: unknown status")
        }
        val decisionKeys = b.decisions.map { it.useId to it.recipeLineId }
        check(decisionKeys.size == decisionKeys.toSet().size, "duplicate decisions")
        for (a in b.aisles) check(a.code in aisleNames && a.name.length <= MAX_TEXT, "aisle ${a.code}: unknown or too long")
        check(b.aisles.map { it.code }.toSet().size == b.aisles.size, "duplicate aisles")
        val storeIds = b.stores.map { it.id }.toSet()
        for (o in b.aisleOrder) check(
            o.aisleCode in aisleNames && o.storeId in storeIds,
            "aisle order ${o.aisleCode}: unknown aisle or store",
        )
        for (o in b.overrides) check(
            o.aisleCode in aisleNames && o.key.length in 1..MAX_TEXT,
            "override ${o.key.take(40)}: unknown aisle or bad key",
        )
        check(b.overrides.map { it.key }.toSet().size == b.overrides.size, "duplicate overrides")
        for (st in b.staples) check(st.key.length in 1..MAX_TEXT, "staple: bad key")
        check(b.staples.map { it.key }.toSet().size == b.staples.size, "duplicate staples")
        for (d in b.deleted) check(d.id in items, "deleted ${d.id}: item is missing")
        for (u in b.usage) {
            check(u.date.length <= 10 && u.model.length <= 100, "usage ${u.date.take(10)}: bad row")
            check(
                u.inputTokens >= 0 && u.outputTokens >= 0 && u.cacheWrite >= 0 && u.cacheRead >= 0 && u.calls >= 0,
                "usage ${u.date.take(10)}: a negative count",
            )
        }
        check(b.catalogVersion.length <= MAX_TEXT, "the catalogue version is too long")
        check((b.manualTitle?.length ?: 0) <= MAX_TEXT, "the hand-added title is too long")
        return problems
    }
}
