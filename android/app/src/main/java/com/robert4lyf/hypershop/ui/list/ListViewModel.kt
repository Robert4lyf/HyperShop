package com.robert4lyf.hypershop.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robert4lyf.hypershop.catalog.EntryEdit
import com.robert4lyf.hypershop.core.catalog.CatalogEntry
import com.robert4lyf.hypershop.core.catalog.DisplayPolicy
import com.robert4lyf.hypershop.core.merge.Display
import com.robert4lyf.hypershop.core.merge.ShoppingList
import com.robert4lyf.hypershop.core.merge.UnitsSetting
import com.robert4lyf.hypershop.core.merge.UseKind
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.ParsedIngredient
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.report.WrongReport
import com.robert4lyf.hypershop.data.InStockLine
import com.robert4lyf.hypershop.data.MANUAL_TITLE
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.settings.ListMode
import com.robert4lyf.hypershop.settings.ListView
import com.robert4lyf.hypershop.ui.common.UiMessage
import com.robert4lyf.hypershop.ui.common.webLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TickState { OPEN, PARTIAL, DONE }

data class ListRow(
    val id: Long,
    val name: String,
    /** What to put in the basket. */
    val main: String,
    /** Context or the pack note. */
    val sub: String?,
    val state: TickState,
    val aisle: AisleCode,
    /** Its group within the aisle ("Herbs"), shown in the categorised view. */
    val group: String? = null,
    /** The recipe it came from, when exactly one recipe put it on the list. */
    val from: String? = null,
    /** X.8: set aside as not available on this trip; still needed, back on the next one. */
    val unavailable: Boolean = false,
)

/** The item sheet (PLAN §3.5). */
data class ItemSheet(
    val id: Long,
    val name: String,
    /** One line per dimension: "2", "300 g". */
    val totals: List<String>,
    /** The recipes (or "Added by hand") that contributed, with how much each. */
    val sources: List<ItemSource>,
    val packNote: String?,
    val aisle: AisleCode,
    /** Names it can be swapped for (from the ingredient library). */
    val swaps: List<String> = emptyList(),
    /** It is a catalogue ingredient, so "Always use instead" can be set on it. */
    val canAlways: Boolean = false,
    /** Ticked when the sheet opened: the sheet's main button is "Untick" then. */
    val done: Boolean = false,
    /** The row as the list shows it ("1 bulb garlic · 3 cloves"), for "This is wrong". */
    val shown: String = "",
)

/** One "From" row: "Weeknight chilli · 400 g", linked to the recipe's page when it has one. */
data class ItemSource(
    val title: String,
    val amount: String? = null,
    val url: String? = null,
)

/** One ingredient you marked in stock on review, for the recipes on the list. */
data class InStockRow(
    val key: String,
    val name: String,
    /** "2tbsp olive oil", or just "Salt". */
    val main: String,
    /** The recipes that need it. */
    val from: String,
    val aisle: AisleCode,
)

data class ListUi(
    val aisles: List<Pair<AisleCode, List<ListRow>>> = emptyList(),
    /** The "In stock" section, below the aisles. */
    val inStock: List<InStockRow> = emptyList(),
    val loaded: Boolean = false,
    /** Imports waiting for review: the "1 recipe ready to review" card. */
    val readyJobs: List<Long> = emptyList(),
    val view: ListView = ListView.STORE,
    /** The recipe view's cards, in the order the recipes were added; empty in the other views. */
    val recipes: List<RecipeSection> = emptyList(),
    /** Planning or shopping (Settings.listMode). */
    val mode: ListMode = ListMode.PLAN,
) {
    /** Rows grouped within each aisle, with a label per group. */
    val categorised: Boolean get() = view == ListView.TYPE

    val isEmpty: Boolean get() = loaded && aisles.isEmpty()

    /** The shop screen is showing: shopping mode, with something to shop for (an empty list is planned, never shopped). */
    val shopping: Boolean get() = mode == ListMode.SHOP && loaded && aisles.isNotEmpty()

    /** Still to find on this trip: what is set aside as not available is not "left". */
    val left: Int get() = aisles.sumOf { (_, r) -> r.count { it.state != TickState.DONE && !it.unavailable } }

    /** X.8: set aside as not available on this trip. */
    val unavailable: Int get() = aisles.sumOf { (_, r) -> r.count { it.unavailable } }
    val doneCount: Int get() = aisles.sumOf { (_, r) -> r.count { it.state == TickState.DONE } }
}

