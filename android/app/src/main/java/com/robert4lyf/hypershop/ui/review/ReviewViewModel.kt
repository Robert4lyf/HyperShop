package com.robert4lyf.hypershop.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robert4lyf.hypershop.core.catalog.DisplayPolicy
import com.robert4lyf.hypershop.core.merge.ApplyResult
import com.robert4lyf.hypershop.core.merge.Display
import com.robert4lyf.hypershop.core.merge.Incoming
import com.robert4lyf.hypershop.core.merge.IncomingLine
import com.robert4lyf.hypershop.core.merge.NewItem
import com.robert4lyf.hypershop.core.merge.Preview
import com.robert4lyf.hypershop.core.merge.PreviewRow
import com.robert4lyf.hypershop.core.merge.RowDecision
import com.robert4lyf.hypershop.core.merge.UnitsSetting
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.core.model.ParsedIngredient
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.data.ListRepository
import com.robert4lyf.hypershop.data.StoredRecipe
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.extract.Method
import com.robert4lyf.hypershop.importing.ImportError
import com.robert4lyf.hypershop.importing.ImportInput
import com.robert4lyf.hypershop.importing.ImportResult
import com.robert4lyf.hypershop.importing.Importer
import com.robert4lyf.hypershop.importing.JobState
import com.robert4lyf.hypershop.importing.LineCorrection
import com.robert4lyf.hypershop.importing.LineIdentity
import com.robert4lyf.hypershop.importing.LineOrigin
import com.robert4lyf.hypershop.importing.PhotoStore
import com.robert4lyf.hypershop.importing.ReviewLines
import com.robert4lyf.hypershop.ui.common.UiMessage
import com.robert4lyf.hypershop.ui.common.webLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    /** Why [check] is set ("\"A or B\": one of two"). */
    val checkReason: String? = null,
    /** The line as the recipe wrote it, shown with the reason when [check] is set. */
    val raw: String? = null,
)

enum class Badge { CLAUDE, CLAUDE_PENDING, RULES }

data class SheetLine(
    val id: Long,
    val raw: String,
    /** How the line was read: "2 tbsp olive oil (or butter)". */
    val parsed: String,
    /** B in "A or B", when the line has one. */
    val alternative: String?,
    /** Why the line is flagged to check, when it is. */
    val checkReason: String? = null,
    /** The amount as read ("2 tbsp"), to start the Amount field from. */
    val amount: String? = null,
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
    /** False when the recipe has one line: it can't be removed. */
    val canRemove: Boolean = true,
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
    /** Editing a recipe already on the list ("Save changes" rather than "Add"). */
    val editing: Boolean = false,
    /** Something was changed here (a row, the servings, the name): Cancel asks first. */
    val changed: Boolean = false,
    /** Why Claude didn't read this recipe, in plain words, when it didn't. */
    val claudeNote: String? = null,
    /** The servings the recipe gives, shown when the stepper has moved away from it. */
    val originalServings: Int? = null,
    /** "Retry with Claude" is running (a [busy] that can be stopped). */
    val retrying: Boolean = false,
    /** "Added by hand" being edited: no name or book to change. */
    val byHand: Boolean = false,
    /** A saved recipe being edited: nothing goes on the list, so no in-stock or servings. */
    val savedOnly: Boolean = false,
    /** A key is saved and Claude hasn't read this recipe: "Ask Claude" is offered. */
    val canAskClaude: Boolean = false,
    /** No web page: a cookbook recipe, which can carry its book and page. */
    val cookbook: Boolean = false,
    val book: String? = null,
    val page: String? = null,
    /** The saved recipe's photo on this phone, or null (its mosaic is drawn). */
    val photo: String? = null,
) {
    private val rows: List<ReviewRow> get() = aisles.flatMap { it.second }
    val addCount: Int get() = rows.count { !it.inStock && it.adds }
    val inStockCount: Int get() = rows.count { it.inStock }
    val checkCount: Int get() = rows.count { it.check && !it.inStock }

    /** "4 to add · 2 at home · 1 to check", without the zero parts ("12 ingredients" for a saved recipe). */
    val summary: String
        get() =
            if (savedOnly) {
                listOfNotNull(
                    if (rows.size == 1) "1 ingredient" else "${rows.size} ingredients",
                    checkCount.takeIf { it > 0 }?.let { "$it to check" },
                ).joinToString(" · ")
            } else {
                summaryForList
            }

    private val summaryForList: String
        get() =
            listOfNotNull(
                // Said here in full contrast, not only by the disabled button at the bottom.
                "Nothing to add".takeIf { addCount == 0 && rows.isNotEmpty() && !editing },
                addCount.takeIf { it > 0 }?.let { "$it to add" },
                inStockCount.takeIf { it > 0 }?.let { "$it at home" },
                checkCount.takeIf { it > 0 }?.let { "$it to check" },
            ).joinToString(" · ")
}

