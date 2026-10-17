package com.robert4lyf.hypershop.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robert4lyf.hypershop.core.merge.ApplyResult
import com.robert4lyf.hypershop.core.merge.Display
import com.robert4lyf.hypershop.core.merge.Preview
import com.robert4lyf.hypershop.core.merge.PreviewRow
import com.robert4lyf.hypershop.core.merge.RowDecision
import com.robert4lyf.hypershop.core.merge.UnitsSetting
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.data.StoredRecipe
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.importing.ImportError
import com.robert4lyf.hypershop.importing.ImportResult
import com.robert4lyf.hypershop.importing.JobState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One review row: name, one quantity, "in stock" (PLAN §3.1 default view). */
data class ReviewRow(
    val key: String,
    val name: String,
    val quantity: String?,
    val inStock: Boolean,
    /** A rule-parsed line that matched an unhandled pattern (R7), highlighted. */
    val check: Boolean,
    val aisle: AisleCode,
)

enum class Badge { CLAUDE, CLAUDE_PENDING, RULES }

data class ReviewUi(
    val title: String,
    val servings: Int?,
    val aisles: List<Pair<AisleCode, List<ReviewRow>>>,
    val badge: Badge,
    val busy: Boolean = false,
    /** "List changed — review updated" */
    val notice: String? = null,
) {
    val addCount: Int get() = aisles.sumOf { (_, rows) -> rows.count { !it.inStock } }
}

sealed interface ReviewState {
    data object Loading : ReviewState

    data class Ready(
        val ui: ReviewUi,
    ) : ReviewState

    data class Failed(
        val error: ImportError,
        val rawLines: List<String>,
    ) : ReviewState

    data class Added(
        val count: Int,
        val useId: Long,
    ) : ReviewState

    data object Cancelled : ReviewState
}

/**
 * Review before adding (PLAN §3.1). Claude runs here, in the foreground, when
 * the background import left the recipe "Claude pending" and a key is set.
 */