/** The "This week" card: how far the week's shop is prepared (docs/design/release-b.md). */
data class PlanPrep(
    val loaded: Boolean = false,
    val meals: Int = 0,
    /** Meals planned and not on the list yet. */
    val pending: Int = 0,
    /** Buy again suggestions not on the list. */
    val buyAgain: Int = 0,
    /** Last trip's hand-added items not on the list. */
    val lastShop: Int = 0,
    /** Cupboard ingredients still to buy that may be at home. */
    val toCheck: Int = 0,
    /** Has saved recipes or a finished trip: someone who has shopped here before. */
    val returning: Boolean = false,
) {
    /** "2 meals · 1 not on the list · 5 to buy again · 3 to check at home". */
    fun summary(): String =
        listOfNotNull(
            if (meals == 0) {
                "No meals planned"
            } else if (meals == 1) {
                "1 meal"
            } else {
                "$meals meals"
            },
            pending.takeIf { it > 0 }?.let { "$it not on the list" },
            buyAgain.takeIf { it > 0 }?.let { "$it to buy again" },
            toCheck.takeIf { it > 0 }?.let { "$it to check at home" },
        ).joinToString(" · ")
}

/** One card of the recipe view: a recipe's items, or those several recipes share, or those added by hand. */
data class RecipeSection(
    val key: String,
    val title: String,
    val kind: Kind,
    val rows: List<ListRow>,
    /** A recipe's library entry ([com.robert4lyf.hypershop.data.LibraryRecipe.key]), for its photo. */
    val libraryKey: String? = null,
    /** The recipe's photo on this phone, or null (its mosaic is drawn). */
    val photo: String? = null,
) {
    enum class Kind { RECIPE, SHARED, BY_HAND }
}