sealed interface ReviewState {
    data class Loading(
        /** "Fetching the page…", "Reading with Claude…" */
        val stage: String,
        /** The page has taken over [ReviewViewModel.SLOW_MS]: offer pasting the ingredients instead. */
        val slow: Boolean = false,
    ) : ReviewState

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
        /** An edit was saved: no Undo, "Saved changes to <title>". */
        val edited: String? = null,
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
    private val _state = MutableStateFlow<ReviewState>(ReviewState.Loading(STAGE_OPENING))
    val state: StateFlow<ReviewState> = _state

    private val _events = Channel<UiMessage>(Channel.BUFFERED)

    /** Snackbars: "Cleared in stock · Undo", "Added honey to Baking". */
    val events: Flow<UiMessage> = _events.receiveAsFlow()

    private var result: ImportResult? = null
    private var stored: StoredRecipe? = null
    private var preview: Preview? = null
    private var decisions: MutableMap<String, RowDecision> = mutableMapOf()
    private var servings: Int? = null
    private var units = UnitsSetting.METRIC
    private val aisleMoves = mutableMapOf<String, AisleCode>()

    /** Set by every change made here; Cancel asks before discarding it. */
    private var changed = false

    /**
     * Lines the user corrected and lines removed, by which recipe line they are
     * ([LineOrigin], never the text alone: a recipe can say "100 g sugar" twice):
     * "Ask Claude" reads the recipe again, and these stand over whatever it says.
     * Kept with the job, so they outlive leaving the review.
     */
    private val corrections = LinkedHashMap<LineOrigin, LineCorrection>()

    /**
     * Applying or retrying with Claude. [recompute] carries it into the UI, so a
     * row change meanwhile can't re-enable "Add", and neither can start twice.
     */
    private var busy = false
    private var retrying = false
    private var retryJob: Job? = null

    /** The import as it failed, for the lines picked or pasted in its place. */
    private var failedFrom: ImportResult? = null

    /** The use an edit replaces ([ImportResult.editsUse]). */
    private val replacing: Long? get() = result?.editsUse

    /** An edit or a library re-add: leaving it changes nothing, so there's nothing to undo or come back to. */
    val isThrowaway: Boolean get() = result?.throwaway == true

    /** See [act]. Declared before init, which uses it. */
    private val ops = Mutex()

    /** The load, so Cancel and "paste instead" need not wait for a slow page behind [ops]. */
    private val loadJob: Job

    init {
        loadJob = act { load() }
    }

    /**
     * Every action on this screen runs under [ops], one at a time, so a row toggle
     * can't interleave with Add, Retry, Cancel or a line edit (each reads and writes
     * the preview, the stored recipe and the decisions across suspensions). Once
     * Added or Cancelled, later actions do nothing.
     */
    private fun act(block: suspend () -> Unit): Job = viewModelScope.launch { ops.withLock { if (!finished()) block() } }

    private fun finished() = _state.value is ReviewState.Added || _state.value == ReviewState.Cancelled

    /**
     * Claude, and saving its answer on the job, run in the app's scope: leaving the
     * screen mid-call (the request is billed whether or not anyone waits) still
     * records the usage and keeps the answer, so reopening doesn't pay again; a
     * review reopened mid-call joins the call ([AppContainer.claudeParse]).
     */
    private suspend fun parseWithClaude(
        r: ImportResult,
        keep: (ImportResult) -> Boolean = { true },
    ): ImportResult =
        c
            .claudeParse(jobId) {
                c.importer
                    .parse(
                        r,
                        allowClaude = true,
                        model = c.settings.model.first(),
                    ).also { if (keep(it)) c.importJobs.finish(jobId, withReviewOf(it, c.importJobs.get(jobId)?.result)) }
            }.await()

    /**
     * The answer as it is kept on the job: with the review's state *as the job holds it when the answer
     * arrives*, not when the call started — a Stop, then edits, then Back mid-call must not lose those
     * edits — and the lines they were made on, to lay the corrections over the answer on opening.
     */
    private fun withReviewOf(
        answer: ImportResult,
        now: ImportResult?,
    ): ImportResult {
        if (now == null || answer.parsedBy != ParsedBy.CLAUDE) return answer
        return answer.copy(
            title = now.title ?: answer.title,
            book = now.book,
            page = now.page,
            targetServings = now.targetServings,
            inStockKeys = now.inStockKeys,
            decisionsSaved = now.decisionsSaved,
            aisleMoves = now.aisleMoves,
            corrections = now.corrections,
            claudeBefore = ReviewLines(now.lines, LineIdentity.originsOf(now)).takeIf { now.corrections.isNotEmpty() },
        )
    }

