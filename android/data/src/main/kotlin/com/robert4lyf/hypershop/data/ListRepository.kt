package com.robert4lyf.hypershop.data

import androidx.room.withTransaction
import com.robert4lyf.hypershop.core.catalog.Catalog
import com.robert4lyf.hypershop.core.catalog.CatalogLoader
import com.robert4lyf.hypershop.core.merge.ApplyResult
import com.robert4lyf.hypershop.core.merge.Display
import com.robert4lyf.hypershop.core.merge.LineStatus
import com.robert4lyf.hypershop.core.merge.Merger
import com.robert4lyf.hypershop.core.merge.NewItem
import com.robert4lyf.hypershop.core.merge.Preview
import com.robert4lyf.hypershop.core.merge.Rekey
import com.robert4lyf.hypershop.core.merge.RemovedUse
import com.robert4lyf.hypershop.core.merge.RowDecision
import com.robert4lyf.hypershop.core.merge.ShoppingList
import com.robert4lyf.hypershop.core.merge.TickSource
import com.robert4lyf.hypershop.core.merge.UseKind
import com.robert4lyf.hypershop.core.merge.UseLineAdd
import com.robert4lyf.hypershop.core.merge.WatchTickResult
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.core.model.ParsedIngredient
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.QtyUnit
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.Normaliser
import com.robert4lyf.hypershop.core.sync.OpResult
import com.robert4lyf.hypershop.core.sync.WatchMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.serialization.json.Json

/** A recipe line marked in stock on review, for a recipe still on the list. */
data class InStockLine(
    val useId: Long,
    val recipeTitle: String,
    val recipeLineId: Long,
    val target: NewItem,
    /** Scaled to the use's servings. */
    val qty: QtyBase,
    val noScale: Boolean,
    val unitHint: QtyUnit?,
)

/** One recipe in the library (the Recipes tab's "Your recipes"). */
data class LibraryRecipe(
    val recipeId: Long,
    val key: String,
    val title: String,
    val url: String?,
    val lines: Int,
    val lastAddedAt: Long,
    val book: String? = null,
    val page: String? = null,
)

/** What "Edit" on the Recipes tab reopens: see [ListRepository.editSource]. */
data class EditSource(
    val recipe: RecipeEntity,
    val lines: List<ParsedIngredient>,
    val inStockLines: List<Int>,
    val servings: Int,
)

/** A stored import: the recipe and its line ids, in line order. */
data class StoredRecipe(
    val recipe: RecipeEntity,
    val lines: List<Pair<RecipeLineEntity, ParsedIngredient>>,
)

/** A recipe on the list (or the items typed in), for the Recipes tab's "On your list". */
data class UseSummary(
    val useId: Long,
    val recipeId: Long?,
    val title: String,
    val url: String?,
    val servings: Int?,
    val itemCount: Int,
    /** Lines left out as in stock on import. */
    val inStockCount: Int,
    /** Its recipe's library entry ([LibraryRecipe.key]), for "On your list" in the library. */
    val libraryKey: String? = null,
    /** "Ottolenghi Simple · p. 142" ([bookRef]). */
    val bookRef: String? = null,
)

/** A use's title and recipe page, for the item sheet's "From" rows. */
data class UseLink(
    val title: String,
    val url: String?,
)

/** What "Remove from list" took off, for Undo: the list rows and the use's review decisions. */
data class UseRemoval(
    val removed: RemovedUse,
    val decisions: List<LineDecisionEntity>,
)

/** "Finish shopping": the stamp for Undo and how many ticked items went. */
data class Finished(
    val stamp: Long,
    val removed: Int,
)

data class WatchReply(
    val ackedThrough: Int,
    val ackExceptions: List<Pair<Int, OpResult>>,
    val fullSnapshot: Boolean,
)

/** One aisle as the watch sees it, in walking order. */
data class WatchAisle(
    val code: AisleCode,
    val name: String,
    val hidden: Boolean,
)

/** What a watch snapshot is built from, read in one transaction. */
data class WatchListView(
    val list: ShoppingList,
    val aisles: List<WatchAisle>,
    val epoch: Int,
)

/**
 * Every list write goes through here (BangGym's rule): one Room transaction
 * that loads the list, applies a pure [ShoppingList] operation from `:core`,
 * and writes the difference. The UI and the watch bridge share it.
 */
