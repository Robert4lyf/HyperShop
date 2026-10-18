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
import com.robert4lyf.hypershop.ui.common.webLink
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
    /** False when everything this row needs is already covered, so nothing is added. */
    val adds: Boolean = true,
)

enum class Badge { CLAUDE, CLAUDE_PENDING, RULES }

data class SheetLine(
    val id: Long,
    val raw: String,
    /** How the line was read: "2 tbsp olive oil (or butter)". */
    val parsed: String,
    /** B in "A or B", when the line has one. */
    val alternative: String?,
)

/** The review row's detail sheet (PLAN §3.1). */
data class RowSheet(
    val key: String,
    val name: String,
    /** "Milk: +200 ml → 700 ml on list" */
    val effect: String,
    val packNote: String?,
    val lines: List<SheetLine>,
    val aisle: AisleCode,
    val inStock: Boolean,
    val isStaple: Boolean,
)

data class ReviewUi(
    val title: String,
    val servings: Int?,
    val aisles: List<Pair<AisleCode, List<ReviewRow>>>,
    val badge: Badge,
    val busy: Boolean = false,
    /** "List changed — review updated" */
    val notice: String? = null,
    /** The recipe's page, to open in the browser. */
    val url: String? = null,
) {
    val addCount: Int get() = aisles.sumOf { (_, rows) -> rows.count { !it.inStock && it.adds } }
}

sealed interface ReviewState {
    data object Loading : ReviewState

    data class Ready(
        val ui: ReviewUi,
    ) : ReviewState

    data class Failed(
        val error: ImportError,
        /** Lines to pick from on "Select lines manually": the recipe's, else the page's text. */
        val candidates: List<String>,
    ) : ReviewState

    data class Added(
        val count: Int,
        val useId: Long,
    ) : ReviewState

    data object Cancelled : ReviewState