    private suspend fun load() {
        units = c.settings.units.first()
        c.importJobs.ensureScheduled(jobId)
        val waiting = c.importJobs.get(jobId)?.state.let { it == JobState.QUEUED || it == JobState.RUNNING }
        if (waiting) _state.value = ReviewState.Loading(STAGE_FETCHING)
        // Woken by the row changing, not by polling it ten times a second while the page is
        // fetched. A parallel timer offers pasting the lines once the fetch has taken a while;
        // the wait itself goes on.
        val slowTimer =
            viewModelScope.launch {
                delay(SLOW_MS)
                (_state.value as? ReviewState.Loading)?.let { _state.value = it.copy(slow = true) }
            }
        val job =
            try {
                c.importJobs.awaitFinished(jobId)
            } finally {
                slowTimer.cancel()
            }
        var r = job?.result
        if (r == null) {
            // No result: a fetch given up on for pasted lines ([pasteInstead]), opened again before any were.
            failedFrom = manualFrom(job?.input)
            return fail(ImportError.NO_RECIPE, emptyList())
        }
        failedFrom = r
        // Once per import: a failure or refusal in the worker isn't retried on open ("Retry with Claude" is).
        if (r.claudePending && !r.claudeTried && c.reviewGate.visible.value) {
            _state.value = ReviewState.Loading(STAGE_CLAUDE)
            // Kept on the job, so reopening this review neither pays for Claude again nor
            // retries a failure (claudeTried is set on those). Pending means the worker
            // already found a line worth asking about: the gate is not asked twice.
            r = parseWithClaude(r.copy(askClaude = true))
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
    fun addAgain() = act { if (_state.value is ReviewState.AlreadyOnList) result?.let { begin(it) } }

    private suspend fun begin(from: ImportResult) {
        corrections.clear()
        for (c in from.corrections) corrections[c.origin] = c
        // An answer from Claude saved while corrections stood: they are laid over it here.
        val before = from.claudeBefore
        val r =
            if (before != null && before.lines.size == before.origins.size) {
                val rec = LineIdentity.reconcile(from.lines, before.lines, before.origins, corrections.toMap())
                from.copy(lines = rec.lines, origins = rec.origins, claudeBefore = null)
            } else {
                from.copy(origins = LineIdentity.originsOf(from), claudeBefore = null)
            }
        result = r
        servings = r.targetServings ?: r.servings
        if (r.decisionsSaved) aisleMoves.putAll(r.aisleMoves)
        store(r)
        decisions = c.repository.merger.defaultDecisions(preview!!.incoming, c.repository.staples()).toMutableMap()
        if (!r.preTick) decisions.replaceAll { _, d -> d.copy(inStock = false) }
        if (r.editsUse != null) {
            // As it went on the list: a row is in stock when its lines were (and none was added).
            val lines = preview!!.incoming.lines
            val (inStock, added) = lines.indices.partition { it in r.inStockLines }
            val keys = inStock.map { lines[it].target.mergeKey }.toSet() - added.map { lines[it].target.mergeKey }.toSet()
            for (k in keys) decisions[k] = (decisions[k] ?: RowDecision()).copy(inStock = true)
        }
        if (r.decisionsSaved) {
            // Back here after leaving (or Undo after Cancel): the decisions as they were left.
            val saved = r.inStockKeys.toSet()
            for (row in preview!!.rows) {
                val k = row.target.mergeKey
                decisions[k] = (decisions[k] ?: RowDecision()).copy(inStock = k in saved)
            }
        }
        recompute()
    }

    // Not cancellable part-way: a cancel between storing the recipe and taking it as [stored] would leak the row.
    private suspend fun store(r: ImportResult) =
        withContext(NonCancellable) {
            val old = stored
            val s =
                c.repository.storeRecipe(
                    r.url,
                    r.title,
                    r.servings,
                    r.locale,
                    "WEB",
                    r.parsedBy,
                    r.model,
                    r.claudePending,
                    r.lines,
                    r.libraryKey,
                    r.book,
                    r.page,
                )
            preview = c.repository.preview(s, servings, emptyMap(), aisleMoves, r.editsUse)
            stored = s
            old?.let { c.repository.discardRecipe(it.recipe.id) }
        }

    /**
     * The review's edits (lines, name, servings, in-stock rows, aisle moves) go
     * with the job, so Back and coming back, or Undo after Cancel, shows them again.
     */
    private suspend fun persist() {
        val r = result ?: return
        val edited =
            r.copy(
                targetServings = servings,
                inStockKeys = decisions.filterValues { it.inStock }.keys.sorted(),
                decisionsSaved = true,
                aisleMoves = aisleMoves.toMap(),
                corrections = corrections.values.toList(),
            )
        result = edited
        c.importJobs.update(jobId, edited)
    }

    private fun candidates(r: ImportResult): List<String> =
        r.rawLines.ifEmpty {
            r.pageText.orEmpty().lines().map { it.trim() }.filter { it.length in 2..500 }.distinct().take(300)
        }

    /** "Taking a while": stop waiting for the page (and the fetch itself) and paste the ingredients instead. */
    fun pasteInstead() {
        loadJob.cancel()
        act {
            if (_state.value !is ReviewState.Loading) return@act
            // The job is ready for the pasted lines; a fetch that still finishes finds it so and keeps out.
            c.importJobs.cancel(jobId)
            failedFrom = manualFrom(c.importJobs.get(jobId)?.input)
            fail(ImportError.NO_RECIPE, emptyList())
        }
    }

    /** The page's title and address, kept for the lines pasted in its place. */
    private fun manualFrom(input: ImportInput?) =
        ImportResult(input?.url, input?.subject, null, Importer.localeFor(input?.url, emptyList()), Method.MANUAL.name)

    /** "Select lines manually": the picked or pasted lines, parsed as a recipe and reviewed as usual. */
    fun useLines(lines: List<String>) =
        act {
            val picked = lines.flatMap { it.lines() }.map { it.trim() }.filter { it.isNotEmpty() }
            if (picked.isEmpty()) return@act
            val claude = c.reviewGate.visible.value && c.apiKeyStore.get() != null
            _state.value = ReviewState.Loading(if (claude) STAGE_CLAUDE else STAGE_LINES)
            val from = failedFrom
            // In the app's scope, as [parseWithClaude]: Back mid-call must not drop a billed answer's usage.
            val r =
                c
                    .claudeParse(jobId) {
                        val allow = c.reviewGate.visible.value
                        c.importer.fromLines(picked, from?.title, allowClaude = allow, model = c.settings.model.first())
                    }.await()
                    .copy(url = from?.url?.takeIf { it.isNotEmpty() })
            if (r.lines.isEmpty()) return@act fail(ImportError.NO_RECIPE, picked)
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
        if (finished()) return
        val s = stored ?: return
        // Re-targeting every line and building the rows is the heavy part: off the main
        // thread. (Under [ops], so nothing else touches these fields meanwhile.)
        val grouped =
            withContext(Dispatchers.Default) {
                val p = c.repository.preview(s, servings, decisions.toMap(), aisleMoves.toMap(), replacing)
                preview = p
                val order = c.repository.aisleOrder()
                p.rows.map { row(it) }.groupBy { it.aisle }.toList().sortedBy { (a, _) ->
                    order.indexOf(a).let { i -> if (i < 0) Int.MAX_VALUE else i }
                }
            }
        persist()
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
                    editing = r.editsUse != null || r.savesToLibrary,
                    changed = changed,
                    claudeNote = claudeNote(r),
                    originalServings = r.servings,
                    retrying = retrying,
                    cookbook = webLink(r.url) == null && !r.byHand,
                    byHand = r.byHand,
                    savedOnly = r.savesToLibrary,
                    canAskClaude = r.parsedBy != ParsedBy.CLAUDE && !r.byHand && c.apiKeyStore.hasKey.value,
                    book = r.book,
                    page = r.page,
                    photo = c.recipePhotos.pathOf(s.recipe.libraryKey ?: s.recipe.url ?: "r:${s.recipe.id}"),
                ),
            )
    }

