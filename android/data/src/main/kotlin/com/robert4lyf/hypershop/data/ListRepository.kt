package com.robert4lyf.hypershop.data

import androidx.room.withTransaction
import com.robert4lyf.hypershop.core.catalog.Catalog
import com.robert4lyf.hypershop.core.catalog.CatalogLoader
import com.robert4lyf.hypershop.core.merge.ApplyResult
import com.robert4lyf.hypershop.core.merge.LineStatus
import com.robert4lyf.hypershop.core.merge.Merger
import com.robert4lyf.hypershop.core.merge.NewItem
import com.robert4lyf.hypershop.core.merge.Preview
import com.robert4lyf.hypershop.core.merge.Rekey
import com.robert4lyf.hypershop.core.merge.RowDecision
import com.robert4lyf.hypershop.core.merge.ShoppingList
import com.robert4lyf.hypershop.core.merge.TickSource
import com.robert4lyf.hypershop.core.merge.UseKind
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

/** A stored import: the recipe and its line ids, in line order. */
data class StoredRecipe(
    val recipe: RecipeEntity,
    val lines: List<Pair<RecipeLineEntity, ParsedIngredient>>,
)

/** The phone's answer to a watch message (PLAN §3.6): always sent. */
data class UseSummary(
    val useId: Long,
    val recipeId: Long?,
    val title: String,
    val url: String?,
    val servings: Int?,
    val itemCount: Int,
    /** Lines left out as in stock on import. */
    val inStockCount: Int,
)

/** A use's title and recipe page, for the item sheet's "From" rows. */
data class UseLink(
    val title: String,
    val url: String?,
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
        (old.items.keys - new.items.keys).toList().takeIf { it.isNotEmpty() }?.let { dao.deleteItems(it) }
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
    ): StoredRecipe =
        db.withTransaction {
            val r = RecipeEntity(0, url, title, servings, locale.name, clock(), source, parsedBy.name, model, claudePending)
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
                .filter { (u, cs) -> u.archivedAt == null && cs.isNotEmpty() }
                .sortedByDescending { (u, _) -> u.addedAt }
        if (shown.isEmpty()) return emptyList()
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
                title = r?.title ?: if (u.kind == UseKind.MANUAL) "Added by hand" else "Recipe",
                url = r?.url,
                servings = u.servings.takeIf { r?.servingsOriginal != null },
                itemCount = cs.map { it.itemId }.distinct().size,
                inStockCount = inStock[u.id] ?: 0,
            )
        }
    }

    /** Title and recipe page of each use in [useIds] (as found in [l]), for the item sheet: one query. */
    suspend fun useLinks(
        l: ShoppingList,
        useIds: Collection<Long>,
    ): Map<Long, UseLink> {
        val recipes = recipesById(useIds.mapNotNull { l.uses[it]?.recipeId })
        return useIds.associateWith { id ->
            val u = l.uses[id]
            val r = u?.recipeId?.let { recipes[it] }
            UseLink(r?.title ?: if (u?.kind == UseKind.MANUAL) "Added by hand" else "Recipe", r?.url)
        }
    }

    private suspend fun recipesById(ids: Collection<Long>): Map<Long, RecipeEntity> =
        ids.distinct().chunked(MAX_ARGS).flatMap { dao.recipes(it) }.associateBy { it.id }

    /** "Add the in-stock items now": the lines of [useId] that were left out as in stock. */
    suspend fun inStockLines(useId: Long): Pair<RecipeEntity, List<ParsedIngredient>>? =
        db.withTransaction {
            val recipeId = load().uses[useId]?.recipeId ?: return@withTransaction null
            val recipe = dao.recipe(recipeId) ?: return@withTransaction null
            val inStock = dao.decisions(useId).filter { it.status == LineStatus.IN_STOCK.name }.map { it.recipeLineId }.toSet()
            val lines =
                dao.lines(recipeId).filter { it.id in inStock }.map { json.decodeFromString(ParsedIngredient.serializer(), it.parsedJson) }
            recipe to lines
        }

    suspend fun preview(
        stored: StoredRecipe,
        servings: Int?,
        decisions: Map<String, RowDecision>? = null,
        /** "Just this time" aisle moves from the review screen. */
        extraOverrides: Map<String, AisleCode> = emptyMap(),
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
        return merger.preview(snapshot(), incoming, d)
    }

    /**
     * "Add N items" on the review screen (Stale → "List changed — review updated"). [jobId], the import being reviewed, is marked done in the same
     * transaction, so a crash can't leave it offered again after the items went on.
     */
    suspend fun apply(
        preview: Preview,
        jobId: Long? = null,
    ): ApplyResult =
        db.withTransaction {
            val old = load()
            when (val r = merger.apply(old, preview, clock())) {
                is ApplyResult.Applied -> {
                    persist(old, r.list)
                    dao.putDecisions(preview.lineStatus.map { (line, s) -> LineDecisionEntity(r.useId, line, s.name) })
                    jobId?.let { dao.setJobState(it, "DONE") }
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

    suspend fun removeUse(useId: Long) = write { it.removeUse(useId) to Unit }

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

    /** Purges soft deletes older than 7 days. */
    suspend fun purgeDeleted() =
        db.withTransaction {
            val ids = dao.deletedBefore(clock() - ShoppingList.SEVEN_DAYS)
            if (ids.isNotEmpty()) {
                val old = load()
                persist(old, old.copy(items = old.items - ids.toSet(), contributions = old.contributions.filter { it.itemId !in ids }))
                dao.undelete(ids)
            }
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

    /** Run when the catalogue version changed. Overrides you set win over seeded ones. */
    suspend fun rekeyIfCatalogChanged(): Boolean =
        db.withTransaction {
            val meta = dao.meta()!!
            if (meta.catalogVersion == catalog.version) return@withTransaction false
            val old = load()
            val r = Rekey.rekey(old, normaliser)
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
            dao.putMeta(dao.meta()!!.copy(catalogVersion = catalog.version))
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
                    reply(m.installId, last, full = true)
                }
                is WatchMessage.Bye -> reply(m.installId, dao.install(m.installId)?.lastSeq ?: 0, full = false)
                is WatchMessage.Ops -> {
                    val inst = dao.install(m.installId) ?: WatchInstallEntity(m.installId, m.ops.first().seq - 1, 0)
                    var last = inst.lastSeq
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

    companion object {
        const val RESULT_HISTORY = 64

        /** Below SQLite's 999 bound variables. */
        private const val MAX_ARGS = 500
    }
}