    /** "Already on your list — add again?" */
    data class AlreadyOnList(
        val title: String,
    ) : ReviewState
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
    private val aisleMoves = mutableMapOf<String, AisleCode>()

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
        failedFrom = r
        // Once per import: a failure or refusal in the worker isn't retried on open ("Retry with Claude" is).
        if (r.claudePending && !r.claudeTried && c.reviewGate.visible.value) {
            _state.value = ReviewState.Loading
            r = c.importer.parse(r, allowClaude = true, model = c.settings.model.first())
            // Kept on the job, so reopening this review neither pays for Claude again nor
            // retries a failure (claudeTried is set on those).
            c.importJobs.finish(jobId, r)
        }
        if (r.lines.isEmpty()) return fail(r.error ?: ImportError.NO_RECIPE, candidates(r))
        result = r
        val url = r.url
        if (r.preTick && url != null && c.repository.alreadyOnList(url)) {
            _state.value = ReviewState.AlreadyOnList(r.title ?: "This recipe")
            return
        }
        begin(r)
    }

    /** "Add again" on [ReviewState.AlreadyOnList]. */
    fun addAgain() = viewModelScope.launch { result?.let { begin(it) } }

    private suspend fun begin(r: ImportResult) {
        servings = r.targetServings ?: r.servings
        store(r)
        decisions = c.repository.merger.defaultDecisions(preview!!.incoming, c.repository.staples()).toMutableMap()
        if (!r.preTick) decisions.replaceAll { _, d -> d.copy(inStock = false) }
        recompute()
    }

    private suspend fun store(r: ImportResult) {
        stored?.let { c.repository.discardRecipe(it.recipe.id) }
        stored =
            c.repository.storeRecipe(r.url, r.title, r.servings, r.locale, "WEB", r.parsedBy, r.model, r.claudePending, r.lines)
        preview = c.repository.preview(stored!!, servings, emptyMap(), aisleMoves)
    }

    private var failedFrom: ImportResult? = null

    private fun candidates(r: ImportResult): List<String> =
        r.rawLines.ifEmpty {
            r.pageText.orEmpty().lines().map { it.trim() }.filter { it.length in 2..500 }.distinct().take(300)
        }

    /** "Select lines manually": the picked or pasted lines, parsed as a recipe and reviewed as usual. */
    fun useLines(lines: List<String>) =
        viewModelScope.launch {
            val picked = lines.flatMap { it.lines() }.map { it.trim() }.filter { it.isNotEmpty() }
            if (picked.isEmpty()) return@launch
            _state.value = ReviewState.Loading
            val from = failedFrom
            val r =
                c.importer
                    .fromLines(picked, from?.title, allowClaude = c.reviewGate.visible.value, model = c.settings.model.first())
                    .copy(url = from?.url)
            if (r.lines.isEmpty()) return@launch fail(ImportError.NO_RECIPE, picked)
            result = r
            begin(r)
        }

    private fun fail(
        e: ImportError,
        raw: List<String>,
    ) {
        _state.value = ReviewState.Failed(e, raw)
    }

    private suspend fun recompute(notice: String? = null) {
        val s = stored ?: return
        val p = c.repository.preview(s, servings, decisions, aisleMoves)
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
                    url = webLink(r.url),
                    busy = busy,
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
        val hint = lines.mapNotNull { it.parsed.shopUnit }.distinct().singleOrNull()
        val entry = c.catalog.byId[r.target.catalogId ?: ""]
        return ReviewRow(
            key = r.target.mergeKey,
            name = entry?.name ?: r.target.displayName,
            quantity = Display.amount(needed, units, produce, hint, entry?.gramsPerCup, entry?.displayPolicy),
            inStock = d.inStock,
            check = lines.any { it.parsed.check },
            aisle = r.target.aisle,
            adds = preview!!.contributions.any { it.mergeKey == r.target.mergeKey },
        )
    }

    private fun lineQty(
        l: com.robert4lyf.hypershop.core.merge.IncomingLine,
        inc: com.robert4lyf.hypershop.core.merge.Incoming,
    ): QtyBase = l.parsed.shopBase(inc.locale).times(inc.scale)

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
    fun retryWithClaude() {
        val r = result ?: return
        if (busy) return
        busy = true
        (state.value as? ReviewState.Ready)?.let { _state.value = it.copy(ui = it.ui.copy(busy = true)) }
        viewModelScope.launch {
            try {
                val again = c.importer.parse(r.copy(claudePending = true), allowClaude = true, model = c.settings.model.first())
                if (again.parsedBy == ParsedBy.CLAUDE) c.importJobs.finish(jobId, again)
                result = again
                store(again)
            } finally {
                busy = false
            }
            recompute(notice = if (result?.parsedBy == ParsedBy.CLAUDE) null else "Claude is unavailable; kept the offline parse")
        }
    }

    /**
     * Applying or retrying with Claude. [recompute] carries it into the UI, so a
     * row change meanwhile can't re-enable "Add", and neither can start twice.
     */
    private var busy = false

    /** "Add N items": one transaction re-checking every rev shown. Runs once at a time. */
    fun confirm() {
        val ready = _state.value as? ReviewState.Ready ?: return
        if (busy) return
        busy = true
        _state.value = ready.copy(ui = ready.ui.copy(busy = true))
        viewModelScope.launch {
            try {
                val p = preview ?: return@launch
                when (val r = c.repository.apply(p, jobId)) {
                    is ApplyResult.Applied -> {
                        _state.value = ReviewState.Added(r.added, r.useId)
                    }
                    ApplyResult.Stale -> {
                        busy = false
                        recompute(notice = "List changed — review updated")
                    }
                }
            } finally {
                busy = false
            }
        }
    }

    /**
     * Back or swipe away without Add or Cancel: the job stays ready to review, and
     * the recipe stored for this preview goes (it is kept if Add made a use of it).
     */
    override fun onCleared() {
        val s = stored ?: return
        c.applicationScope.launch { c.repository.discardRecipe(s.recipe.id) }
    }

    fun cancel() =
        viewModelScope.launch {
            stored?.let { c.repository.discardRecipe(it.recipe.id) }
            c.importJobs.done(jobId)
            _state.value = ReviewState.Cancelled
        }

    // ---- the row sheet (Step 5.5) ----------------------------------------------------

    fun sheet(key: String): RowSheet? {
        val p = preview ?: return null
        val row = p.rows.firstOrNull { it.target.mergeKey == key } ?: return null
        val entry = c.catalog.byId[row.target.catalogId ?: ""]
        val name = entry?.name ?: row.target.displayName
        val produce = row.target.aisle == AisleCode.PRODUCE
        val before =
            row.existing?.let {
                Display.amount(
                    row.onListAfter.minusClamped(row.adding),
                    units,
                    produce,
                    null,
                    entry?.gramsPerCup,
                    entry?.displayPolicy,
                )
            }
        val adding = Display.amount(row.adding, units, produce, null, entry?.gramsPerCup, entry?.displayPolicy)
        val after = Display.amount(row.onListAfter, units, produce, null, entry?.gramsPerCup, entry?.displayPolicy)
        val effect =
            when {
                row.decision.inStock -> "$name: in stock, nothing added"
                adding == null && row.existing != null -> "$name: already on the list"
                adding == null -> "$name: added to the list"
                row.existing == null || before == null -> "$name: +$adding (new)"
                else -> "$name: +$adding → $after on list"
            }
        val lines =
            p.incoming.lines.filter { it.target.mergeKey == key }.map { l ->
                SheetLine(
                    l.recipeLineId,
                    l.parsed.raw,
                    summary(l.parsed),
                    l.parsed.note?.takeIf { it.startsWith("or ") }?.removePrefix("or ")?.trim(),
                )
            }
        return RowSheet(
            key,
            name,
            effect,
            entry?.packNote,
            lines,
            row.target.aisle,
            row.decision.inStock,
            entry != null && row.decision.inStock,
        )
    }

    /** Live re-parse of an edited line. */
    fun reparse(text: String): String = summary(c.ruleParser.parse(text))

    private fun summary(p: com.robert4lyf.hypershop.core.model.ParsedIngredient): String =
        listOfNotNull(
            p.quantity?.let { q -> Display.fraction(q) + (p.quantityMax?.let { "–" + Display.fraction(it) } ?: "") },
            p.unit?.serial,
            p.name,
            p.note?.let { "($it)" },
        ).joinToString(" ")

    /** "I have some": an amount such as "200 ml" or "2". False when it cannot be read. */
    fun haveSome(
        key: String,
        text: String,
    ): Boolean {
        val parsed = c.ruleParser.parse(text.trim() + " x")
        val inc = preview?.incoming
        val q = QtyBase.of(parsed.quantity, parsed.unit, inc?.locale ?: com.robert4lyf.hypershop.core.model.UnitLocale.METRIC)
        if (q.isPresence) return false
        // A volume can't come off a mass (and so on): refuse rather than pretend it worked.
        if (inc != null) {
            val needed = inc.lines.filter { it.target.mergeKey == key }.fold(QtyBase.PRESENCE) { acc, l -> acc + lineQty(l, inc) }
            if (!needed.isPresence && q.parts.keys.none { it in needed.parts.keys }) return false
        }
        viewModelScope.launch {
            decisions[key] = (decisions[key] ?: RowDecision()).copy(haveSome = q, inStock = false)
            recompute()
        }
        return true
    }

    /** "Always in stock": a staple from now on, and ticked here. */
    fun alwaysInStock(key: String) =
        viewModelScope.launch {
            c.repository.setStaple(key, true)
            decisions[key] = (decisions[key] ?: RowDecision()).copy(inStock = true)
            recompute()
        }

    /** "Move aisle": always (a CategoryOverride) or just this time. */
    fun moveAisle(
        key: String,
        aisle: AisleCode,
        always: Boolean,
    ) = viewModelScope.launch {
        if (always) {
            aisleMoves.remove(key)
            c.repository.setOverride(key, aisle)
        } else {
            aisleMoves[key] = aisle
        }
        recompute()
    }

    /** "A or B" swap: the line becomes B, with "or A" in its note. */
    fun swap(lineId: Long) =
        viewModelScope.launch {
            replaceLine(lineId) { p ->
                val alt = p.note?.takeIf { it.startsWith("or ") }?.removePrefix("or ")?.trim() ?: return@replaceLine p
                p.copy(name = alt.lowercase(), note = "or ${p.name}", unit = p.unit)
            }
        }

    /** Edit the source line; it is parsed again with the rule parser. */
    fun editLine(
        lineId: Long,
        text: String,
    ) = viewModelScope.launch {
        if (text.isBlank()) return@launch
        replaceLine(lineId) { old -> c.ruleParser.parse(text).copy(section = old.section) }
    }

    private suspend fun replaceLine(
        lineId: Long,
        f: (com.robert4lyf.hypershop.core.model.ParsedIngredient) -> com.robert4lyf.hypershop.core.model.ParsedIngredient,
    ) {
        val s = stored ?: return
        val r = result ?: return
        val index = s.lines.indexOfFirst { it.first.id == lineId }.takeIf { it >= 0 } ?: return
        val lines = r.lines.toMutableList()
        lines[index] = f(lines[index])
        result = r.copy(lines = lines)
        store(result!!)
        recompute()
    }

    /** The row's parse. */
    fun previewRow(key: String): PreviewRow? = preview?.rows?.firstOrNull { it.target.mergeKey == key }

    internal fun currentPreview(): Preview? = preview
}