    /** Why the badge isn't "Parsed by Claude", when it isn't. */
    private fun claudeNote(r: ImportResult): String? =
        when {
            r.parsedBy == ParsedBy.CLAUDE -> null
            r.claudeNote != null -> Importer.claudeNoteText(r.claudeNote)
            // Claude answered that there was nothing to read; the rule parser's lines stand.
            r.claudeTried && !r.claudePending -> "Claude found no ingredient list here; the lines were read offline"
            r.claudeSkipped -> "Every line was clear, so Claude wasn't needed"
            else -> null
        }

    private fun row(r: PreviewRow): ReviewRow {
        val d = r.decision
        val lines = preview!!.incoming.lines.filter { it.target.mergeKey == r.target.mergeKey }
        // In stock rows show what the recipe needs; others what will be added.
        val inc = preview!!.incoming
        val needed = if (d.inStock) lines.fold(QtyBase.PRESENCE) { acc, l -> acc + lineQty(l, inc) } else r.adding
        val produce = r.target.aisle == AisleCode.PRODUCE
        val hint = Display.hintOf(lines.map { it.parsed.shopUnit })
        val entry = c.catalog.byId[r.target.catalogId ?: ""]
        val check = lines.firstOrNull { it.parsed.check }
        return ReviewRow(
            key = r.target.mergeKey,
            name = entry?.name ?: r.target.displayName,
            quantity =
                if (entry?.displayPolicy == DisplayPolicy.PRESENCE && units != UnitsSetting.AS_WRITTEN) {
                    Display.packs(needed, entry.shoppingUnit)
                } else {
                    Display.amount(needed, units, produce, hint, entry?.gramsPerCup, entry?.displayPolicy)
                },
            inStock = d.inStock,
            check = check != null,
            aisle = r.target.aisle,
            adds = preview!!.contributions.any { it.mergeKey == r.target.mergeKey },
            checkReason = check?.parsed?.checkReason,
            raw = check?.parsed?.raw,
        )
    }