/** The shopping list (PLAN §3.5): grouped by aisle in store order, DONE rows sink within their aisle. */
@OptIn(ExperimentalCoroutinesApi::class)
class ListViewModel(
    private val c: AppContainer,
) : ViewModel() {
    /** Re-read on every list change, so a new walking order from Settings shows straight away. */
    private val order = c.repository.observeAisleOrder()
    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val events: Flow<UiMessage> = messages.receiveAsFlow()

    private val _sheet = MutableStateFlow<ItemSheet?>(null)
    val sheet: StateFlow<ItemSheet?> = _sheet

    // The list and units behind the latest [ui], written off the main thread.
    @Volatile private var lastList: ShoppingList = ShoppingList()

    @Volatile private var lastUnits: UnitsSetting? = null

    /** The item sheet being loaded: a newer open, or a close, cancels it. */
    private var openJob: Job? = null

    /** The walking order, the layout and the mode. */
    private val view = combine(order, c.settings.listView, c.settings.listMode) { o, v, m -> Triple(o, v, m) }

    /**
     * The list with its in-stock lines and recipe titles, read together on every
     * list change (an import, a removed recipe, "Need it"): one emission per
     * change, so the aisles and the "In stock" section never show two different
     * lists for a frame.
     */
    private val listWithInStock = c.repository.list.map { Triple(it, c.repository.inStockOnList(it), sources(it)) }

    val ui: StateFlow<ListUi> =
        combine(
            listWithInStock,
            c.settings.units,
            view,
            c.importJobs.ready,
            c.recipePhotos.paths(),
        ) { (list, lines, src), units, (ord, v, m), ready, photos ->
            lastList = list
            lastUnits = units
            build(
                list,
                units,
                ord,
                v,
                src,
            ).let { ui ->
                ui.copy(
                    readyJobs = ready.map { it.id },
                    inStock = inStockRows(lines, units),
                    mode = m,
                    recipes = ui.recipes.map { r -> r.libraryKey?.let { r.copy(photo = photos[it]) } ?: r },
                )
            }
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, ListUi())

    /**
     * The "This week" card above the list: how far this week's shop is prepared.
     * Its own flow, so the list's main one (the one a tick waits on) carries none of it.
     */
    val prep: StateFlow<PlanPrep> =
        c.settings.listMode
            .flatMapLatest { mode ->
                // The shop screen draws no card: a tick there reads nothing for it (QueryBudgetTest).
                if (mode == ListMode.SHOP) flowOf(PlanPrep()) else prepFlow()
            }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanPrep())

    private fun prepFlow(): Flow<PlanPrep> =
        combine(
            // The week is read again with every change to the list, so a card left open over Sunday
            // midnight shows the new week (this view model lives as long as the app).
            c.repository.list.map { c.planner.currentWeek() }.distinctUntilChanged().flatMapLatest { c.planner.observeWeek(it) },
            c.buyAgain.observe(),
            c.homeCheck.observe(),
            c.repository.observeLibrary().map { it.isNotEmpty() },
        ) { meals, buy, home, saved ->
            PlanPrep(
                loaded = true,
                meals = meals.size,
                pending = meals.count { it.shopping.pending },
                buyAgain = buy.regulars.count { !it.onList },
                lastShop = buy.lastShop.size,
                toCheck = home.count { !it.have },
                returning = saved || buy.hasHistory,
            )
        }

    /** "Add the rest of last shop" from the empty list: the last trip's hand-added items, never doubled. */
    fun addLastShop() =
        viewModelScope.launch {
            val items = c.buyAgain.view().lastShop
            val added = c.buyAgain.add(items)
            if (added.isNotEmpty()) {
                messages.send(UiMessage("Added ${added.size} from last shop", undo = { c.buyAgain.undo(added) }))
            }
        }

    /** Which uses put each live item on the list, and every use's title: one query. */
    private suspend fun sources(list: ShoppingList): Sources {
        val byItem = list.liveItems.associate { item -> item.id to list.contributionsOf(item.id).map { it.useId }.distinct().sorted() }
        val ids = byItem.values.flatten().toSet()
        if (ids.isEmpty()) return Sources(byItem, emptyMap(), emptyMap())
        val links = c.repository.useLinks(list, ids)
        return Sources(byItem, links.mapValues { it.value.title }, links.mapNotNull { (id, l) -> l.libraryKey?.let { id to it } }.toMap())
    }

    /** The recipe title for each live item that exactly one use put on the list, when that use is a recipe. */
    private fun fromTitles(
        list: ShoppingList,
        src: Sources,
    ): Map<Long, String> =
        src.uses.mapNotNull { (item, uses) ->
            val use = uses.singleOrNull() ?: return@mapNotNull null
            if (list.uses[use]?.kind != UseKind.RECIPE) return@mapNotNull null
            src.titles[use]?.let { item to it }
        }.toMap()

    /**
     * The recipe view: a card per recipe in the order they were added, then the
     * items more than one recipe needs (each naming them), then those added by
     * hand. Within a card, aisles in walking order, ticked rows last.
     */
    private fun recipeSections(
        list: ShoppingList,
        rows: List<Pair<ListRow, Int>>,
        ord: List<AisleCode>,
        src: Sources,
    ): List<RecipeSection> {
        fun aisleRank(a: AisleCode) = ord.indexOf(a).let { if (it < 0) Int.MAX_VALUE else it }
        val order = compareBy<Pair<ListRow, Int>>({ it.first.state == TickState.DONE }, { aisleRank(it.first.aisle) }, { it.second })
        val recipe = { use: Long -> list.uses[use]?.kind == UseKind.RECIPE }
        val byKey =
            rows.groupBy { (row, _) ->
                val uses = src.uses[row.id].orEmpty()
                when {
                    uses.size > 1 -> SHARED
                    uses.size == 1 && recipe(uses.single()) -> "use-${uses.single()}"
                    else -> BY_HAND
                }
            }
        val recipeKeys = list.uses.keys.filter(recipe).sorted().map { "use-$it" }.filter { it in byKey }
        return (recipeKeys + listOf(SHARED, BY_HAND).filter { it in byKey }).map { key ->
            val sorted = byKey.getValue(key).sortedWith(order).map { it.first }
            when (key) {
                SHARED ->
                    RecipeSection(
                        key,
                        "In more than one recipe",
                        RecipeSection.Kind.SHARED,
                        sorted.map { r -> r.copy(from = src.uses[r.id].orEmpty().mapNotNull { src.titles[it] }.joinToString(", ")) },
                    )
                BY_HAND -> {
                    // Named as the Recipes tab names it ("Household" once renamed).
                    val manual = list.uses.values.firstOrNull { it.kind == UseKind.MANUAL && it.archivedAt == null }
                    RecipeSection(key, manual?.let { src.titles[it.id] } ?: MANUAL_TITLE, RecipeSection.Kind.BY_HAND, sorted)
                }
                // The card already names the recipe.
                else ->
                    RecipeSection(
                        key,
                        src.titles[key.removePrefix("use-").toLong()] ?: "Recipe",
                        RecipeSection.Kind.RECIPE,
                        sorted.map {
                            it.copy(from = null)
                        },
                        libraryKey = src.keys[key.removePrefix("use-").toLong()],
                    )
            }
        }
    }

    private fun inStockRows(
        lines: List<InStockLine>,
        units: UnitsSetting,
    ): List<InStockRow> =
        lines
            .groupBy { it.target.mergeKey }
            .map { (key, ls) ->
                val t = ls.first().target
                val entry = t.catalogId?.let { c.catalog.byId[it] }
                val name = entry?.name ?: t.displayName
                val qty = ls.fold(QtyBase.PRESENCE) { acc, l -> acc + l.qty }
                val presence = entry?.displayPolicy == DisplayPolicy.PRESENCE && units != UnitsSetting.AS_WRITTEN
                val hint = Display.hintOf(ls.map { it.unitHint })
                // In the list's own words ("2 bananas"); a presence item at home needs no amount.
                val (amount, shown) = Display.brief(qty, entry, name, units, t.aisle == AisleCode.PRODUCE, hint)
                val from = ls.map { it.recipeTitle }.distinct().joinToString(", ")
                InStockRow(key, name, listOfNotNull(amount.takeIf { !presence }, shown).joinToString(" "), from, t.aisle)
            }.sortedBy { it.name.lowercase() }

    /** "Need it": the in-stock lines go on the list after all, under their recipes. */
    fun needIt(row: InStockRow) =
        viewModelScope.launch {
            if (c.repository.needAfterAll(row.key) > 0) messages.send(UiMessage("Added ${row.name} to the list"))
        }

    private fun build(
        list: ShoppingList,
        units: UnitsSetting,
        ord: List<AisleCode>,
        view: ListView = ListView.STORE,
        src: Sources = Sources(emptyMap(), emptyMap(), emptyMap()),
    ): ListUi {
        val categorised = view == ListView.TYPE
        val from = fromTitles(list, src)
        val rows =
            list.liveItems.map { item ->
                val entry = item.catalogId?.let { c.catalog.byId[it] }
                val text = Display.row(list, item, entry, units)
                val state =
                    when {
                        list.isDone(item.id) -> TickState.DONE
                        list.isPartial(item.id) -> TickState.PARTIAL
                        else -> TickState.OPEN
                    }
                ListRow(
                    item.id,
                    item.displayName,
                    text.main,
                    text.sub,
                    state,
                    item.aisle,
                    entry?.group,
                    from[item.id],
                    unavailable = list.isUnavailable(item.id),
                ) to item.position
            }
        // Categorised: by group (the catalogue's order), then as usual within it.
        val order =
            if (categorised) {
                compareBy<Pair<ListRow, Int>>(
                    { c.catalog.groupRank(it.first.aisle, it.first.group) },
                    { it.first.state == TickState.DONE },
                    { it.second },
                )
            } else {
                compareBy({ it.first.state == TickState.DONE }, { it.second })
            }
        val grouped =
            rows.groupBy { it.first.aisle }
                .toList()
                .sortedBy { (a, _) -> ord.indexOf(a).let { if (it < 0) Int.MAX_VALUE else it } }
                .map { (a, rs) -> a to rs.sortedWith(order).map { it.first } }
        val recipes = if (view == ListView.RECIPE) recipeSections(list, rows, ord, src) else emptyList()
        return ListUi(grouped, loaded = true, view = view, recipes = recipes)
    }

    /** The menu's choice of layout. */
    fun setView(v: ListView) = viewModelScope.launch { c.settings.setListView(v) }

    /** "Shop" / "Plan": the mode is a setting, so it survives a restart mid-shop. Nothing on the list changes. */
    fun setMode(m: ListMode) = viewModelScope.launch { c.settings.setListMode(m) }

    init {
        // A shop that took everything ends in planning, however the list was emptied (Finish shopping,
        // Clear all, a meal removed, Undo of an add): the next item typed must not land in the shop
        // screen, where there is no field to type into.
        viewModelScope.launch {
            ui.collect { if (it.mode == ListMode.SHOP && it.isEmpty) c.settings.setListMode(ListMode.PLAN) }
        }
    }

    /** The round tick: tick, or untick a DONE row. */
    fun toggle(row: ListRow) = toggle(row.id)

    /** Tick, or untick a DONE item (the item sheet's button too). No snackbar: tapping again is the undo. */
    fun toggle(id: Long) = viewModelScope.launch { c.repository.toggle(id) }

    /** X.8: "Not available" in the shop: set aside for this trip, back on the next, with Undo. */
    fun unavailable(row: ListRow) =
        viewModelScope.launch {
            if (!c.repository.markUnavailable(row.id)) return@launch
            messages.send(
                UiMessage("${row.name.replaceFirstChar { it.uppercase() }} set aside for next time", undo = {
                    c.repository.markAvailable(row.id)
                }),
            )
        }

    /** "Found it after all": back among what is left. */
    fun available(row: ListRow) = viewModelScope.launch { c.repository.markAvailable(row.id) }

    /** "Delete" on the item card: closes it, with Undo on the list. */
    fun delete(
        id: Long,
        name: String,
    ) = viewModelScope.launch {
        openJob?.cancel()
        _sheet.value = null
        val stamp = c.repository.softDelete(id)
        messages.send(UiMessage("Deleted $name", undo = { c.repository.undoDelete(stamp) }))
    }

    fun finishShopping() =
        viewModelScope.launch {
            // Counted inside the write: a tick still on its way to the screen is included.
            val (stamp, n) = c.repository.finish()
            val text = if (n == 1) "Finished: 1 bought item removed" else "Finished: $n bought items removed"
            messages.send(UiMessage(text, undo = { c.repository.undoDelete(stamp) }))
        }

    // ---- the item sheet (5.7) ---------------------------------------------------------------

    /** Opens the sheet for [id]. Opening another, or closing, before it loads wins. */
    fun openItem(id: Long): Job {
        openJob?.cancel()
        return viewModelScope.launch {
            // Read, not the list on screen: a tap right after a tick would otherwise show the sheet as still open.
            val list = c.repository.snapshot()
            val item = list.items[id] ?: return@launch
            val entry = item.catalogId?.let { c.catalog.byId[it] }
            val units = lastUnits ?: c.settings.units.first()
            val produce = item.aisle == AisleCode.PRODUCE
            val hint = Display.hintOf(list.contributionsOf(id).map { it.unitHint })
            val totals =
                list.total(id).parts.filterValues { it > Rational.ZERO }.mapNotNull { (k, v) ->
                    Display.amount(QtyBase(mapOf(k to v)), units, produce, hint, entry?.gramsPerCup, entry?.displayPolicy)
                }
            val byUse = list.contributionsOf(id).groupBy { it.useId }
            val links = c.repository.useLinks(list, byUse.keys)
            val sources =
                byUse.mapNotNull { (u, cs) ->
                    val link = links[u] ?: return@mapNotNull null
                    val useHint = Display.hintOf(cs.map { it.unitHint })
                    val qty = cs.fold(QtyBase.PRESENCE) { acc, x -> acc + x.qty }
                    val amount = Display.amount(qty, units, produce, useHint, entry?.gramsPerCup, entry?.displayPolicy)
                    ItemSource(link.title, amount, webLink(link.url))
                }
            // Main thread from here on: a cancel (newer open, close) can't slip in after this check.
            ensureActive()
            val swaps = entry?.swaps.orEmpty().mapNotNull { c.catalog.byId[it]?.name }
            val pack = entry?.packNote?.let(Display::compactUnits)
            val row = Display.row(list, item, entry, units)
            _sheet.value =
                ItemSheet(
                    id,
                    entry?.name ?: item.displayName,
                    totals,
                    sources,
                    pack,
                    item.aisle,
                    swaps,
                    entry != null,
                    list.isDone(id),
                    listOfNotNull(row.main, row.sub).joinToString(" · "),
                )
        }.also { openJob = it }
    }

    /** W.4: "This is wrong" on the open sheet, saved on the phone; the note's text, for a share. */
    suspend fun wrongNote(
        sheet: ItemSheet,
        expected: String,
    ): String =
        c.wrongReports.save(
            WrongReport(
                "List",
                sheet.name,
                sheet.sources.map { it.title to (it.amount ?: "no amount") },
                sheet.shown,
                expected,
                c.catalog.version,
                java.time.Instant.ofEpochMilli(c.clock()).toString(),
            ),
        )

    /**
     * "Swap for": the item becomes [to] (merging into it if it's on the list).
     * [always] also sets "always use instead" on the ingredient in the library,
     * so later recipes ask for [to] straight away.
     */
    fun swapItem(
        id: Long,
        to: String,
        always: Boolean,
    ) = viewModelScope.launch {
        val item = lastList.items[id] ?: c.repository.snapshot().items[id] ?: return@launch
        val from = item.catalogId?.let { c.catalog.byId[it] }
        val name = c.repository.swapItem(id, to) ?: return@launch
        closeSheetOf(id)
        val target = c.catalog.lookup(to)
        if (always && from != null && target != null) {
            c.library.save(EntryEdit.of(from).copy(replaceWith = target.id))
            c.repository.rekeyIfCatalogChanged()
        }
        val was = from?.name ?: item.displayName
        messages.send(UiMessage(if (always) "Swapped $was for $name, from now on" else "Swapped $was for $name"))
    }

    fun closeItem() {
        openJob?.cancel()
        _sheet.value = null
    }

    /** After a change made from [id]'s sheet: closes it, unless another item's sheet is open by now. */
    private fun closeSheetOf(id: Long) = _sheet.update { if (it?.id == id) null else it }

    fun moveItem(
        id: Long,
        aisle: AisleCode,
        always: Boolean,
    ) = viewModelScope.launch {
        c.repository.moveAisle(id, aisle, always)
        closeSheetOf(id)
    }

    /** Edit quantity: the new total; the difference becomes a manual contribution. False if [text] has no amount. */
    fun editQuantity(
        id: Long,
        text: String,
    ): Boolean {
        val parsed = c.ruleParser.parse(text.trim() + " x")
        val q = parsed.shopBase(UnitLocale.METRIC)
        if (q.isPresence) return false
        viewModelScope.launch {
            c.repository.editQuantity(id, q)
            closeSheetOf(id)
        }
        return true
    }

    // ---- the "Add item" field (5.6b) ----------------------------------------------------

    /** The catalogue's names, mapped once per catalogue state (a library edit swaps the entries list). */
    @Volatile private var catalogueNames: Pair<List<CatalogEntry>, List<String>>? = null

    private fun catalogueNames(): List<String> {
        val entries = c.catalog.entries
        catalogueNames?.let { (e, names) -> if (e === entries) return names }
        return entries.map { it.name }.also { catalogueNames = entries to it }
    }

    /** Autocomplete on the name typed (a leading amount is skipped): recent items first, then catalogue names; at most 6. */
    fun suggestions(query: String): List<String> {
        val q = nameOf(query).lowercase()
        if (q.length < 2) return emptyList()
        val recent = lastList.items.values.sortedByDescending { it.id }.map { it.displayName }
        return (recent + catalogueNames())
            .distinct()
            .filter { it.lowercase().startsWith(q) || it.lowercase().contains(" $q") }
            .take(6)
    }

    /** The name part of a typed line: "2 oni" is a search for "oni". */
    private fun nameOf(typed: String): String {
        val t = typed.trim()
        val p = c.ruleParser.parse(t)
        return if (p.quantity != null && p.name.isNotBlank()) p.name.trim() else t
    }

    /** The amount typed before the name, as typed ("200 g", "1 1/2"), or null when there was none. */
    private fun amountOf(p: ParsedIngredient): String? =
        if (p.quantity == null) null else listOfNotNull(p.quantityText ?: p.quantity.toString(), p.unit?.serial).joinToString(" ")

    /** A suggestion tapped: the amount typed in front of it stays, so "2 oni" + "onion" adds "2 onions". */
    fun addSuggested(
        typed: String,
        suggestion: String,
    ) = addTyped(listOfNotNull(amountOf(c.ruleParser.parse(typed.trim())), suggestion).joinToString(" "))

    /**
     * Enter in the field. Typed items are rule-parsed (no Claude) and skip
     * review. Several lines (a paste) add one item per line. A single item
     * already on the list is not doubled: a message says so, with "Add 1 more"
     * (or the typed amount) one tap away, and typing carries on.
     */
    fun addTyped(text: String) =
        viewModelScope.launch {
            val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size == 1) {
                val p = typed(lines.single())
                val existing = lastList.itemByKey(c.repository.target(p.name).mergeKey)
                if (existing != null) {
                    val amount = amountOf(p)
                    val line = lines.single()
                    messages.send(
                        UiMessage(
                            "${existing.displayName.replaceFirstChar { it.uppercase() }} is already on the list",
                            undo = { addMore(line) },
                            action = if (amount == null) "Add 1 more" else "Add $amount",
                        ),
                    )
                    return@launch
                }
            }
            for (l in lines) addLine(l)
            if (lines.size > 1) messages.send(UiMessage("Added ${lines.size} items"))
        }

    /**
     * A typed line read as a recipe's would be on its way to the list: "lime juice" is limes (V.2), whoever
     * typed it, unless it says it comes in a bottle.
     */
    private fun typed(line: String) = c.repository.merger.juiceAsFruit(c.ruleParser.parse(line), UnitLocale.METRIC)

    private suspend fun addLine(line: String) {
        val p = typed(line)
        if (p.name.isBlank()) return
        c.repository.manualAdd(p.name, p.shopBase(UnitLocale.METRIC), p.shopUnit)
    }

    /** "Add 1 more" tapped: the typed amount (or one more) goes on as a manual contribution. */
    private suspend fun addMore(line: String) {
        val parsed = typed(line)
        // As a typed add would: grams over spoons, the top of a range; one more when no amount was typed.
        val amount = parsed.shopBase(UnitLocale.METRIC)
        val q = if (parsed.quantity == null || amount.isPresence) QtyBase.of(Rational.ONE, null, UnitLocale.METRIC) else amount
        c.repository.manualAdd(parsed.name, q, parsed.shopUnit.takeIf { parsed.quantity != null })
    }

    /** "Try a sample list". */
    fun addSample() =
        viewModelScope.launch {
            for (l in SAMPLE) addLine(l)
        }

    /** "Share list as text": what is left, by aisle. */
    fun shareText(): String {
        val ui = ui.value
        return buildString {
            append("Shopping list\n")
            for ((aisle, rows) in ui.aisles) {
                val open = rows.filter { it.state != TickState.DONE }
                if (open.isEmpty()) continue
                append("\n").append(aisle.displayName).append('\n')
                for (r in open) append("- ").append(r.main.replaceFirstChar { it.uppercase() }).append('\n')
            }
        }.trimEnd()
    }

    fun clearAll() =
        viewModelScope.launch {
            val stamp = c.repository.clearAll()
            messages.send(UiMessage("List cleared", undo = { c.repository.undoDelete(stamp) }))
        }

    companion object {
        val SAMPLE =
            listOf(
                "2 onions",
                "1 bulb garlic",
                "500g beef mince",
                "400g tin chopped tomatoes",
                "1 pint milk",
                "6 eggs",
                "1 loaf bread",
                "250g butter",
            )
    }
}

/** Each live item's uses (by id), and every use's title. */
private class Sources(
    val uses: Map<Long, List<Long>>,
    val titles: Map<Long, String>,
    /** Every recipe use's library key. */
    val keys: Map<Long, String>,
)

private const val SHARED = "shared"
private const val BY_HAND = "by-hand"
