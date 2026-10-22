package com.robert4lyf.hypershop.ui.meals

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robert4lyf.hypershop.core.merge.UseProgress
import com.robert4lyf.hypershop.core.plan.Week
import com.robert4lyf.hypershop.data.BuyAgainView
import com.robert4lyf.hypershop.data.HomeRow
import com.robert4lyf.hypershop.data.MealShopping
import com.robert4lyf.hypershop.data.PlannedMeal
import com.robert4lyf.hypershop.data.Regular
import com.robert4lyf.hypershop.data.WeekPreview
import com.robert4lyf.hypershop.data.bookRef
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.settings.ListMode
import com.robert4lyf.hypershop.ui.common.UiMessage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A planned meal on the screen. */
data class Meal(
    val id: Long,
    val title: String,
    /** The meal's servings, when the recipe says how many it serves. */
    val servings: Int?,
    val shopping: MealShopping,
    /** Its shopping on the list, when it is on it. */
    val progress: UseProgress?,
    /** "Ottolenghi Simple · p. 142" or the site. */
    val source: String?,
    /** Still in "Your recipes". */
    val saved: Boolean = true,
)

/** A saved recipe that could be planned. */
data class Pick(
    val key: String,
    val title: String,
    val lines: Int,
    /** How many times it is planned in the week shown. */
    val planned: Int,
    val source: String?,
)

data class MealsUi(
    val loaded: Boolean = false,
    val week: Week = Week(0),
    /** "This week", "Next week", "Week of 13 Oct". */
    val weekName: String = "",
    /** "20–26 Oct". */
    val range: String = "",
    /** The week shown is this one: Buy again and the At home check are for the shop about to happen. */
    val isCurrent: Boolean = true,
    /** Before this week: kept to look back at and repeat, not to shop for. */
    val isPast: Boolean = false,
    val meals: List<Meal> = emptyList(),
    /** The latest earlier week with meals, offered as "Repeat" while this week has none. */
    val repeatFrom: Week? = null,
    val repeatName: String = "",
    val repeatTitles: List<String> = emptyList(),
    /** What "Add to the list" would do; null when no meal is waiting to go on. */
    val preview: WeekPreview? = null,
    /** Rows on the list this week's meals still need, each once. */
    val itemsLeft: Int = 0,
    val picks: List<Pick> = emptyList(),
    val hasLibrary: Boolean = false,
    val query: String = "",
    val buyAgain: BuyAgainView = BuyAgainView(),
    val home: List<HomeRow> = emptyList(),
    /** Something on the list to shop for. */
    val listHasItems: Boolean = false,
) {
    val pending: Int get() = meals.count { it.shopping.pending }

    /** Buy again chips not on the list yet: what "Add all" adds. */
    val toAdd: List<Regular> get() = buyAgain.regulars.filter { !it.onList }

    /** At home rows still to buy: what "I have all of these" moves. */
    val toCheck: List<HomeRow> get() = home.filter { !it.have }
}