    private fun lineQty(
        l: IncomingLine,
        inc: Incoming,
    ): QtyBase = l.parsed.shopBase(inc.locale).times(inc.scale)

    fun setInStock(
        key: String,
        inStock: Boolean,
    ) = act {
        decisions[key] = (decisions[key] ?: RowDecision()).copy(inStock = inStock)
        changed = true
        recompute()
    }

    /** Overflow menu: mark this aisle in stock (with Undo). */
    fun aisleInStock(aisle: AisleCode) =
        act {
            val before = decisions.toMap()
            val rows = preview?.rows?.filter { it.target.aisle == aisle && !it.decision.inStock }.orEmpty()
            if (rows.isEmpty()) return@act
            for (r in rows) decisions[r.target.mergeKey] = r.decision.copy(inStock = true)
            changed = true
            recompute()
            _events.send(UiMessage("${aisle.displayName} marked as at home", undo = { restoreDecisions(before) }))
        }

    /** Overflow menu: clear in stock (with Undo). */
    fun clearInStock() =
        act {
            val before = decisions.toMap()
            if (before.values.none { it.inStock }) return@act
            decisions.replaceAll { _, d -> d.copy(inStock = false) }
            changed = true
            recompute()
            _events.send(UiMessage("Cleared \"at home\"", undo = { restoreDecisions(before) }))
        }

    /** Undo of [clearInStock] and [aisleInStock]: the decisions as they were. */
    fun restoreDecisions(decisions: Map<String, RowDecision>) =
        act {
            this.decisions = decisions.toMutableMap()
            changed = true
            recompute()
        }

    fun setServings(n: Int) =
        act {
            if (n in 1..99) {
                servings = n
                changed = true
                recompute()
            }
        }

    /** "Retry with Claude" / "Ask Claude": called whatever the hybrid gate says. */
    fun retryWithClaude() {
        // A second tap while the first is still queued behind the lock would ask twice.
        if (retryJob?.isActive == true) return
        retryJob =
            act {
                val r = result ?: return@act
                if (busy) return@act
                // Set under the lock: a Stop while this waited for it then has nothing to undo.
                busy = true
                retrying = true
                (state.value as? ReviewState.Ready)?.let { _state.value = it.copy(ui = it.ui.copy(busy = true, retrying = true)) }
                try {
                    val again = parseWithClaude(r.copy(claudePending = true, askClaude = true)) { it.parsedBy == ParsedBy.CLAUDE }
                    // Laid over the review as it is now, not as it was when the call started: a second Ask
                    // Claude after Stop and an edit joins the first call (AppContainer.claudeParse).
                    val kept = keepCorrections(again, result ?: r, corrections.toMap())
                    store(kept)
                    result = kept
                } finally {
                    busy = false
                    retrying = false
                }
                // Why it didn't work shows under the badge (ReviewUi.claudeNote).
                recompute()
            }
    }

    /** Claude's reading with the user's corrections laid over it, line by line ([LineIdentity.reconcile]). */
    private fun keepCorrections(
        again: ImportResult,
        before: ImportResult,
        fixed: Map<LineOrigin, LineCorrection>,
    ): ImportResult {
        val r = LineIdentity.reconcile(again.lines, before.lines, LineIdentity.originsOf(before), fixed)
        return again.copy(lines = r.lines, origins = r.origins, corrections = fixed.values.toList(), claudeBefore = null)
    }

    /** "Stop" while asking Claude. An answer that still arrives is kept on the job for next time. */
    fun stopRetry() {
        val j = retryJob?.takeIf { it.isActive } ?: return
        j.cancel()
        act {
            busy = false
            retrying = false
            recompute()
        }
    }