class ListRepository(
    private val db: HyperShopDatabase,
    private val catalog: Catalog = CatalogLoader.default,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Where the shared [list] reads run; lives as long as the repository (the app). */
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val dao = db.dao()
    private val normaliser = Normaliser(catalog)
    val merger = Merger(catalog, normaliser)
    private val json = Json { ignoreUnknownKeys = true }

    // ---- reading ------------------------------------------------------------------

    suspend fun snapshot(): ShoppingList = db.withTransaction { load() }

    /**
     * The list, re-read once whenever listRev changes and shared by every
     * collector (the list, the Recipes tab, ...), so one change is one load
     * however many screens watch it. Stops 5 s after the last collector goes;
     * a later collector then waits for a fresh read rather than a stale one.
     */
    val list: Flow<ShoppingList> =
        dao.observeListRev()
            .filterNotNull()
            .distinctUntilChanged()
            .map { snapshot() }
            .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0), replay = 1)

    /** The shared [list]. */
    fun observe(): Flow<ShoppingList> = list

    /** Claude usage rows, re-emitted whenever one changes (Settings' spend). */
    fun observeUsage(): Flow<List<ClaudeUsageEntity>> = dao.observeUsage()

    suspend fun aisleOrder(): List<AisleCode> {
        val hidden = dao.aisles().filter { it.hidden }.map { it.code }.toSet()
        return dao.aisleOrder().map { AisleCode.valueOf(it.aisleCode) }.filter { it.name !in hidden }
    }

    /** Settings › Aisles: the store's walking order. Bumps listRev, so the list (and the watch) re-read it. */
    suspend fun setAisleOrder(order: List<AisleCode>) =
        db.withTransaction {
            dao.putAisleOrder(order.mapIndexed { i, a -> AisleOrderEntity(1, a.name, i) })
            val meta = dao.meta()!!
            dao.putMeta(meta.copy(listRev = meta.listRev + 1))
        }

    /** The walking order, re-read whenever listRev changes. */
    fun observeAisleOrder(): Flow<List<AisleCode>> =
        dao.observeListRev().filterNotNull().distinctUntilChanged().map { aisleOrder() }.distinctUntilChanged()

    /** The units setting changed: every live item's text changed, so bump every rev and listRev once. */
    suspend fun unitsChanged() = write { it.unitsChanged() to Unit }

    /** "Always put X in Y" from the review screen. */
    suspend fun setOverride(
        mergeKey: String,
        aisle: AisleCode,
    ) = dao.putOverride(CategoryOverrideEntity(mergeKey, aisle.name, "USER"))

    /** Adds one Claude call to today's usage row, atomically. */
    suspend fun recordUsage(
        date: String,
        model: String,
        inputTokens: Long,
        outputTokens: Long,
        cacheWrite: Long,
        cacheRead: Long,
    ) = db.withTransaction {
        dao.insertUsageIfAbsent(ClaudeUsageEntity(date, model, 0, 0, 0, 0, 0))
        dao.addUsage(date, model, inputTokens, outputTokens, cacheWrite, cacheRead)
    }

    fun observeStaples(): Flow<Set<String>> = dao.observeStaples().map { rows -> rows.map { it.key }.toSet() }

    suspend fun staples(): Set<String> = dao.staples().map { it.key }.toSet()

    suspend fun overrides(): Map<String, AisleCode> = dao.overrides().associate { it.key to AisleCode.valueOf(it.aisleCode) }

    private suspend fun load(): ShoppingList {
        val meta = requireNotNull(dao.meta()) { "Meta row missing" }
        return ShoppingList(
            items = dao.items().map(Mapping::item).associateBy { it.id },
            uses = dao.uses().map(Mapping::use).associateBy { it.id },
            contributions = dao.contributions().map(Mapping::contribution),
            ticks = dao.ticks().map(Mapping::tick),
            aliases = dao.aliases().associate { it.oldId to it.newId },
            listRev = meta.listRev,
            nextId = meta.nextId,
        )
    }

    /**
     * Writes [new] over [old]: inserts and updates parents first, deletes children first.
     * Items whose mergeKey changed (a re-key) are written last, after the items
     * merged into them are gone: the loser may still hold the new key, and live
     * keys are unique.
     */
    private suspend fun persist(
        old: ShoppingList,
        new: ShoppingList,
    ) {
        val now = clock()
        // Items gone from the list go first (their ticks and contributions cascade): an
        // edit can drop an item and create another with the same key, and keys are
        // unique among live items.
        (old.items.keys - new.items.keys).toList().takeIf { it.isNotEmpty() }?.let { dao.deleteItems(it) }
        val changedItems = new.items.values.filter { old.items[it.id] != it }
        val (rekeyed, others) = changedItems.partition { i -> old.items[i.id]?.let { it.mergeKey != i.mergeKey } == true }
        dao.upsertItems(others.map { Mapping.item(it, now) })
        val newlyDeleted = changedItems.filter { it.deletedAt != null && old.items[it.id]?.deletedAt == null }
        dao.putDeleted(newlyDeleted.map { DeletedItemEntity(it.id, it.deletedAt!!) })
        val undeleted = changedItems.filter { it.deletedAt == null && old.items[it.id]?.deletedAt != null }.map { it.id }
        if (undeleted.isNotEmpty()) dao.undelete(undeleted)
        dao.upsertUses(new.uses.values.filter { old.uses[it.id] != it }.map(Mapping::use))
        val oldTicks = old.ticks.associateBy { it.id }
        dao.upsertTicks(new.ticks.filter { oldTicks[it.id] != it }.map(Mapping::tick))
        val oldCs = old.contributions.associateBy { it.id }
        dao.upsertContributions(new.contributions.filter { oldCs[it.id] != it }.map(Mapping::contribution))
        dao.upsertAliases(new.aliases.filter { old.aliases[it.key] != it.value }.map { ItemAliasEntity(it.key, it.value) })

        val newCs = new.contributions.map { it.id }.toSet()
        old.contributions.map { it.id }.filter { it !in newCs }.takeIf { it.isNotEmpty() }?.let { dao.deleteContributions(it) }
        val newTicks = new.ticks.map { it.id }.toSet()
        old.ticks.map { it.id }.filter { it !in newTicks }.takeIf { it.isNotEmpty() }?.let { dao.deleteTicks(it) }
        (old.uses.keys - new.uses.keys).toList().takeIf { it.isNotEmpty() }?.let { dao.deleteUses(it) }
        dao.upsertItems(rekeyed.map { Mapping.item(it, now) })

        val meta = dao.meta()!!
        dao.putMeta(meta.copy(listRev = new.listRev, nextId = new.nextId))
    }

    /** One transaction: load, apply [op], persist. Null from [op] means no change. */
    private suspend fun <T> write(op: suspend (ShoppingList) -> Pair<ShoppingList, T>?): T? =
        db.withTransaction {
            val old = load()
            val r = op(old) ?: return@withTransaction null
            persist(old, r.first)
            r.second
        }

    // ---- imports (5.2b, 5.2c) ----------------------------------------------------------

    suspend fun storeRecipe(
        url: String?,
        title: String?,
        servings: Int?,
        locale: UnitLocale,
        source: String,
        parsedBy: ParsedBy,
        model: String?,
        claudePending: Boolean,
        lines: List<ParsedIngredient>,
        /** The library entry this is a new copy of (an edit or a re-add); null for a new recipe. */
        libraryKey: String? = null,
        book: String? = null,
        page: String? = null,
    ): StoredRecipe =
        db.withTransaction {
            val r =
                RecipeEntity(
                    0,
                    url,
                    title,
                    servings,
                    locale.name,
                    clock(),
                    source,
                    parsedBy.name,
                    model,
                    claudePending,
                    libraryKey = libraryKey,
                    book = book?.trim()?.take(MAX_BOOK)?.ifEmpty { null },
                    page = page?.trim()?.take(MAX_PAGE)?.ifEmpty { null },
                )
            val id = dao.insertRecipe(r)
            val rows = lines.map { RecipeLineEntity(0, id, it.section, it.raw, json.encodeToString(ParsedIngredient.serializer(), it)) }
            val ids = dao.insertLines(rows)
            StoredRecipe(r.copy(id = id), rows.zip(ids).zip(lines) { (row, lid), p -> row.copy(id = lid) to p })
        }

    /** Drops a stored recipe that was never confirmed (Cancel, or a re-parse). */
    suspend fun discardRecipe(id: Long) =
        db.withTransaction {
            if (dao.uses().none { it.recipeId == id }) dao.deleteRecipe(id)
        }

    /** "Already on your list — add again?": a use of this page that [usesOnList] would show. */
    suspend fun alreadyOnList(url: String): Boolean =
        db.withTransaction {
            val recipes = dao.recipesByUrl(url).map { it.id }.toSet()
            if (recipes.isEmpty()) return@withTransaction false
            val l = load()
            val live = l.liveItems.map { it.id }.toSet()
            l.uses.values.any { u ->
                u.recipeId in recipes && u.archivedAt == null && l.contributions.any { it.useId == u.id && it.itemId in live }
            }
        }

    /** The Recipes tab: uses with something on the list, newest first. */
    suspend fun usesOnList(): List<UseSummary> = db.withTransaction { usesOnList(load()) }

    /**
     * [usesOnList] for a list already loaded (from [list]): the recipes and
     * the in-stock decisions come in one query each, not two per use.
     */
    suspend fun usesOnList(l: ShoppingList): List<UseSummary> {
        val live = l.liveItems.map { it.id }.toSet()
        val shown =
            l.uses.values
                .map { u -> u to l.contributionsOfUse(u.id).filter { it.itemId in live } }
                // A recipe stays once everything is bought, as the week's reminder of what to
                // cook, until "Remove from list" or the 7-day archive at "Finish shopping".
                // Hand-added items are only shopping, so that card goes with its last item.
                .filter { (u, cs) -> u.archivedAt == null && (cs.isNotEmpty() || u.kind == UseKind.RECIPE) }
                .sortedByDescending { (u, _) -> u.addedAt }
        if (shown.isEmpty()) return emptyList()
        val manual = manualTitle()
        val recipes = recipesById(shown.mapNotNull { (u, _) -> u.recipeId })
        val inStock =
            shown.map { (u, _) -> u.id }.chunked(MAX_ARGS).flatMap { dao.decisionsFor(it) }
                .filter { it.status == LineStatus.IN_STOCK.name }
                .groupingBy { it.useId }
                .eachCount()
        return shown.map { (u, cs) ->
            val r = u.recipeId?.let { recipes[it] }
            UseSummary(
                useId = u.id,
                recipeId = u.recipeId,
                title = r?.title ?: if (u.kind == UseKind.MANUAL) manual else "Recipe",
                url = r?.url,
                servings = u.servings.takeIf { r?.servingsOriginal != null },
                itemCount = cs.map { it.itemId }.distinct().size,
                inStockCount = inStock[u.id] ?: 0,
                libraryKey = r?.let { it.libraryKey ?: it.url ?: "r:${it.id}" },
                bookRef = r?.let { bookRef(it.book, it.page) },
            )
        }
    }

    /** Title and recipe page of each use in [useIds] (as found in [l]), for the item sheet: one query. */
    suspend fun useLinks(
        l: ShoppingList,
        useIds: Collection<Long>,
    ): Map<Long, UseLink> {
        val recipes = recipesById(useIds.mapNotNull { l.uses[it]?.recipeId })
        val manual = manualTitle()
        return useIds.associateWith { id ->
            val u = l.uses[id]
            val r = u?.recipeId?.let { recipes[it] }
            UseLink(r?.title ?: if (u?.kind == UseKind.MANUAL) manual else "Recipe", r?.url)
        }
    }

    /** What "Added by hand" is called. */
    suspend fun manualTitle(): String = dao.meta()?.manualTitle ?: MANUAL_TITLE

    /**
     * Renames a use: a recipe's every copy (so the library shows it too), or
     * "Added by hand" itself, where blank goes back to that. Bumps listRev, so
     * the list and the Recipes tab re-read.
     */
    suspend fun renameUse(
        useId: Long,
        title: String,
    ) = db.withTransaction {
        val l = load()
        val use = l.uses[useId] ?: return@withTransaction
        val name = title.trim().take(MAX_TITLE)
        val meta = dao.meta()!!
        if (use.kind == UseKind.MANUAL) {
            dao.putMeta(meta.copy(manualTitle = name.takeIf { it.isNotEmpty() && it != MANUAL_TITLE }, listRev = meta.listRev + 1))
            return@withTransaction
        }
        if (name.isEmpty()) return@withTransaction
        val r = use.recipeId?.let { dao.recipe(it) } ?: return@withTransaction
        dao.renameRecipes(r.libraryKey ?: r.url ?: "r:${r.id}", name)
        dao.putMeta(meta.copy(listRev = meta.listRev + 1))
    }

    /** Renames a library entry, and any copy of it on the list. */
    suspend fun renameLibrary(
        key: String,
        title: String,
    ) = db.withTransaction {
        val name = title.trim().take(MAX_TITLE)
        if (name.isEmpty() || dao.renameRecipes(key, name) == 0) return@withTransaction
        val meta = dao.meta()!!
        dao.putMeta(meta.copy(listRev = meta.listRev + 1))
    }

    private suspend fun recipesById(ids: Collection<Long>): Map<Long, RecipeEntity> =
        ids.distinct().chunked(MAX_ARGS).flatMap { dao.recipes(it) }.associateBy { it.id }

    /**
     * What you marked in stock for the recipes on the list ([usesOnList]'s
     * recipes), line by line: one query for the decisions, one for the recipes
     * and one for their lines (chunked), each line parsed once.
     */
    suspend fun inStockOnList(l: ShoppingList): List<InStockLine> {
        val live = l.liveItems.map { it.id }.toSet()
        val uses =
            l.uses.values.filter { u ->
                u.archivedAt == null && u.recipeId != null && l.contributionsOfUse(u.id).any { it.itemId in live }
            }
        if (uses.isEmpty()) return emptyList()
        val inStock =
            uses.map { it.id }.chunked(MAX_ARGS).flatMap { dao.decisionsFor(it) }
                .filter { it.status == LineStatus.IN_STOCK.name }
                .groupBy({ it.useId }, { it.recipeLineId })
        if (inStock.isEmpty()) return emptyList()
        val withInStock = uses.filter { it.id in inStock }
        val recipes = recipesById(withInStock.mapNotNull { it.recipeId })
        val ov = overrides()
        // A line already on the list ("Need it") isn't in stock any more.
        val onList = l.contributions.filter { it.itemId in live }.mapNotNull { it.recipeLineId }.toSet()
        val wanted = inStock.values.flatten().toSet() - onList
        val rows = recipes.keys.toList().chunked(MAX_ARGS).flatMap { dao.linesFor(it) }.filter { it.id in wanted }.groupBy { it.recipeId }
        // Two uses of one recipe share its parsed lines.
        val parsedByRecipe = HashMap<Long, List<Pair<Long, ParsedIngredient>>>()
        return withInStock.sortedBy { it.addedAt }.flatMap { u ->
            val ids = inStock.getValue(u.id).toSet() - onList
            val r = recipes[u.recipeId] ?: return@flatMap emptyList()
            val parsed =
                parsedByRecipe.getOrPut(r.id) {
                    rows[r.id].orEmpty().map { it.id to json.decodeFromString(ParsedIngredient.serializer(), it.parsedJson) }
                }.filter { it.first in ids }
            if (parsed.isEmpty()) return@flatMap emptyList()
            val locale = UnitLocale.valueOf(r.unitLocale)
            val inc = merger.incoming(r.id, r.servingsOriginal, u.servings, locale, parsed, ov)
            inc.lines.map { line ->
                val noScale = line.parsed.noScale
                val q = line.parsed.shopBase(locale).let { if (noScale) it else it.times(inc.scale) }
                InStockLine(u.id, r.title ?: "Recipe", line.recipeLineId, line.target, q, noScale || q.isPresence, line.parsed.shopUnit)
            }
        }
    }

    /**
     * "Need it": every in-stock line for [mergeKey] goes on the list, under its
     * recipe, and stops counting as in stock. One transaction. The number of lines added.
     */
    suspend fun needAfterAll(mergeKey: String): Int =
        db.withTransaction {
            val old = load()
            val lines = inStockOnList(old).filter { it.target.mergeKey == mergeKey }
            if (lines.isEmpty()) return@withTransaction 0
            var l = old
            for ((useId, ls) in lines.groupBy { it.useId }) {
                l = l.addToUse(useId, ls.map { UseLineAdd(it.target, it.recipeLineId, it.qty, it.noScale, it.unitHint) }) ?: l
            }
            persist(old, l)
            dao.putDecisions(lines.map { LineDecisionEntity(it.useId, it.recipeLineId, LineStatus.ADDED.name) })
            lines.size
        }

    // ---- the recipe library -------------------------------------------------------------

    /** Every recipe that has been on the list (its latest copy), the one added longest ago first. */
    fun observeLibrary(): Flow<List<LibraryRecipe>> =
        dao.observeLibrary().map { rows ->
            val counts = rows.map { it.id }.chunked(MAX_ARGS).flatMap { dao.lineCounts(it) }.associate { it.recipeId to it.n }
            rows.map { r ->
                LibraryRecipe(
                    r.id,
                    r.libraryKey ?: "r:${r.id}",
                    r.title ?: "Recipe",
                    r.url,
                    counts[r.id] ?: 0,
                    r.lastAddedAt ?: 0,
                    r.book,
                    r.page,
                )
            }
        }

    /**
     * In the transaction that put [recipeId] on the list: it becomes its library
     * entry's latest copy. Older unused copies go, except the one before it, which
     * Undo brings back.
     */
    private suspend fun addedToLibrary(recipeId: Long) {
        dao.markAdded(recipeId, clock())
        val key = dao.recipe(recipeId)?.libraryKey ?: return
        val older =
            dao.recipesByKey(key)
                .filter { it.id != recipeId && it.lastAddedAt != null }
                .sortedWith(compareByDescending<RecipeEntity> { it.lastAddedAt }.thenByDescending { it.id })
        for (r in older.drop(1)) if (dao.usesOfRecipe(r.id) == 0) dao.deleteRecipe(r.id)
    }

    /**
     * "Remove from your recipes": leaves the library. The copies stay until the
     * start-up purge, so [restoreToLibrary] (Undo) can bring the entry back.
     * Returns what to restore: each copy and when it was added.
     */
    suspend fun removeFromLibrary(key: String): List<Pair<Long, Long>> =
        db.withTransaction {
            val copies = dao.recipesByKey(key).filter { it.lastAddedAt != null }.map { it.id to it.lastAddedAt!! }
            dao.forgetKey(key)
            copies
        }

    /** Undo of [removeFromLibrary]. */
    suspend fun restoreToLibrary(copies: List<Pair<Long, Long>>) =
        db.withTransaction {
            for ((id, at) in copies) dao.setLastAdded(id, at)
        }

    /** The parsed lines of each recipe in [ids] (the recipe search's index): one query per 900. */
    suspend fun ingredientsOf(ids: List<Long>): Map<Long, List<ParsedIngredient>> =
        ids.distinct().chunked(MAX_ARGS).flatMap { dao.linesFor(it) }.groupBy({ it.recipeId }) {
            json.decodeFromString(ParsedIngredient.serializer(), it.parsedJson)
        }

    /** "Add to list" from the library: the recipe and its lines, as stored. */
    suspend fun librarySource(recipeId: Long): Pair<RecipeEntity, List<ParsedIngredient>>? =
        db.withTransaction {
            val recipe = dao.recipe(recipeId) ?: return@withTransaction null
            recipe to dao.lines(recipeId).map { json.decodeFromString(ParsedIngredient.serializer(), it.parsedJson) }
        }

    /**
     * "Edit" on the Recipes tab: the recipe as it went on the list for [useId] —
     * the recipe, its lines in order, the indices of those marked in stock — and
     * the use's servings. Null when the use or recipe is gone.
     */
    suspend fun editSource(useId: Long): EditSource? =
        db.withTransaction {
            val use = load().uses[useId] ?: return@withTransaction null
            val recipe = use.recipeId?.let { dao.recipe(it) } ?: return@withTransaction null
            val rows = dao.lines(recipe.id)
            val inStock = dao.decisions(useId).filter { it.status == LineStatus.IN_STOCK.name }.map { it.recipeLineId }.toSet()
            EditSource(
                recipe,
                rows.map { json.decodeFromString(ParsedIngredient.serializer(), it.parsedJson) },
                rows.indices.filter { rows[it].id in inStock },
                use.servings,
            )
        }

    /**
     * "Edit" on "Added by hand": its items as lines to review ("500 g mince"), in
     * list order, or null when [useId] isn't the hand-added use.
     */
    suspend fun manualLines(useId: Long): List<String>? =
        db.withTransaction {
            val l = load()
            val use = l.uses[useId]?.takeIf { it.kind == UseKind.MANUAL } ?: return@withTransaction null
            l.contributions
                .filter { it.useId == use.id }
                .groupBy { it.itemId }
                .mapNotNull { (itemId, cs) -> l.items[itemId]?.takeIf { it.deletedAt == null }?.let { it to cs } }
                .sortedBy { (item, _) -> item.position }
                .map { (item, cs) ->
                    val total = cs.map { it.qty }.reduce { a, b -> a + b }
                    val produce = item.aisle == AisleCode.PRODUCE
                    listOfNotNull(Display.amount(total, produce = produce, hint = cs.last().unitHint), item.displayName).joinToString(" ")
                }
        }

    /** The list a review shows against: without [replacing], the use an edit replaces. */
    private fun without(
        l: ShoppingList,
        replacing: Long?,
    ): ShoppingList = replacing?.takeIf { it in l.uses }?.let { l.removeUse(it) } ?: l

    suspend fun preview(
        stored: StoredRecipe,
        servings: Int?,
        decisions: Map<String, RowDecision>? = null,
        /** "Just this time" aisle moves from the review screen. */
        extraOverrides: Map<String, AisleCode> = emptyMap(),
        /** The use an edit replaces, left out of the list the preview is against. */
        replacing: Long? = null,
    ): Preview {
        val incoming =
            merger.incoming(
                stored.recipe.id,
                stored.recipe.servingsOriginal,
                servings ?: stored.recipe.servingsOriginal,
                UnitLocale.valueOf(stored.recipe.unitLocale),
                stored.lines.map { (row, p) -> row.id to p },
                overrides() + extraOverrides,
            )
        val d = decisions ?: merger.defaultDecisions(incoming, staples())
        return merger.preview(without(snapshot(), replacing), incoming, d)
    }

    /**
     * "Add N items" on the review screen (Stale → "List changed — review updated"). [jobId], the import being reviewed, is marked done in the same
     * transaction, so a crash can't leave it offered again after the items went on.
     */
    suspend fun apply(
        preview: Preview,
        jobId: Long? = null,
        /** An edit: this use (and its items, ticks and decisions) is replaced by the new one. */
        replacing: Long? = null,
        /** An edit of "Added by hand": the lines go back as hand-added items, with no recipe kept. */
        manual: Boolean = false,
    ): ApplyResult =
        db.withTransaction {
            val old = load()
            val oldRecipe = replacing?.let { old.uses[it]?.recipeId }
            val kind = if (manual) UseKind.MANUAL else UseKind.RECIPE
            when (val r = merger.apply(without(old, replacing), preview, clock(), kind)) {
                is ApplyResult.Applied -> {
                    persist(old, r.list)
                    jobId?.let { dao.setJobState(it, "DONE") }
                    if (manual) {
                        // The review's stored copy was only there to be reviewed.
                        preview.incoming.recipeId?.let { id ->
                            db.openHelper.writableDatabase.execSQL("DELETE FROM Recipe WHERE id = ?", arrayOf(id))
                        }
                        return@withTransaction r
                    }
                    dao.putDecisions(preview.lineStatus.map { (line, s) -> LineDecisionEntity(r.useId, line, s.name) })
                    preview.incoming.recipeId?.let { addedToLibrary(it) }
                    // The edited recipe was stored afresh; the old one goes once nothing uses it.
                    if (oldRecipe != null && oldRecipe != preview.incoming.recipeId && dao.uses().none { it.recipeId == oldRecipe }) {
                        db.openHelper.writableDatabase.execSQL("DELETE FROM Recipe WHERE id = ?", arrayOf(oldRecipe))
                    }
                    r
                }
                ApplyResult.Stale -> r
            }
        }

    /** Undo of an import: the use and everything that cascades, and the recipe if nothing else uses it. */
    suspend fun undoImport(useId: Long) {
        db.withTransaction {
            val old = load()
            val recipeId = old.uses[useId]?.recipeId
            persist(old, old.removeUse(useId))
            if (recipeId != null && dao.uses().none { it.recipeId == recipeId }) {
                db.openHelper.writableDatabase.execSQL("DELETE FROM Recipe WHERE id = ?", arrayOf(recipeId))
            }
        }
    }

    /** "Remove from list": what went, for [restoreUse] (Undo); null when there was no such use. */
    suspend fun removeUse(useId: Long): UseRemoval? =
        db.withTransaction {
            val old = load()
            val (next, removed) = old.removeUseKeeping(useId)
            if (removed == null) return@withTransaction null
            // The use's review decisions cascade with it; Undo wants them back too.
            val decisions = dao.decisions(useId)
            persist(old, next)
            UseRemoval(removed, decisions)
        }

    /**
     * Undo of [removeUse]. False when the use was back already, or its recipe copy
     * has gone meanwhile (the start-up purge; a use must have its recipe).
     */
    suspend fun restoreUse(r: UseRemoval): Boolean =
        db.withTransaction {
            val recipeId = r.removed.use.recipeId
            if (recipeId != null && dao.recipe(recipeId) == null) return@withTransaction false
            val old = load()
            var next = old.restoreUse(r.removed) ?: return@withTransaction false
            // An item that came back under a key the catalogue has since moved on from
            // (an ingredient edit) joins the item now at the new key, or takes it.
            for (item in r.removed.items) {
                val restored = next.items[item.id] ?: continue
                if (restored.normName.isEmpty()) continue
                val target = merger.target(restored.normName, null, overrides())
                if (target.mergeKey != restored.mergeKey) next = next.swapItem(item.id, target) ?: next
            }
            persist(old, next)
            // Only decisions of lines the copy still has: a decision's line is a foreign key.
            val lines = recipeId?.let { id -> dao.lines(id).map { it.id }.toSet() }.orEmpty()
            dao.putDecisions(r.decisions.filter { it.recipeLineId in lines })
            true
        }

    suspend fun rescaleUse(
        useId: Long,
        servings: Int,
    ) = write { it.rescaleUse(useId, servings) to Unit }

    suspend fun lineStatuses(useId: Long): Map<Long, LineStatus> =
        dao.decisions(useId).associate {
            it.recipeLineId to LineStatus.valueOf(it.status)
        }

    // ---- ticking and manual changes (5.2b, 5.2c) -----------------------------------------

    suspend fun tick(itemId: Long): Boolean = write { l -> l.tick(itemId, clock(), TickSource.PHONE)?.let { it to true } } ?: false

    suspend fun untick(itemId: Long): Boolean = write { l -> l.untick(itemId)?.let { it to true } } ?: false

    /**
     * The tick button: unticks a DONE item, else ticks it. Decided inside the
     * write, so a quick second tap undoes the first even before the list redraws.
     */
    suspend fun toggle(itemId: Long): Boolean =
        write { l ->
            (if (l.isDone(itemId)) l.untick(itemId) else l.tick(itemId, clock(), TickSource.PHONE))?.let { it to true }
        } ?: false

    suspend fun manualAdd(
        name: String,
        qty: QtyBase,
        unitHint: QtyUnit?,
    ): Long =
        write { l ->
            val target = merger.target(name, null, overrides())
            l.manualAdd(target, qty, unitHint, clock())
        }!!

    fun target(name: String): NewItem = merger.target(name)

    suspend fun editQuantity(
        itemId: Long,
        total: QtyBase,
    ) = write { it.editQuantity(itemId, total, clock()) to Unit }

    /** Soft-deletes; returns the stamp for Undo. */
    suspend fun softDelete(itemId: Long): Long {
        val stamp = clock()
        write { it.softDelete(itemId, stamp) to Unit }
        return stamp
    }

    suspend fun finishShopping(): Long = finish().stamp

    /** "Finish shopping", with the number of ticked items it removed, counted inside the write. */
    suspend fun finish(): Finished {
        val stamp = clock()
        val removed = write { l -> l.finishShopping(stamp) to l.liveItems.count { l.isDone(it.id) } } ?: 0
        return Finished(stamp, removed)
    }

    suspend fun clearAll(): Long {
        val stamp = clock()
        write { it.clearAll(stamp) to Unit }
        return stamp
    }

    suspend fun undoDelete(stamp: Long) = write { it.undoDelete(stamp) to Unit }

    /**
     * Swaps the item for [name] (an ingredient, or anything typed): it merges into
     * that item if it is already on the list. The new item's name, or null when
     * nothing changed (no such item, or it already is that).
     */
    suspend fun swapItem(
        itemId: Long,
        name: String,
    ): String? =
        write { l ->
            val target = merger.target(name, null, overrides())
            l.swapItem(itemId, target)?.let { it to target.displayName }
        }

    /** [swapItem] for the item with this key, if one is on the list ("always use instead" set in the library). */
    suspend fun swapKey(
        mergeKey: String,
        name: String,
    ): String? =
        write { l ->
            val item = l.itemByKey(mergeKey) ?: return@write null
            val target = merger.target(name, null, overrides())
            l.swapItem(item.id, target)?.let { it to target.displayName }
        }

    /**
     * Purges soft deletes older than 7 days, and recipes forgotten from the library
     * that nothing uses, imported more than 7 days ago (a newer one may be a review
     * still open, or restored after process death).
     */
    suspend fun purgeDeleted() =
        db.withTransaction {
            val before = clock() - ShoppingList.SEVEN_DAYS
            val ids = dao.deletedBefore(before)
            if (ids.isNotEmpty()) {
                val old = load()
                persist(old, old.copy(items = old.items - ids.toSet(), contributions = old.contributions.filter { it.itemId !in ids }))
                dao.undelete(ids)
            }
            dao.deleteForgottenRecipes(before)
        }

    /** Move to another aisle; "Always put X in Y?" yes → a user override. */
    suspend fun moveAisle(
        itemId: Long,
        aisle: AisleCode,
        always: Boolean,
    ) = db.withTransaction {
        val old = load()
        val item = old.items[itemId] ?: return@withTransaction
        persist(old, old.moveAisle(itemId, aisle))
        if (always) dao.putOverride(CategoryOverrideEntity(item.mergeKey, aisle.name, "USER"))
    }

    suspend fun setStaple(
        mergeKey: String,
        staple: Boolean,
    ) = if (staple) dao.putStaple(StapleEntity(mergeKey, "USER")) else dao.deleteStaple(mergeKey)

    // ---- catalogue re-key (5.2d) ---------------------------------------------------------

    /**
     * Run when the catalogue version changed. Overrides you set win over seeded ones.
     * One snapshot of the catalogue throughout: an ingredient edit saved meanwhile
     * swaps the live one, and Meta must name the version the list was re-keyed with.
     */
    suspend fun rekeyIfCatalogChanged(): Boolean =
        db.withTransaction {
            val snap = catalog.copy()
            val meta = dao.meta()!!
            if (meta.catalogVersion == snap.version) return@withTransaction false
            val old = load()
            val r = Rekey.rekey(old, Normaliser(snap))
            // Every live item: a row's text can change with the catalogue (pack sizes,
            // densities) even when its key doesn't.
            persist(old, r.list.catalogueChanged())
            val overrides = dao.overrides().associateBy { it.key }
            for ((from, to) in r.renamed) {
                val o = overrides[from] ?: continue
                val existing = overrides[to]
                if (existing == null || (existing.source != "USER" && o.source == "USER")) dao.putOverride(o.copy(key = to))
                dao.deleteOverride(from)
            }
            val staples = dao.staples().associateBy { it.key }
            for ((from, to) in r.renamed) {
                val st = staples[from] ?: continue
                val existing = staples[to]
                if (existing == null || (existing.source != "USER" && st.source == "USER")) dao.putStaple(st.copy(key = to))
                dao.deleteStaple(from)
            }
            dao.putMeta(dao.meta()!!.copy(catalogVersion = snap.version))
            true
        }

    // ---- watch ops (5.2d) ---------------------------------------------------------------

    /**
     * The phone's protocol rules (docs/garmin-protocol.md), in one transaction:
     * epoch, sequence (go-back-N), ItemAlias, coverage by seenListRev, untick of
     * this install's own tick. Results are kept, the last 64 per install.
     */
    suspend fun applyWatch(m: WatchMessage): WatchReply =
        db.withTransaction {
            val meta = dao.meta()!!
            val epoch = meta.listEpoch.toInt()
            when (m) {
                is WatchMessage.Hello -> {
                    val i = dao.install(m.installId)
                    val last = maxOf(i?.lastSeq ?: m.ackedThrough, m.ackedThrough)
                    val offset = clock() - m.watchNow * 1000
                    dao.putInstall(WatchInstallEntity(m.installId, last, offset))
                    // Results the watch has settled are never asked for again.
                    dao.trimOpResults(m.installId, m.ackedThrough)
                    reply(m.installId, last, full = true)
                }
                is WatchMessage.Bye -> reply(m.installId, dao.install(m.installId)?.lastSeq ?: 0, full = false)
                is WatchMessage.Ops -> {
                    val inst = dao.install(m.installId) ?: WatchInstallEntity(m.installId, m.watchAckedThrough, 0)
                    var last = inst.lastSeq
                    // The batch starts right after what the watch has settled, so only results
                    // from there on can still be asked for (a duplicate after a lost ack). The
                    // reply then carries just those exceptions: sending every non-ok result in
                    // the 64-deep history could not fit the payload cap once a handful of noops
                    // had piled up, and the watch then resent the same batch for ever. A batch
                    // with a gap is ignored below, and its claim is ignored with it.
                    if (m.watchAckedThrough <= last) dao.trimOpResults(m.installId, m.watchAckedThrough)
                    var list = load()
                    val start = list
                    for (op in m.ops) {
                        if (op.seq <= last) continue
                        if (op.seq != last + 1) break
                        val result: OpResult =
                            if (m.epoch != epoch) {
                                OpResult.EPOCH
                            } else {
                                val item = list.resolve(op.itemId.toLong())
                                if (item == null) {
                                    OpResult.REJECTED
                                } else {
                                    val at = op.ts * 1000 + inst.clockOffsetMs
                                    val (next, r) =
                                        if (op.done) {
                                            list.watchTick(item.id, op.seenListRev.toLong(), at, m.installId, op.seq)
                                        } else {
                                            list.watchUntick(item.id, m.installId)
                                        }
                                    if (next != null) list = next
                                    when (r) {
                                        WatchTickResult.OK -> OpResult.OK
                                        WatchTickResult.NOOP -> OpResult.NOOP
                                        WatchTickResult.PARTIAL -> OpResult.PARTIAL
                                        WatchTickResult.STILL_DONE -> OpResult.STILL_DONE
                                    }
                                }
                            }
                        last = op.seq
                        dao.putOpResult(WatchOpResultEntity(m.installId, op.seq, result.name))
                    }
                    if (list !== start) persist(start, list)
                    dao.putInstall(inst.copy(lastSeq = last))
                    dao.trimOpResults(m.installId, last - RESULT_HISTORY)
                    reply(m.installId, last, full = list !== start)
                }
            }
        }

    /** The list, the aisles in walking order (hidden ones flagged) and the epoch, for a snapshot. */
    suspend fun watchView(): WatchListView =
        db.withTransaction {
            val byCode = dao.aisles().associateBy { it.code }
            val ordered = dao.aisleOrder().map { it.aisleCode }
            val rest = byCode.values.sortedBy { it.defaultOrder }.map { it.code }.filter { it !in ordered }
            val aisles =
                (ordered + rest).mapNotNull { code ->
                    val a = AisleCode.entries.firstOrNull { it.name == code } ?: return@mapNotNull null
                    val e = byCode[code]
                    WatchAisle(a, e?.name ?: a.displayName, e?.hidden ?: false)
                }
            WatchListView(load(), aisles, dao.meta()!!.listEpoch.toInt())
        }

    /**
     * An unsolicited snapshot's acks for [installId]: everything applied, with
     * the results the watch has not settled. A push without them would let a
     * watch with a batch in flight read every op in it as ok.
     */
    suspend fun watchAck(installId: Int): WatchReply =
        db.withTransaction { reply(installId, dao.install(installId)?.lastSeq ?: 0, full = true) }

    private suspend fun reply(
        installId: Int,
        last: Int,
        full: Boolean,
    ): WatchReply {
        val ax = dao.opResults(installId).filter { it.result != OpResult.OK.name }.map { it.seq to OpResult.valueOf(it.result) }
        return WatchReply(last, ax, full)
    }

    // ---- epoch (PLAN §3.6) --------------------------------------------------------------

    /**
     * Called at start-up. A missing or different marker file (a restore, or a
     * first launch) starts a new list epoch and forgets every watch install.
     */
    suspend fun checkEpoch(marker: EpochMarker): Boolean =
        db.withTransaction {
            val meta = dao.meta()!!
            val file = marker.read()
            if (file != null && file == meta.epochMarker) return@withTransaction false
            val fresh = EpochMarker.newMarker()
            dao.putMeta(meta.copy(listEpoch = EpochMarker.newEpoch(), epochMarker = fresh, listRev = meta.listRev + 1))
            dao.clearInstalls()
            dao.clearOpResults()
            marker.write(fresh)
            true
        }

    suspend fun listEpoch(): Long = dao.meta()!!.listEpoch

    /** The epoch and listRev together, from one Meta read (the watch bridge's ack-only reply needs nothing else). */
    suspend fun epochAndRev(): Pair<Long, Long> = dao.meta()!!.let { it.listEpoch to it.listRev }

    /** Every listRev change, without loading the list: for a subscriber that only needs to know "something changed". */
    fun observeListRev(): Flow<Long> = dao.observeListRev().filterNotNull().distinctUntilChanged()

    /** The watch install last recorded, so a push after the phone process restarted need not wait for the watch to speak. */
    suspend fun lastInstall(): Int? = dao.lastInstall()

    /** Import jobs long finished (reviewed, cancelled or failed) go: their result text is the page, tens of KB each. */
    suspend fun purgeOldJobs(before: Long) = dao.deleteFinishedJobsBefore(before)

    companion object {
        const val RESULT_HISTORY = 64
        const val MAX_BOOK = 120
        const val MAX_PAGE = 20

        /** Below SQLite's 999 bound variables. */
        private const val MAX_ARGS = 500
    }
}

/** "Added by hand", until it is renamed. */
const val MANUAL_TITLE = "Added by hand"

private const val MAX_TITLE = 80