/**
 * "Plan this week's shop" (docs/design/release-b.md): the week's meals, Buy
 * again and the At home check on one screen, in that order, with one primary
 * action at the bottom. Meals are a plan of their own ([com.robert4lyf.hypershop.data.Planner]);
 * "Add to the list" puts the waiting ones on through the review's path. Every
 * change has Undo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MealsViewModel(
    private val c: AppContainer,
) : ViewModel() {
    var query by mutableStateOf("")
        private set

    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val events: Flow<UiMessage> = messages.receiveAsFlow()

    private val current = c.planner.currentWeek()
    private val week = MutableStateFlow(current)

    /** Plan, list and Buy again writes, one at a time and in tap order. */
    private val writing = Mutex()

    /** Servings asked for and not yet announced, by meal: the stepper counts from the last tap. Main thread only. */
    private val asked = MutableStateFlow<Map<Long, Int>>(emptyMap())
    private val burstFrom = HashMap<Long, Int>()
    private val announce = HashMap<Long, Job>()

    private val weekView =
        week.flatMapLatest { w ->
            c.planner.observeWeek(w).map { meals ->
                val pending = meals.any { it.shopping.pending }
                val preview = if (pending && w >= current) c.planner.previewWeek(w) else null
                val repeat = if (meals.isEmpty()) c.planner.previousPlannedWeek(w) else null
                val repeatTitles = repeat?.let { r -> c.planner.week(r).map { it.title } }.orEmpty()
                WeekView(w, meals, preview, repeat, repeatTitles)
            }
        }

    private val extras =
        week.flatMapLatest { w ->
            if (w != current) {
                flowOf(BuyAgainView() to emptyList())
            } else {
                combine(c.buyAgain.observe(), c.homeCheck.observe()) { b, h -> b to h }
            }
        }

    val ui: StateFlow<MealsUi> =
        combine(
            combine(weekView, c.repository.list) { v, l -> v to l },
            c.repository.observeLibrary(),
            snapshotFlow { query },
            asked,
            extras,
        ) { (v, list), lib, q, asked, (buy, home) ->
            val meals = v.meals.map { it.toMeal().let { m -> asked[m.id]?.let { n -> m.copy(servings = n) } ?: m } }
            val counts = v.meals.groupingBy { it.libraryKey }.eachCount()
            val term = q.trim().lowercase()
            val picks =
                lib.map { r -> Pick(r.key, r.title, r.lines, counts[r.key] ?: 0, r.url?.let { siteOf(it) } ?: bookRef(r.book, r.page)) }
                    .filter { term.isEmpty() || it.title.lowercase().contains(term) }
            MealsUi(
                loaded = true,
                week = v.week,
                weekName = v.week.name(current),
                range = v.week.range(),
                isCurrent = v.week == current,
                isPast = v.week < current,
                meals = meals,
                repeatFrom = v.repeat,
                repeatName = v.repeat?.name(current)?.replaceFirstChar { it.lowercase() }.orEmpty(),
                repeatTitles = v.repeatTitles,
                preview = v.preview,
                itemsLeft = list.rowsStillNeeded(v.meals.mapNotNull { it.useId }).size,
                picks = picks,
                hasLibrary = lib.isNotEmpty(),
                query = q,
                buyAgain = buy,
                home = home,
                listHasItems = list.liveItems.isNotEmpty(),
            )
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MealsUi())

    private class WeekView(
        val week: Week,
        val meals: List<PlannedMeal>,
        val preview: WeekPreview?,
        val repeat: Week?,
        val repeatTitles: List<String>,
    )

    private fun PlannedMeal.toMeal() = Meal(id, title, servings, shopping, progress, url?.let { siteOf(it) } ?: bookRef, saved)

    private fun siteOf(url: String): String? =
        com.robert4lyf.hypershop.ui.common.webLink(url)?.let {
            com.robert4lyf.hypershop.ui.common.siteName(it)
        }

    fun search(text: String) {
        query = text
    }

    fun previousWeek() = week.update { it - 1 }

    fun nextWeek() = week.update { it + 1 }

    fun thisWeek() {
        week.value = current
    }

    /** Write in the app's scope, in tap order; the message is the screen's. */
    private fun <T> written(block: suspend () -> T) =
        c.applicationScope.async(start = CoroutineStart.UNDISPATCHED) { writing.withLock { block() } }

    /** A saved recipe joins the week shown. Twice is allowed: a second chilli is a choice. */
    fun plan(pick: Pick) {
        val w = week.value
        val write = written { c.planner.plan(w, pick.key) }
        viewModelScope.launch {
            val id = write.await()
            messages.send(UiMessage("Planned ${pick.title}", undo = { writing.withLock { c.planner.unplanNew(listOf(id)) } }))
        }
    }

    /** "Repeat": the latest earlier week's meals planned again for this one. */
    fun repeat() {
        val from = ui.value.repeatFrom ?: return
        val to = week.value
        val write = written { c.planner.repeatWeek(from, to) }
        viewModelScope.launch {
            val ids = write.await()
            if (ids.isEmpty()) return@launch
            messages.send(
                UiMessage("Planned ${plural(ids.size, "meal")} again", undo = { writing.withLock { c.planner.unplanNew(ids) } }),
            )
        }
    }

    /** "Add to the list": every waiting meal of the week, merged with what is there, in one go. */
    fun addToList() {
        val w = week.value
        val write = written { c.planner.addWeekToList(w) }
        viewModelScope.launch {
            val added = write.await() ?: return@launch
            messages.send(
                UiMessage(
                    "Added ${plural(added.meals.size, "meal")} · ${plural(added.items, "item")}",
                    undo = { writing.withLock { c.planner.undoAddWeek(added) } },
                ),
            )
        }
    }

    /** The stepper: one more or one fewer serving, counted from the last tap. */
    fun stepServings(
        meal: Meal,
        delta: Int,
    ) {
        val from = asked.value[meal.id] ?: meal.servings ?: return
        setServings(meal, from + delta)
    }

    /** The plan (and the list, if the meal is on it) change at once; a burst of taps is one message with one Undo. */
    fun setServings(
        meal: Meal,
        n: Int,
    ) {
        val start = asked.value[meal.id] ?: meal.servings ?: return
        val target = n.coerceIn(1, MAX_SERVINGS)
        if (target == start) return
        burstFrom.putIfAbsent(meal.id, start)
        asked.update { it + (meal.id to target) }
        announce.remove(meal.id)?.cancel()
        val write = written { runCatching { c.planner.setServings(meal.id, target) }.getOrDefault(false) }
        announce[meal.id] =
            viewModelScope.launch {
                if (!write.await()) {
                    settle(meal.id)
                    messages.send(UiMessage("Couldn't change ${meal.title}: it is no longer planned"))
                    return@launch
                }
                delay(SETTLE_MS)
                val was = settle(meal.id) ?: return@launch
                if (was == target) return@launch
                messages.send(
                    UiMessage("${meal.title} serves $target", undo = { writing.withLock { c.planner.setServings(meal.id, was) } }),
                )
            }
    }

    private fun settle(id: Long): Int? {
        asked.update { it - id }
        announce.remove(id)
        return burstFrom.remove(id)
    }

    /** "Remove": off the plan; what it still needed comes off the list too. Undo brings both back. */
    fun remove(meal: Meal) {
        settle(meal.id)
        val write = written { c.planner.unplan(meal.id) }
        viewModelScope.launch {
            val removed = write.await() ?: return@launch
            messages.send(UiMessage("Removed ${meal.title}", undo = { writing.withLock { c.planner.restore(removed) } }))
        }
    }

    /** One Buy again chip, or "Add all", or "Repeat last shop": by hand, never doubling what is there. */
    fun addRegulars(
        rs: List<Regular>,
        what: String,
    ) {
        val write = written { c.buyAgain.add(rs) }
        viewModelScope.launch {
            val added = write.await()
            if (added.isEmpty()) {
                messages.send(UiMessage("Already on the list"))
                return@launch
            }
            messages.send(UiMessage("Added $what", undo = { writing.withLock { c.buyAgain.undo(added) } }))
        }
    }

    fun addRegular(r: Regular) = addRegulars(listOf(r), r.name)

    fun addAllRegulars() = ui.value.toAdd.takeIf { it.isNotEmpty() }?.let { addRegulars(it, plural(it.size, "item")) }

    fun repeatLastShop() = ui.value.buyAgain.lastShop.takeIf { it.isNotEmpty() }?.let { addRegulars(it, plural(it.size, "item")) }

    fun pin(r: Regular) =
        viewModelScope.launch {
            c.buyAgain.pin(r)
            messages.send(UiMessage("${r.name.replaceFirstChar { it.uppercase() }} is always offered"))
        }

    fun hide(r: Regular) =
        viewModelScope.launch {
            c.buyAgain.hide(r)
            messages.send(UiMessage("Won't suggest ${r.name}", undo = { c.buyAgain.reset(r) }))
        }

    /** At home check: "Have" (off the list for this shop) or "Need" (back on it), for one or several. */
    fun have(rows: List<HomeRow>) {
        val write = written { c.homeCheck.have(rows.map { it.mergeKey }) }
        viewModelScope.launch {
            val lines = write.await()
            if (lines.isEmpty()) return@launch
            val what = rows.singleOrNull()?.name ?: plural(rows.size, "item")
            messages.send(UiMessage("$what at home", undo = { writing.withLock { c.homeCheck.needLines(lines) } }))
        }
    }

    fun need(row: HomeRow) {
        val write = written { c.homeCheck.need(listOf(row.mergeKey)) }
        viewModelScope.launch {
            val lines = write.await()
            if (lines.isEmpty()) return@launch
            messages.send(UiMessage("${row.name} back on the list", undo = { writing.withLock { c.homeCheck.haveLines(lines) } }))
        }
    }

    /** "Start shopping": the list in shopping mode. */
    fun startShopping() = c.applicationScope.launch { c.settings.setListMode(ListMode.SHOP) }

    private fun plural(
        n: Int,
        word: String,
    ) = if (n == 1) "1 $word" else "$n ${word}s"

    companion object {
        const val MAX_SERVINGS = 99

        /** How long the stepper waits for another tap before saying what changed. */
        const val SETTLE_MS = 1_500L
    }
}