    /** "Add N items": one transaction re-checking every rev shown. Runs once at a time. */
    fun confirm() {
        val ready = _state.value as? ReviewState.Ready ?: return
        if (busy) return
        busy = true
        _state.value = ready.copy(ui = ready.ui.copy(busy = true))
        act {
            try {
                if (result?.savesToLibrary == true) {
                    val s = stored ?: return@act
                    c.repository.saveToLibrary(s.recipe.id, jobId)
                    offerPhoto(s.recipe.id)
                    // Kept now: leaving the screen must not discard it.
                    stored = null
                    _state.value = ReviewState.Added(0, 0, result?.title ?: "Recipe")
                    return@act
                }
                val p = preview ?: return@act
                when (
                    val r =
                        c.repository.apply(
                            p,
                            jobId,
                            replacing,
                            manual = result?.byHand == true,
                        )
                ) {
                    is ApplyResult.Applied -> {
                        offerPhoto(stored?.recipe?.id)
                        _state.value = ReviewState.Added(r.added, r.useId, replacing?.let { result?.title ?: "Recipe" })
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
     * The page's own photo goes to the recipe's library entry once it is saved, and is fetched in the
     * app's scope: leaving the screen straight after Add must not stop it. Never replaces a photo chosen.
     */
    private fun offerPhoto(recipeId: Long?) {
        val url = result?.imageUrl ?: return
        val id = recipeId ?: return
        c.applicationScope.launch { c.repository.libraryKeyOf(id)?.let { c.recipePhotos.offer(it, url) } }
    }

    /**
     * Back or swipe away without Add or Cancel: the job stays ready to review, and
     * the recipe stored for this preview goes (it is kept if Add made a use of it).
     */
    override fun onCleared() {
        val s = stored ?: return
        c.applicationScope.launch { c.repository.discardRecipe(s.recipe.id) }
    }

    /**
     * Cancel: the screen closes at once; the job is only done for good once the
     * "Import discarded" snackbar has gone without Undo ([discardForGood]). Not
     * queued behind a slow page or Claude.
     */
    fun cancel() {
        loadJob.cancel()
        retryJob?.cancel()
        act {
            stored?.let { c.repository.discardRecipe(it.recipe.id) }
            // Off the ready card while Undo shows: opening it from there meanwhile would be undone by the timer.
            c.importJobs.discarding(jobId)
            _state.value = ReviewState.Cancelled
        }
    }

    /** Undo wasn't tapped: the job is done and its photo, if any, goes. Nothing happens to a job Undo already put back. */
    suspend fun discardForGood() {
        val job = c.importJobs.get(jobId) ?: return
        if (job.state != JobState.DISCARDING) return
        PhotoStore.delete(job.result?.photo)
        c.importJobs.done(jobId)
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
                row.decision.inStock -> "$name: at home, nothing added"
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
                    l.parsed.checkReason?.takeIf { l.parsed.check },
                    amount =
                        l.parsed.quantity?.let { q ->
                            listOfNotNull(
                                Display.fraction(q) + (l.parsed.quantityMax?.let { "–" + Display.fraction(it) } ?: ""),
                                l.parsed.unit?.serial?.replace('_', ' '),
                            ).joinToString(" ")
                        },
                )
            }
        return RowSheet(
            key,
            name,
            effect,
            entry?.packNote?.let(Display::compactUnits),
            lines,
            row.target.aisle,
            row.decision.inStock,
            entry != null && row.decision.inStock,
            canRemove = p.incoming.lines.size > 1,
        )
    }

    /** Live re-parse of an edited line. */
    fun reparse(text: String): String = summary(c.ruleParser.parse(text))

    /** Under the rename field: "Joins maple syrup in Baking" or "A new item in Other". */
    fun renamePreview(name: String): String? {
        val n = name.trim().lowercase()
        if (n.isEmpty()) return null
        val t = c.repository.target(n)
        val entry = c.catalog.byId[t.catalogId ?: ""]
        return if (entry != null) "Joins ${entry.name} in ${t.aisle.displayName}" else "A new item in ${t.aisle.displayName}"
    }

    private fun summary(p: ParsedIngredient): String =
        listOfNotNull(
            p.quantity?.let { q -> Display.fraction(q) + (p.quantityMax?.let { "–" + Display.fraction(it) } ?: "") },
            p.unit?.serial,
            p.name,
            p.note?.let { "($it)" },
        ).joinToString(" ")

    /** "Always at home": a staple from now on, and marked here. */
    fun alwaysInStock(key: String) =
        act {
            c.repository.setStaple(key, true)
            decisions[key] = (decisions[key] ?: RowDecision()).copy(inStock = true)
            changed = true
            recompute()
        }

    /** "Move aisle": always (a CategoryOverride) or just this time. */
    fun moveAisle(
        key: String,
        aisle: AisleCode,
        always: Boolean,
    ) = act {
        val name = preview?.rows?.firstOrNull { it.target.mergeKey == key }?.let { displayName(it.target) } ?: "It"
        val wasMoved = aisleMoves[key]
        if (always) {
            aisleMoves.remove(key)
            c.repository.setOverride(key, aisle)
        } else {
            aisleMoves[key] = aisle
        }
        changed = true
        recompute()
        // The sheet closes on a move and the row may leave the screen: say where it went.
        val text = "${name.replaceFirstChar { it.uppercase() }} moved to ${aisle.displayName}" + if (always) ", from now on" else ""
        _events.send(
            UiMessage(
                text,
                undo =
                    if (always) {
                        null
                    } else {
                        {
                            act {
                                if (wasMoved == null) aisleMoves.remove(key) else aisleMoves[key] = wasMoved
                                recompute()
                            }
                        }
                    },
            ),
        )
    }

    /** "A or B" swap: the line becomes B, with "or A" in its note. [onKey] gets the row's new key. */
    fun swap(
        lineId: Long,
        onKey: (String) -> Unit = {},
    ) = act {
        replaceLine(lineId) { p ->
            val alt = p.note?.takeIf { it.startsWith("or ") }?.removePrefix("or ")?.trim() ?: return@replaceLine p
            // Decided: the line no longer needs checking.
            p.copy(name = alt.lowercase(), note = "or ${p.name}", unit = p.unit, check = false, checkReason = null)
        }?.let(onKey)
    }

    /**
     * "Amount" on the sheet: the line's quantity and unit from [text] ("2 tbsp", "500 g",
     * "3"), its name and note unchanged. False when [text] holds no amount.
     */
    fun setAmount(
        lineId: Long,
        text: String,
    ): Boolean {
        val probe = c.ruleParser.parse("${text.trim()} item")
        if (probe.quantity == null) return false
        act {
            replaceLine(lineId) { old ->
                old.copy(
                    quantity = probe.quantity,
                    quantityMax = probe.quantityMax,
                    quantityText = text.trim(),
                    unit = probe.unit,
                    altQuantity = null,
                    altUnit = null,
                    // An amount the user typed settles any doubt about the amount (an unknown unit,
                    // a count times a count, a range, a number left in the name); a choice ("A or B") stays.
                    check = old.check && !aboutTheAmount(old.checkReason),
                    checkReason = old.checkReason?.takeIf { !aboutTheAmount(it) },
                )
            }
        }
        return true
    }

    private fun aboutTheAmount(reason: String?): Boolean =
        reason != null && listOf("amount", "unit", "count", "range").any { reason.contains(it) }

    /** Edit the source line; it is parsed again with the rule parser. [onKey] gets the row's new key. */
    fun editLine(
        lineId: Long,
        text: String,
        onKey: (String) -> Unit = {},
    ) = act {
        if (text.isBlank()) return@act
        replaceLine(lineId) { old -> c.ruleParser.parse(text).copy(section = old.section) }?.let(onKey)
    }

    /**
     * "Rename": the row's lines are read as [name] for this recipe only; the
     * ingredient library is unchanged. A name the library knows joins that
     * ingredient ("maple syrup"); any other becomes an item of its own. What was
     * decided for the row (in stock, an aisle move) goes with it. [onKey] gets the
     * row's new key.
     */
    fun rename(
        key: String,
        name: String,
        onKey: (String) -> Unit = {},
    ) = act {
        val n = name.trim().lowercase()
        val ids = preview?.incoming?.lines?.filter { it.target.mergeKey == key }?.map { it.recipeLineId }?.toSet()
        if (n.isEmpty() || ids.isNullOrEmpty()) return@act
        val to = c.repository.target(n).mergeKey
        if (to != key) {
            decisions.remove(key)?.let { decisions.putIfAbsent(to, it) }
            aisleMoves.remove(key)?.let { aisleMoves.putIfAbsent(to, it) }
        }
        // Claude's aisle was for the old name; and a named line needs no more checking.
        replaceLines(ids) { it.copy(name = n, aisleSuggestion = null, check = false, checkReason = null) }?.let(onKey)
    }

    private suspend fun replaceLine(
        lineId: Long,
        f: (ParsedIngredient) -> ParsedIngredient,
    ) = replaceLines(setOf(lineId), f)

    /** Returns the key of the row the first changed line now belongs to (it can change with the name). */
    private suspend fun replaceLines(
        lineIds: Set<Long>,
        f: (ParsedIngredient) -> ParsedIngredient,
    ): String? {
        val s = stored ?: return null
        val r = result ?: return null
        val lines = r.lines.toMutableList()
        val indices = s.lines.indices.filter { s.lines[it].first.id in lineIds && it in lines.indices }
        if (indices.isEmpty()) return null
        val origins = LineIdentity.originsOf(r)
        for (i in indices) {
            lines[i] = f(lines[i])
            // Claude goes on reading the recipe as written; this reading of the line stands over its.
            corrections[origins[i]] = LineCorrection(origins[i], lines[i])
        }
        val edited = r.copy(lines = lines, origins = origins)
        store(edited)
        result = edited
        changed = true
        recompute()
        // The recipe is stored again (new line ids) in the same order, so the line is found by index.
        return preview?.incoming?.lines?.getOrNull(indices.first())?.target?.mergeKey
    }

    /** The recipe's name, as shown on the Recipes tab. */
    fun setTitle(title: String) =
        act {
            val t = title.trim().take(200)
            val r = result ?: return@act
            if (t.isEmpty() || t == r.title) return@act
            val edited = r.copy(title = t)
            store(edited)
            result = edited
            changed = true
            recompute()
        }

    /** The cookbook it came from: book and page, either may be blank. */
    fun setBook(
        book: String,
        page: String,
    ) = act {
        val r = result ?: return@act
        val b = book.trim().take(ListRepository.MAX_BOOK).ifEmpty { null }
        val p = page.trim().take(ListRepository.MAX_PAGE).ifEmpty { null }
        if (b == r.book && p == r.page) return@act
        val edited = r.copy(book = b, page = p)
        store(edited)
        result = edited
        changed = true
        recompute()
    }

    /** "Add an ingredient": a line typed in, rule-parsed, at the end of the recipe. */
    fun addLine(text: String) =
        act {
            val t = text.trim()
            val r = result ?: return@act
            if (t.isEmpty()) return@act
            val origins = LineIdentity.originsOf(r)
            val origin = LineIdentity.next(t, origins + corrections.keys)
            // Its own source line, the next one: it is never a part of a line above it, however it reads.
            val line = c.ruleParser.parse(t).copy(sourceLine = r.rawLines.size)
            // A line the user typed is their reading of it, like an edit: it stands over Claude's, and is kept if
            // Claude's answer leaves it out (an answer to a call made before it was typed does).
            corrections[origin] = LineCorrection(origin, line)
            val edited = r.copy(lines = r.lines + line, rawLines = r.rawLines + t, origins = origins + origin)
            store(edited)
            result = edited
            changed = true
            recompute()
            preview?.incoming?.lines?.lastOrNull()?.target?.let { added ->
                _events.send(UiMessage("Added ${displayName(added).replaceFirstChar { it.uppercase() }} to ${added.aisle.displayName}"))
            }
        }

    private fun displayName(t: NewItem): String = c.catalog.byId[t.catalogId ?: ""]?.name ?: t.displayName

    /** "Remove from recipe": the line goes from this recipe (the last one stays), with Undo. */
    fun removeLine(lineId: Long) =
        act {
            val s = stored ?: return@act
            val r = result ?: return@act
            val index = s.lines.indexOfFirst { it.first.id == lineId }
            if (index !in r.lines.indices || r.lines.size < 2) return@act
            val line = r.lines[index]
            val origins = LineIdentity.originsOf(r)
            val origin = origins[index]
            val was = corrections[origin]
            // The source line stays for Claude to read; the removal stands over its reading of this one only.
            corrections[origin] = LineCorrection(origin, line, removed = true)
            val edited =
                r.copy(
                    lines = r.lines.filterIndexed { i, _ -> i != index },
                    origins = origins.filterIndexed { i, _ -> i != index },
                )
            store(edited)
            result = edited
            changed = true
            recompute()
            val name = c.catalog.byId[c.repository.target(line.name).catalogId ?: ""]?.name ?: line.name
            _events.send(
                UiMessage(
                    "Removed ${name.replaceFirstChar { it.uppercase() }} from this recipe",
                    undo = { restoreLine(index, line, origin, was) },
                ),
            )
        }

    /** Undo of [removeLine]: that line (not another with the same text) back where it was, as it was. */
    private fun restoreLine(
        index: Int,
        line: ParsedIngredient,
        origin: LineOrigin,
        was: LineCorrection?,
    ) = act {
        val r = result ?: return@act
        val origins = LineIdentity.originsOf(r)
        // Already back (a second Undo), or Claude has since returned it: nothing to restore.
        if (origin in origins || corrections[origin]?.removed != true) return@act
        if (was != null) corrections[origin] = was else corrections.remove(origin)
        val at = index.coerceIn(0, r.lines.size)
        val lines = r.lines.toMutableList().also { it.add(at, line) }
        val edited = r.copy(lines = lines, origins = origins.toMutableList().also { it.add(at, origin) })
        store(edited)
        result = edited
        changed = true
        recompute()
    }

    /** The row's parse. */
    fun previewRow(key: String): PreviewRow? = preview?.rows?.firstOrNull { it.target.mergeKey == key }

    internal fun currentPreview(): Preview? = preview

    companion object {
        /**
         * The parser's reason in plain words, with what to do about it. Reasons it
         * doesn't know (Claude's, or a new one) are shown as they are.
         */
        fun plainReason(reason: String): String =
            when {
                reason.startsWith("\"A or B\"") -> "the recipe offers a choice; the swap button takes the other"
                reason.startsWith("\"X and Y\"") -> "this may be two ingredients; edit the line to split them"
                reason == "an unknown unit" -> "a measure the app doesn't know, so the amount may be off; the Amount field sets it"
                reason == "an amount left in the name" -> "a number stayed in the name; check the amount"
                reason == "a count times a count" -> "two counts multiplied (like 2 x 400 g); check the total"
                reason == "a range that goes down" -> "the range reads backwards; check the amount"
                reason == "several items on one line" -> "several items on one line; add each as its own line"
                reason == "no ingredient name" -> "no ingredient name was found; edit the line"
                reason == "an amount it couldn't read" -> "an amount it couldn't read; the Amount field sets it"
                reason == "a name that reads like a sentence" -> "the name reads like a sentence; edit the line"
                else -> reason
            }

        const val STAGE_OPENING = "Opening…"
        const val STAGE_FETCHING = "Fetching the page…"
        const val STAGE_CLAUDE = "Reading with Claude…"
        const val STAGE_LINES = "Reading the lines…"

        /** How long a fetch may take before the screen offers pasting the lines instead. */
        const val SLOW_MS = 25_000L
    }
}
