package com.robert4lyf.hypershop.ui.list

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robert4lyf.hypershop.catalog.EntryEdit
import com.robert4lyf.hypershop.core.catalog.CatalogEntry
import com.robert4lyf.hypershop.core.merge.Display
import com.robert4lyf.hypershop.core.merge.ShelfOrder
import com.robert4lyf.hypershop.core.merge.ShoppingList
import com.robert4lyf.hypershop.core.merge.UnitsSetting
import com.robert4lyf.hypershop.core.merge.UseKind
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.ParsedIngredient
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.report.WrongReport
import com.robert4lyf.hypershop.data.MANUAL_TITLE
import com.robert4lyf.hypershop.data.UseSummary
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.settings.ListView
import com.robert4lyf.hypershop.ui.common.UiMessage
import com.robert4lyf.hypershop.ui.common.webLink
import com.robert4lyf.hypershop.ui.recipes.UseActions
import com.robert4lyf.hypershop.ui.recipes.UseDetail
import com.robert4lyf.hypershop.watch.WatchLinkState
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

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
    /** Its merge key: what an arranged place ([ShelfOrder]) is kept under. */
    val key: String = "",
) {
    internal fun slot() = ShelfOrder.Slot(aisle, group, key, 0)
}

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
    /** The total as text to edit ("400 g"); blank when it is not one amount. */
    val amount: String = "",
    /** Its category within the aisle ("Herbs"), if it has one. */
    val group: String? = null,
    /** The categories each aisle has, to pick from. */
    val groups: Map<AisleCode, List<String>> = emptyMap(),
    val revision: Long = 0,
    val storeId: Long = 1,
    val storeName: String = "My store",
    val preferredPack: String = "",
    val purchaseSuggestion: String? = null,
    val explanation: String = "",
    val saving: Boolean = false,
    val saveError: String? = null,
    /** Step Q: what is still to buy (requirement less what is bought and at home), shown first on the sheet. */
    val toBuy: List<String> = emptyList(),
    /** Set aside with "Can't find it" on this trip: the sheet offers "Found it" instead. */
    val unavailable: Boolean = false,
)

/** One "From" row: "Weeknight chilli · 400 g", linked to the recipe's page when it has one. */
data class ItemSource(
    val title: String,
    val amount: String? = null,
    val url: String? = null,
)

data class ListUi(
    val aisles: List<Pair<AisleCode, List<ListRow>>> = emptyList(),
    val loaded: Boolean = false,
    /** Imports waiting for review: the "1 recipe ready to review" card. */
    val readyJobs: List<Long> = emptyList(),
    val view: ListView = ListView.STORE,
    /** The recipe view's cards, in the order the recipes were added; empty in the other views. */
    val recipes: List<RecipeSection> = emptyList(),
    /** X.9: putting things in the order they are found, with arrows on every row and group. */
    val arranging: Boolean = false,
    /** "Watch synced" / "Watch updating…" / "Watch not connected", or null with no watch to speak of (UX review 23). */
    val watch: String? = null,
    /** The store whose walking order the list follows, and whether a second store has one (missing 4 of the review). */
    val activeStore: Long = 1,
    val storeName: String = "My store",
    val secondStore: Boolean = false,
) {
    val isEmpty: Boolean get() = loaded && aisles.isEmpty()

    /** Still to find on this trip: what is set aside as not available is not "left". */
    val left: Int get() = aisles.sumOf { (_, r) -> r.count { it.state != TickState.DONE && !it.unavailable } }

    /** X.8: set aside as not available on this trip. */
    val unavailable: Int get() = aisles.sumOf { (_, r) -> r.count { it.unavailable } }
    val doneCount: Int get() = aisles.sumOf { (_, r) -> r.count { it.state == TickState.DONE } }
}