class ReviewViewModel(
    private val c: AppContainer,
    private val jobId: Long,
) : ViewModel() {
    private val _state = MutableStateFlow<ReviewState>(ReviewState.Loading)
    val state: StateFlow<ReviewState> = _state

    private var result: ImportResult? = null
    private var stored: StoredRecipe? = null
    private var preview: Preview? = null
    private var decisions: MutableMap<String, RowDecision> = mutableMapOf()
    private var servings: Int? = null
    private var units = UnitsSetting.METRIC

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        units = c.settings.units.first()
        var job = c.importJobs.get(jobId)
        while (job != null && (job.state == JobState.QUEUED || job.state == JobState.RUNNING)) {
            kotlinx.coroutines.delay(100)
            job = c.importJobs.get(jobId)
        }
        var r = job?.result ?: return fail(ImportError.NO_RECIPE, emptyList())
        if (r.claudePending && c.reviewGate.visible.value) {
            _state.value = ReviewState.Loading
            r = c.importer.parse(r, allowClaude = true, model = c.settings.model.first())
        }
        if (r.lines.isEmpty()) return fail(r.error ?: ImportError.NO_RECIPE, r.rawLines)
        result = r
        servings = r.servings
        store(r)
        decisions = c.repository.merger.defaultDecisions(preview!!.incoming, c.repository.staples()).toMutableMap()
        recompute()
    }

    private suspend fun store(r: ImportResult) {
        stored?.let { c.repository.discardRecipe(it.recipe.id) }
        stored =
            c.repository.storeRecipe(r.url, r.title, r.servings, r.locale, "WEB", r.parsedBy, r.model, r.claudePending, r.lines)
        preview = c.repository.preview(stored!!, servings, emptyMap())
    }

    private fun fail(
        e: ImportError,
        raw: List<String>,
    ) {
        _state.value = ReviewState.Failed(e, raw)
    }

    private suspend fun recompute(notice: String? = null) {
        val s = stored ?: return
        val p = c.repository.preview(s, servings, decisions)
        preview = p
        val order = c.repository.aisleOrder()
        val rows = p.rows.map { row(it) }
        val grouped =
            rows.groupBy { it.aisle }.toList().sortedBy { (a, _) -> order.indexOf(a).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        val r = result!!
        _state.value =
            ReviewState.Ready(
                ReviewUi(
                    title = r.title ?: "Recipe",
                    servings = servings,
                    aisles = grouped,
                    badge =
                        when {
                            r.parsedBy == ParsedBy.CLAUDE -> Badge.CLAUDE
                            r.claudePending -> Badge.CLAUDE_PENDING
                            else -> Badge.RULES
                        },
                    notice = notice,
                ),
            )
    }

    private fun row(r: PreviewRow): ReviewRow {
        val d = r.decision
        val lines = preview!!.incoming.lines.filter { it.target.mergeKey == r.target.mergeKey }
        // In stock rows show what the recipe needs; others what will be added.
        val inc = preview!!.incoming
        val needed = if (d.inStock) lines.fold(QtyBase.PRESENCE) { acc, l -> acc + lineQty(l, inc) } else r.adding
        val produce = r.target.aisle == AisleCode.PRODUCE
        val hint = lines.mapNotNull { it.parsed.unit }.distinct().singleOrNull()
        val entry = c.catalog.byId[r.target.catalogId ?: ""]
        return ReviewRow(
            key = r.target.mergeKey,
            name = entry?.name ?: r.target.displayName,
            quantity = Display.amount(needed, units, produce, hint, entry?.gramsPerCup),
            inStock = d.inStock,
            check = lines.any { it.parsed.check },
            aisle = r.target.aisle,
        )
    }

    private fun lineQty(
        l: com.robert4lyf.hypershop.core.merge.IncomingLine,
        inc: com.robert4lyf.hypershop.core.merge.Incoming,
    ): QtyBase = QtyBase.of(l.parsed.quantityMax ?: l.parsed.quantity, l.parsed.unit, inc.locale).times(inc.scale)

    fun setInStock(
        key: String,
        inStock: Boolean,
    ) = viewModelScope.launch {
        decisions[key] = (decisions[key] ?: RowDecision()).copy(inStock = inStock)
        recompute()
    }

    /** Overflow menu: mark this aisle in stock. */
    fun aisleInStock(aisle: AisleCode) =
        viewModelScope.launch {
            preview?.rows?.filter { it.target.aisle == aisle }?.forEach { decisions[it.target.mergeKey] = it.decision.copy(inStock = true) }
            recompute()
        }

    /** Overflow menu: clear in stock. */
    fun clearInStock() =
        viewModelScope.launch {
            decisions.replaceAll { _, d -> d.copy(inStock = false) }
            recompute()
        }

    fun setServings(n: Int) =
        viewModelScope.launch {
            if (n in 1..99) {
                servings = n
                recompute()
            }
        }

    /** "Retry with Claude". */
    fun retryWithClaude() =
        viewModelScope.launch {
            val r = result ?: return@launch
            (state.value as? ReviewState.Ready)?.let { _state.value = it.copy(ui = it.ui.copy(busy = true)) }
            val again = c.importer.parse(r.copy(claudePending = true), allowClaude = true, model = c.settings.model.first())
            result = again
            store(again)
            recompute(notice = if (again.parsedBy == ParsedBy.CLAUDE) null else "Claude is unavailable; kept the offline parse")
        }

    /** "Add N items": one transaction re-checking every rev shown. */
    fun confirm() =
        viewModelScope.launch {
            val p = preview ?: return@launch
            when (val r = c.repository.apply(p)) {
                is ApplyResult.Applied -> {
                    c.importJobs.done(jobId)
                    _state.value = ReviewState.Added(r.added, r.useId)
                }
                ApplyResult.Stale -> recompute(notice = "List changed — review updated")
            }
        }

    fun cancel() =
        viewModelScope.launch {
            stored?.let { c.repository.discardRecipe(it.recipe.id) }
            c.importJobs.done(jobId)
            _state.value = ReviewState.Cancelled
        }

    /** The row's parse, for the row sheet (Step 5.5). */
    fun previewRow(key: String): PreviewRow? = preview?.rows?.firstOrNull { it.target.mergeKey == key }

    internal fun currentPreview(): Preview? = preview
}