/** What the list offers around its items: Buy again and last shop. */
data class PlanPrep(
    val loaded: Boolean = false,
    /** Buy again suggestions not on the list. */
    val buyAgain: Int = 0,
    /** Last trip's hand-added items not on the list. */
    val lastShop: Int = 0,
    /** Has saved recipes or a finished trip: someone who has shopped here before. */
    val returning: Boolean = false,
)

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
    /** How many the recipe is on the list for (a recipe's card only). */
    val servings: Int? = null,
) {
    enum class Kind { RECIPE, SHARED, BY_HAND }
}

/** The shopping list (PLAN §3.5): grouped by aisle in store order, DONE rows sink within their aisle. */
@OptIn(ExperimentalCoroutinesApi::class)
class ListViewModel(
    private val c: AppContainer,
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    /** Re-read on every list change, so a new walking order from Settings (or an arrangement) shows straight away. */
    private val order = c.settings.activeStore.flatMapLatest { c.repository.observeWalkingOrder(it) }

    /** Which store is walked, and whether a second one has an order to choose (missing 4 of the review). */
    private val stores =
        combine(c.settings.activeStore, c.repository.observeStores()) { s, profiles -> s to profiles }
            .distinctUntilChanged()

    /** Step Z: "Change store" in the list's menu, with two or more stores. */
    fun changeStore() = viewModelScope.launch { c.shopStart.change() }

    private val arranging = MutableStateFlow(false)
    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val events: Flow<UiMessage> = messages.receiveAsFlow()

    /** Job ids to open on the review screen: "Edit" on a recipe's card (cut 1 of the review). */
    private val reviewsOut = Channel<Long>(Channel.BUFFERED)
    val reviews: Flow<Long> = reviewsOut.receiveAsFlow()

    /** The recipe's own actions on its card in the recipe view, shared with the Recipes tab's model. */
    private val useActions by lazy { UseActions(c, viewModelScope, messages, reviewsOut) }

    /** The recipe whose ingredients are open in a sheet, or null. */
    val useDetail: StateFlow<UseDetail?> get() = useActions.detail

    fun closeUse() = useActions.closeDetail()

    /** The use a card of the recipe view stands for: the recipe's, or the hand-added one. */
    private suspend fun useOf(section: RecipeSection): UseSummary? {
        val uses = c.repository.usesOnList(c.repository.snapshot())
        return if (section.kind == RecipeSection.Kind.BY_HAND) {
            uses.firstOrNull { it.recipeId == null }
        } else {
            val id = section.key.removePrefix("use-").toLongOrNull()
            uses.firstOrNull { it.useId == id }
        }
    }

    fun openUse(section: RecipeSection) = viewModelScope.launch { useOf(section)?.let { useActions.open(it) } }

    fun editUse(section: RecipeSection) = viewModelScope.launch { useOf(section)?.let { useActions.edit(it) } }

    fun editUse(use: UseSummary) = useActions.edit(use)

    fun renameUse(
        section: RecipeSection,
        title: String,
    ) = viewModelScope.launch { useOf(section)?.let { useActions.rename(it, title) } }

    fun removeUse(section: RecipeSection) = viewModelScope.launch { useOf(section)?.let { useActions.remove(it) } }

    /**
     * Step AA "− Serves N +" on a recipe's card: the meal and its items on the list follow at once, what was bought
     * stays bought. The number is on screen, so a press says nothing unless it could not be done.
     */
    fun setServings(
        section: RecipeSection,
        servings: Int,
    ) = viewModelScope.launch {
        val use = useOf(section) ?: return@launch
        if (!c.planner.setUseServings(use.useId, servings)) messages.send(UiMessage("${section.title} doesn't say how many it serves"))
    }

    fun removeUse(use: UseSummary) = useActions.remove(use)

    private val committedSaves = mutableSetOf<Long>().apply { savedState.get<Long>("committedSave")?.let(::add) }
    private val _sheet = MutableStateFlow<ItemSheet?>(null)
    val sheet: StateFlow<ItemSheet?> = _sheet

    // The list and units behind the latest [ui], written off the main thread.
    @Volatile private var lastList: ShoppingList = ShoppingList()

    @Volatile private var lastUnits: UnitsSetting? = null

    /** The item sheet being loaded: a newer open, or a close, cancels it. */
    private var openJob: Job? = null

    /** The walking order and the layout, and whether the list is being arranged. */
    private val view =
        combine(order, c.settings.listView, arranging) { o, v, a -> View(o.first, o.second, v, a) }

    private data class View(
        val aisles: List<AisleCode>,
        val shelf: ShelfOrder,
        val layout: ListView,
        val arranging: Boolean,
    )

    /** The list with the recipe titles behind its rows, read together on every list change: one emission per change. */
    private val listWithSources = c.repository.list.map { it to sources(it) }

    /** The watch link's state and the listRev it last got, for the shop header's word. */
    private val watchSent = combine(c.watchLink.state, c.watchSync.sent) { st, rev -> st to rev }

    val ui: StateFlow<ListUi> =
        combine(
            listWithSources,
            c.settings.units,
            view,
            combine(c.importJobs.ready, watchSent, stores) { r, w, s -> Triple(r, w, s) },
            c.recipePhotos.paths(),
        ) { (list, src), units, v, (ready, watch, store), photos ->
            lastList = list
            lastUnits = units
            build(
                list,
                units,
                v.aisles,
                v.layout,
                src,
                v.shelf,
            ).let { ui ->
                ui.copy(
                    readyJobs = ready.map { it.id },
                    arranging = v.arranging && v.layout != ListView.RECIPE,
                    recipes = ui.recipes.map { r -> r.libraryKey?.let { r.copy(photo = photos[it]) } ?: r },
                    watch = watchWord(watch.first, watch.second, list.listRev),
                    activeStore = store.first,
                    secondStore = store.second.size > 1,
                    storeName = store.second.firstOrNull { it.id == store.first }?.name ?: "My store",
                )
            }
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, ListUi())

    /**
     * The "This week" card above the list: how far this week's shop is prepared.
     * Its own flow, so the list's main one (the one a tick waits on) carries none of it.
     */
    val prep: StateFlow<PlanPrep> =
        prepFlow()
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanPrep())

    private fun prepFlow(): Flow<PlanPrep> =
        combine(
            c.buyAgain.observe(),
            c.repository.observeLibrary().map { it.isNotEmpty() },
        ) { buy, saved ->
            PlanPrep(
                loaded = true,
                buyAgain = buy.regulars.count { !it.onList },
                lastShop = buy.lastShop.size,
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
                        servings = list.uses[key.removePrefix("use-").toLong()]?.servings,
                    )
            }
        }
    }

    private fun build(
        list: ShoppingList,
        units: UnitsSetting,
        ord: List<AisleCode>,
        view: ListView = ListView.STORE,
        src: Sources = Sources(emptyMap(), emptyMap(), emptyMap()),
        shelf: ShelfOrder = ShelfOrder(),
    ): ListUi {
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
                    key = item.mergeKey,
                ) to item.position
            }
        // X.9: groups keep together in every view (the herbs, the salad), as arranged or in the catalogue's
        // order; within a group what was arranged first, then the rest as added. Bought rows sink in their group.
        val order =
            compareBy<Pair<ListRow, Int>>(
                { shelf.groupSort(it.first.aisle, it.first.group, c.catalog.groupRank(it.first.aisle, it.first.group)) },
                { it.first.state == TickState.DONE },
                { shelf.itemSort(it.first.slot()) },
                { it.second },
            )
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

    /** Whether the watch has this list, in a word for the shop header; null when there is no watch to speak of. */
    private fun watchWord(
        state: WatchLinkState,
        sentRev: Int,
        listRev: Long,
    ): String? =
        when (state) {
            WatchLinkState.NO_SDK, WatchLinkState.NOT_INSTALLED -> null
            WatchLinkState.DISCONNECTED, WatchLinkState.CONNECTED -> "Watch not connected"
            WatchLinkState.APP_READY -> if (sentRev.toLong() == listRev) "Watch synced" else "Watch updating…"
        }

    /** After Finish with rows unbought: how many, while the "keep or drop?" prompt is up; null otherwise. */
    val carryOver = MutableStateFlow<Int?>(null)

    /** "Keep them": the prompt goes, the rows stay for the next trip. */
    fun keepLeftovers() {
        carryOver.value = null
    }

    /** "Remove them": everything left (all unbought after a finish) goes, with the clear's Undo. */
    fun dropLeftovers() {
        carryOver.value = null
        clearAll()
    }

    init {
        savedState.get<Long>("openItem")?.let { id ->
            viewModelScope.launch {
                c.awaitReady()
                openItem(id)
            }
        }
    }

    /** The round tick: tick, or untick a DONE row. */
    fun toggle(row: ListRow) = toggle(row.id)

    /** Tick, or untick a DONE item (the item sheet's button too). No snackbar: tapping again is the undo. */
    fun toggle(id: Long) = viewModelScope.launch { c.repository.toggle(id) }

    /** "Can't find it" on the item's sheet: set aside for this trip, back on the next, with Undo. The sheet closes. */
    fun cantFind(
        id: Long,
        name: String,
    ) = viewModelScope.launch {
        closeSheetOf(id)
        if (!c.repository.markUnavailable(id)) return@launch
        messages.send(
            UiMessage("${name.replaceFirstChar { it.uppercase() }} set aside for next time", undo = {
                c.repository.markAvailable(id)
            }),
        )
    }

    /** X.9: "Arrange items" from the menu, and "Done". */
    fun arrange(on: Boolean) {
        arranging.value = on
    }

    /** Moves [row] one place up ([by] -1) or down (+1) among its group's rows, for this trip and the next. */
    fun moveItem(
        row: ListRow,
        by: Int,
    ) = viewModelScope.launch {
        val rows = ui.value.aisles.firstOrNull { it.first == row.aisle }?.second.orEmpty().filter { it.group == row.group }
        c.repository.arrangeWith(c.settings.activeStore.first()) { it.movedItem(rows.map { r -> r.slot() }, row.slot(), by) }
    }

    /** UX review 17: a row to the top or bottom of its group, as shown. */
    fun moveItemToEnd(
        row: ListRow,
        top: Boolean,
    ) = viewModelScope.launch {
        val rows = ui.value.aisles.firstOrNull { it.first == row.aisle }?.second.orEmpty().filter { it.group == row.group }
        c.repository.arrangeWith(c.settings.activeStore.first()) { it.movedItemToEnd(rows.map { r -> r.slot() }, row.slot(), top) }
    }

    /** Moves the group [group] one place up or down within [aisle], past the group shown next to it. */
    fun moveGroup(
        aisle: AisleCode,
        group: String?,
        by: Int,
    ) = viewModelScope.launch {
        val present = ui.value.aisles.firstOrNull { it.first == aisle }?.second.orEmpty().map { it.group }.distinct()
        val known: List<String?> = c.catalog.groupsIn(aisle) + null
        c.repository.arrangeWith(c.settings.activeStore.first()) {
            it.movedGroup(aisle, present, known, group, by) { g -> c.catalog.groupRank(aisle, g) }
        }
    }

    /** "Found it" on the sheet of an item set aside: back among what is left. */
    fun found(id: Long) =
        viewModelScope.launch {
            closeSheetOf(id)
            c.repository.markAvailable(id)
        }

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
            val left = c.repository.snapshot().liveItems.size
            // Step Q: what Finish did, in plain words: the bought things recorded, the rest still on the list.
            val text = if (left > 0) "Shopping finished: $n bought · $left to buy" else "Shopping finished: $n bought"
            messages.send(UiMessage(text, undo = { c.repository.undoDelete(stamp) }))
            // Missing 3: what was not bought is asked about once, rather than carried over unnoticed.
            if (left > 0 && n > 0) carryOver.value = left
        }

    /** Plain text contains only outstanding shopping, grouped in the selected store's aisle order. */
    suspend fun shoppingText(): String {
        val list = c.repository.snapshot()
        val store = c.settings.activeStore.first()
        val order = c.repository.aisleOrder(store)
        val units = c.settings.units.first()
        val rows = list.liveItems.filter { !list.isDone(it.id) }
        if (rows.isEmpty()) return "HyperShop — nothing to buy"
        return order.mapNotNull { aisle ->
            val items = rows.filter { it.aisle == aisle }.sortedBy { it.position }
            if (items.isEmpty()) {
                null
            } else {
                aisle.displayName + "\n" +
                    items.joinToString("\n") { item ->
                        "• " + Display.row(list, item, item.catalogId?.let { c.catalog.byId[it] }, units).main +
                            if (item.unavailableAt != null) " (not available this trip)" else ""
                    }
            }
        }.joinToString("\n\n")
    }

    // ---- the item sheet (5.7) ---------------------------------------------------------------

    /** Opens the sheet for [id]. Opening another, or closing, before it loads wins. */
    fun openItem(id: Long): Job {
        savedState["openItem"] = id
        openJob?.cancel()
        return viewModelScope.launch {
            // Let a tap that lands with this one (or a close) come first: started inline on the main thread, a load
            // whose reads return at once would otherwise show this row before the one tapped after it.
            yield()
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
            val storeId = c.settings.activeStore.first()
            val packSize = c.repository.packPreference(storeId, item.mergeKey)
            val suggestion = packSize?.let { com.robert4lyf.hypershop.core.merge.PackSuggestion.forNeed(list.toBuy(id), it) }
            val toBuy =
                list.toBuy(id).parts.filterValues { it > Rational.ZERO }.mapNotNull { (k, v) ->
                    Display.amount(QtyBase(mapOf(k to v)), units, produce, hint, entry?.gramsPerCup, entry?.displayPolicy)
                }
            // Cancelling an earlier load only takes effect at a suspension point; past its last one, it would still
            // show its row over the one tapped since, or reopen a sheet closed meanwhile. Only the latest request
            // ("openItem", set as it is made and cleared by closeItem) may show its sheet.
            if (savedState.get<Long>("openItem") != id) return@launch
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
                    ItemEdit.text(list.total(id)),
                    entry?.group,
                    AisleCode.entries.associateWith { c.catalog.groupsIn(it) },
                    revision = item.rev,
                    storeId = storeId,
                    storeName = c.repository.storeProfiles().firstOrNull { it.id == storeId }?.name ?: "My store",
                    preferredPack = packSize?.let(ItemEdit::text).orEmpty(),
                    purchaseSuggestion =
                        suggestion?.let {
                            "Suggested purchase: ${it.packs} × ${ItemEdit.text(it.size)} packs. " +
                                "Requirement unchanged; tick only what you buy."
                        } ?: packSize?.let { "Usual pack saved; no purchase suggestion is possible in a different measure." },
                    explanation =
                        listOfNotNull(
                            "Required ${ItemEdit.text(list.total(id)).ifBlank { "as shown above" }}",
                            // Step Z: only an amount said to be at home before (the food-at-home check went).
                            item.onHand?.let { "At home ${ItemEdit.text(it)}" },
                            "Bought ${ItemEdit.text(list.purchased(id)).ifBlank { "0" }}",
                            "Buy ${ItemEdit.text(list.toBuy(id)).ifBlank { "0 / see requirement" }}",
                        ).joinToString(" · "),
                    toBuy = toBuy,
                    unavailable = list.isUnavailable(id),
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
        savedState["openItem"] = null
        openJob?.cancel()
        _sheet.value = null
    }

    /** After a change made from [id]'s sheet: closes it, unless another item's sheet is open by now. */
    private fun closeSheetOf(id: Long) {
        if (_sheet.value?.id == id) savedState["openItem"] = null
        _sheet.update { if (it?.id == id) null else it }
    }

    /**
     * The sheet's Save: every change at once, then the sheet closes. False, and nothing
     * saved, when the amount has no number in it.
     *
     * The rename goes first, because a name can be another ingredient's, with its own
     * aisle: the aisle on the form (as it was, or as picked) is then laid over that. A category belongs to
     * the ingredient, so it is kept in the library and applies from now on; a name the
     * library does not know becomes one of yours there, in the aisle it is in.
     */
    fun saveItem(
        id: Long,
        ch: ItemChanges,
    ): Boolean {
        if (_sheet.value?.saving == true) return false
        val total = ch.amount?.let { com.robert4lyf.hypershop.core.parse.QuantityInput.parse(it) ?: return false }
        val pack = ch.pack?.takeIf { it.isNotBlank() }?.let { com.robert4lyf.hypershop.core.parse.QuantityInput.parse(it) ?: return false }
        val opened = _sheet.value?.takeIf { it.id == id }
        _sheet.update { it?.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            try {
                c.awaitReady()
                if (id in committedSaves) {
                    c.flushLibraryEdits()
                    committedSaves.remove(id)
                    savedState["committedSave"] = null
                    closeSheetOf(id)
                    messages.send(UiMessage("Saved"))
                    return@launch
                }
                val before = c.repository.snapshot().items[id] ?: error("This item is no longer on the list")
                val target = ch.name?.let { c.repository.target(it) }
                val targetId = if (ch.name != null) target?.catalogId else before.catalogId
                val entry = targetId?.let { c.catalog.byId[it] }
                val pending =
                    if (ch.groupChanged) {
                        val edit =
                            entry?.let { EntryEdit.of(it).copy(group = ch.group) }
                                ?: EntryEdit(
                                    "user_item_$id", ch.name ?: before.displayName, ch.name ?: before.displayName,
                                    aisle = (ch.aisle ?: before.aisle).name, custom = true, group = ch.group,
                                )
                        com.robert4lyf.hypershop.data.PendingLibraryEditEntity(
                            java.util.UUID.randomUUID().toString(),
                            kotlinx.serialization.json.Json.encodeToString(EntryEdit.serializer(), edit),
                        )
                    } else {
                        null
                    }
                c.repository.editItem(
                    id, opened?.revision?.takeIf { it > 0 } ?: before.rev,
                    ch.name, total, ch.aisle, ch.alwaysAisle, pending, ch.packChanged, pack,
                    opened?.storeId ?: c.settings.activeStore.first(),
                )
                committedSaves.add(id)
                savedState["committedSave"] = id
                c.flushLibraryEdits()
                committedSaves.remove(id)
                savedState["committedSave"] = null
                closeSheetOf(id)
                messages.send(UiMessage("Saved"))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val text = e.message ?: "Could not save. Your draft is still here."
                _sheet.update { if (it?.id == id) it.copy(saveError = text) else it }
                messages.send(UiMessage(text))
            } finally {
                _sheet.update { if (it?.id == id) it.copy(saving = false) else it }
            }
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
            // One add at a time, against the list as it is, not as last shown: two quick Enters of one item must
            // not add it twice (review B.17); the second finds the first's row and says so.
            adding.withLock { addTypedNow(text) }
        }

    /** Adds in tap order. */
    private val adding = Mutex()

    private suspend fun addTypedNow(text: String) {
        run {
            val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size == 1) {
                val p = typed(lines.single())
                val existing = c.repository.snapshot().itemByKey(c.repository.target(p.name).mergeKey)
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
                    return@run
                }
            }
            for (l in lines) addLine(l)
            if (lines.size > 1) messages.send(UiMessage("Added ${lines.size} items"))
        }
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
