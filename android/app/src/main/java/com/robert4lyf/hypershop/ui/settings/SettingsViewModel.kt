package com.robert4lyf.hypershop.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robert4lyf.hypershop.backup.BackupFile
import com.robert4lyf.hypershop.backup.BackupSummary
import com.robert4lyf.hypershop.backup.Inspection
import com.robert4lyf.hypershop.backup.RestoreOutcome
import com.robert4lyf.hypershop.backup.StagedPhotos
import com.robert4lyf.hypershop.claude.ClaudeModel
import com.robert4lyf.hypershop.claude.KeyCheck
import com.robert4lyf.hypershop.claude.SpendGuard
import com.robert4lyf.hypershop.core.merge.UnitsSetting
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.settings.ThemeMode
import com.robert4lyf.hypershop.ui.common.UiMessage
import com.robert4lyf.hypershop.ui.recipes.PhotoCache
import com.robert4lyf.hypershop.watch.WatchLinkState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/** "Test key": null until tried. [MALFORMED] never reached Anthropic: it can't be a key. */
enum class KeyStatus { TESTING, OK, BAD_KEY, MALFORMED, OFFLINE, ERROR }

data class SettingsUi(
    val units: UnitsSetting = UnitsSetting.METRIC,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val staples: List<String> = emptyList(),
    val aisles: List<AisleCode> = emptyList(),
    /**
     * Step AC: each aisle's groups in walking order (null: things in no group), for the aisles that have groups. The
     * same stored order as the list's Arrange items ([com.robert4lyf.hypershop.data.ListRepository.arrangeWith]).
     */
    val groups: Map<AisleCode, List<String?>> = emptyMap(),
    /** Whose walking order [aisles] is: 1, or 2 for the second store (missing 4 of the review). */
    val backupHealth: String? = null,
    val editingStore: Long = 1,
    val storeNames: Map<Long, String> = emptyMap(),
    val claudeAlways: Boolean = true,
    /** W.4: "This is wrong" notes saved on the phone. */
    val wrongNotes: Int = 0,
    /** The watch link in words (Settings › Watch). */
    val watchStatus: String = "",
    /** The link is down or reports a problem, so "Try again" is offered. */
    val watchCanRetry: Boolean = false,
    val hasKey: Boolean = false,
    /** Saved before, missing now: it didn't survive a restore. */
    val keyMissingAfterRestore: Boolean = false,
    /** One-time: the Keystore couldn't decrypt the key, so it was wiped. */
    val keyLost: Boolean = false,
    val keyStatus: KeyStatus? = null,
    val model: ClaudeModel = ClaudeModel.DEFAULT,
    val monthlyCapUsd: Double = SpendGuard.DEFAULT_MONTHLY_CAP_USD,
    val dailyCalls: Int = SpendGuard.DEFAULT_DAILY_CALLS,
    val spentThisMonthUsd: Double = 0.0,
    val callsToday: Int = 0,
    /** The app's today, so the screen can say when the caps reset. */
    val today: LocalDate = LocalDate.now(),
    val debugLog: Boolean = false,
    /** The in-memory log's last lines (redacted), shown while [debugLog] is on. */
    val logLines: List<String> = emptyList(),
    /** False until the saved settings are read, so the screen never shows defaults and then jumps. */
    val loaded: Boolean = false,
    /** A backup file was read and checked: "Restore this backup?" with what it holds. */
    val restorePrompt: BackupSummary? = null,
    /** A backup or restore is running: the Data buttons wait. */
    val dataBusy: Boolean = false,
)

/** Settings (PLAN §3.5 groups; Claude per §3.7). */
class SettingsViewModel(
    private val c: AppContainer,
) : ViewModel() {
    private val _ui = MutableStateFlow(SettingsUi())
    val ui: StateFlow<SettingsUi> = _ui

    private val messages = Channel<UiMessage>(Channel.BUFFERED)

    /** Snackbars (a removed staple, with Undo). */
    val events: Flow<UiMessage> = messages.receiveAsFlow()

    // Main thread only (viewModelScope), so plain fields.
    private var keyWasSet = false

    /** The key store's hasKey has changed since this screen opened: newer than the start-up read. */
    private var hasKeyMoved = false

    /** The walking order was changed here: the start-up read must not undo it. */
    private var aislesMoved = false

    /** Each key test, save and removal takes a new number; a test result for an older one is dropped. */
    private var keyGen = 0

    private val backups = c.backups

    /** The backup read and checked, waiting for "Restore". */
    private var pendingRestore: Inspection.Ok? = null

    /** Aisle moves are written in tap order. */
    private val aisleWrites = Mutex()

    /** Completed by each watcher's first value: the screen shows once all have arrived. */
    private val firsts = mutableListOf<CompletableDeferred<Unit>>()

    init {
        val s = c.settings
        // Every watcher fills its own field, and the screen is drawn (loaded) only once
        // each has given its first value, so it never shows defaults and then jumps. The
        // two start-up reads below fill only what nothing newer has filled since.
        watch(s.units) { copy(units = it) }
        watch(s.theme) { copy(theme = it) }
        watch(s.model) { copy(model = it) }
        watch(s.monthlyCapUsd) { copy(monthlyCapUsd = it) }
        watch(s.dailyCalls) { copy(dailyCalls = it) }
        watch(s.claudeAlways) { copy(claudeAlways = it) }
        viewModelScope.launch { _ui.update { it.copy(wrongNotes = c.wrongReports.count()) } }
        val link = combine(c.watchLink.state, c.watchLink.problem) { st, why -> watchStatus(st, why) to watchCanRetry(st, why) }
        watch(link) { (text, can) -> copy(watchStatus = text, watchCanRetry = can) }
        watch(s.debugLog) { copy(debugLog = it, logLines = if (it) c.log.snapshot().takeLast(LOG_LINES) else emptyList()) }
        watch(s.keyLostNotice) { copy(keyLost = it) }
        watch(s.keyWasSet) {
            keyWasSet = it
            copy(keyMissingAfterRestore = it && !hasKey)
        }
        // hasKey can lag the store at start-up (isSaved reads it now); any later value is current.
        var firstHasKey = true
        viewModelScope.launch {
            c.apiKeyStore.hasKey.collect { v ->
                if (!firstHasKey) hasKeyMoved = true
                firstHasKey = false
                _ui.update { it.copy(hasKey = v, keyMissingAfterRestore = keyWasSet && !v) }
            }
        }
        watch(backups.health) { value ->
            val parts = value?.split('|')
            val time = parts?.firstOrNull()?.toLongOrNull()
            val text =
                time?.let {
                    "Last manual backup: ${java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()} · " +
                        "${parts.getOrNull(1) ?: "0"} photos included · ${parts.getOrNull(2) ?: "0"} missing"
                }
            copy(backupHealth = text)
        }
        watch(c.repository.observeStores()) { rows -> copy(storeNames = rows.associate { it.id to it.name }) }
        watch(c.repository.observeStaples()) { keys -> copy(staples = keys.map(::stapleName).sorted()) }
        val startUp =
            viewModelScope.launch {
                val saved = c.apiKeyStore.isSaved()
                if (!hasKeyMoved) _ui.update { it.copy(hasKey = saved, keyMissingAfterRestore = keyWasSet && !saved) }
                val aisles = c.repository.aisleOrder(_ui.value.editingStore)
                val groups = groupOrder(_ui.value.editingStore)
                if (!aislesMoved) _ui.update { it.copy(aisles = aisles) }
                _ui.update { it.copy(groups = groups) }
            }
        viewModelScope.launch {
            startUp.join()
            firsts.toList().awaitAll()
            _ui.update { it.copy(loaded = true) }
        }
        // Spend changes only when a Claude call is recorded.
        viewModelScope.launch { c.repository.observeUsage().collect { refreshSpend() } }
    }

    private fun <T> watch(
        flow: Flow<T>,
        apply: SettingsUi.(T) -> SettingsUi,
    ) {
        val first = CompletableDeferred<Unit>().also { firsts += it }
        viewModelScope.launch {
            flow.collect { v ->
                _ui.update { it.apply(v) }
                first.complete(Unit)
            }
        }
    }

    private fun stapleName(key: String): String =
        key.removePrefix("cat:").removePrefix("name:").let { id -> c.catalog.byId[id]?.name ?: id.replace('_', ' ') }

    suspend fun refreshSpend() {
        val history = c.usageHistory()
        val g = SpendGuard()
        val today = c.today()
        _ui.update {
            it.copy(spentThisMonthUsd = g.spentThisMonth(history, today), callsToday = g.callsToday(history, today), today = today)
        }
    }

    // ---- List ------------------------------------------------------------------------------

    fun setTheme(t: ThemeMode) = viewModelScope.launch { c.settings.setTheme(t) }

    fun setUnits(u: UnitsSetting) =
        viewModelScope.launch {
            c.settings.setUnits(u)
            c.repository.unitsChanged()
        }

    /** The × on a staple chip; Undo marks it "always in stock" again. */
    fun removeStaple(name: String) =
        viewModelScope.launch {
            val key = c.repository.staples().firstOrNull { stapleName(it) == name } ?: return@launch
            c.repository.setStaple(key, false)
            messages.send(UiMessage("$name is no longer always at home", undo = { c.repository.setStaple(key, true) }))
        }

    // ---- Aisles ----------------------------------------------------------------------------

    /** Move [aisle] one place up (-1) or down (+1) in the walking order. */
    fun moveAisle(
        aisle: AisleCode,
        delta: Int,
    ) {
        // Applied to the screen at once, so a second quick tap builds on this one.
        val order = _ui.value.aisles.toMutableList()
        val i = order.indexOf(aisle)
        val j = i + delta
        if (i < 0 || j !in order.indices) return
        order[i] = order[j].also { order[j] = order[i] }
        write(order)
    }

    /** "Move to top" / "Move to bottom": [aisle] goes to [index] in the walking order. */
    fun moveAisleTo(
        aisle: AisleCode,
        index: Int,
    ) {
        val order = _ui.value.aisles.toMutableList()
        if (!order.remove(aisle)) return
        order.add(index.coerceIn(0, order.size), aisle)
        write(order)
    }

    /** Step AC: every group each aisle can have (and "everything else"), in [store]'s order. */
    private suspend fun groupOrder(store: Long): Map<AisleCode, List<String?>> {
        val shelf = c.repository.shelfOrder(store)
        return AisleCode.entries.mapNotNull { a ->
            val known = c.catalog.groupsIn(a)
            if (known.isEmpty()) return@mapNotNull null
            a to (known + null).sortedBy { shelf.groupSort(a, it, c.catalog.groupRank(a, it)) }
        }.toMap()
    }

    /**
     * Step AC: a group moved in Walking order, [by] places (-1 up, +1 down, or to an end). Written with the list's own
     * Arrange items move, in one transaction that bumps listRev, so the list, the widget and the watch follow.
     */
    fun moveGroup(
        aisle: AisleCode,
        group: String?,
        by: Int,
    ) = viewModelScope.launch {
        aisleWrites.withLock {
            val store = _ui.value.editingStore
            val shown = _ui.value.groups[aisle] ?: return@withLock
            val known = c.catalog.groupsIn(aisle) + null
            c.repository.arrangeWith(store) { it.movedGroup(aisle, shown, known, group, by) { g -> c.catalog.groupRank(aisle, g) } }
            val groups = groupOrder(store)
            _ui.update { it.copy(groups = groups) }
        }
    }

    /** "Move to top" / "Move to bottom" for a group. */
    fun moveGroupTo(
        aisle: AisleCode,
        group: String?,
        index: Int,
    ) {
        val shown = _ui.value.groups[aisle] ?: return
        val at = shown.indexOf(group)
        if (at < 0) return
        val by = index.coerceIn(0, shown.lastIndex) - at
        if (by != 0) moveGroup(aisle, group, by)
    }

    /** "Reset order": the aisles as the app ships them. */
    fun resetAisleOrder() = write(AisleCode.entries.toList())

    private fun write(order: List<AisleCode>) {
        if (order == _ui.value.aisles) return
        aislesMoved = true
        val store = _ui.value.editingStore
        _ui.update { it.copy(aisles = order) }
        viewModelScope.launch { aisleWrites.withLock { c.repository.setAisleOrder(order, store) } }
    }

    /** Settings › Aisles: which store's walking order the arrows move (missing 4 of the review). */
    fun editStore(id: Long) =
        viewModelScope.launch {
            val order = aisleWrites.withLock { c.repository.aisleOrder(id) }
            val groups = groupOrder(id)
            _ui.update { it.copy(editingStore = id, aisles = order, groups = groups) }
        }

    /** Step AD.2: the pencil on a store's tab renames that store, whichever is being edited. */
    fun renameStore(
        store: Long,
        name: String,
    ) = viewModelScope.launch {
        c.repository.renameStore(store, name)
    }

    /** Settings › Always at home › "Ran out" (missing 5 of the review): on the list once, still always at home. */
    fun ranOut(name: String) =
        viewModelScope.launch {
            val key = c.repository.staples().firstOrNull { stapleName(it) == name } ?: return@launch
            c.repository.manualAdd(stapleName(key), QtyBase.PRESENCE, null)
            messages.send(UiMessage("$name is on the shopping list, and still always at home"))
        }

    // ---- Data: backup and restore ------------------------------------------------------------

    /** "Back up to a file": the file the user picked. */
    fun backupTo(uri: Uri) =
        viewModelScope.launch {
            _ui.update { it.copy(dataBusy = true) }
            val words =
                outliving("Couldn't write the backup file", said = { it }) {
                    backups.writeTo(c.context, uri).fold(
                        onSuccess = { s ->
                            val photos = if (s.photos > 0) ", ${plural(s.photos, "photo")}" else ""
                            val missing =
                                when (s.missingPhotos) {
                                    0 -> ""
                                    1 -> ". 1 photo wasn't on this phone, so it isn't in the backup"
                                    else -> ". ${s.missingPhotos} photos weren't on this phone, so they aren't in the backup"
                                }
                            "Backed up ${plural(s.recipes, "recipe")}$photos and ${plural(s.itemsOnList, "item")}$missing"
                        },
                        onFailure = { "Couldn't write the backup file" },
                    )
                }
            _ui.update { it.copy(dataBusy = false) }
            messages.send(UiMessage(words))
        }

    /**
     * Work that must not stop half-way because Settings was left (review 2026-11-23, D): a restore cancelled between
     * its stages left the backup's database with the old settings, and the next start applied the backup again over
     * whatever had been ticked since. It runs in the app's own scope. If this screen has gone by the time it ends,
     * what it has to say ([said], or [failed] if it threw) is shown by the app instead of being lost.
     */
    private suspend fun <T> outliving(
        failed: String,
        said: (T) -> String?,
        work: suspend () -> T,
    ): T {
        val running = c.applicationScope.async { work() }
        try {
            return running.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (!running.isCancelled) {
                c.applicationScope.launch {
                    val words = runCatching { said(running.await()) }.getOrElse { failed }
                    if (words != null) c.notice.value = words
                }
            }
            throw e
        }
    }

    /** "Restore from a file": read and check it, then ask. Nothing changes until [confirmRestore]. */
    fun inspectRestore(uri: Uri) =
        viewModelScope.launch {
            _ui.update { it.copy(dataBusy = true) }
            val i = backups.inspect(c.context, uri)
            _ui.update { it.copy(dataBusy = false) }
            when (i) {
                is Inspection.Ok -> {
                    // Another file picked while one waited: the one waiting is not going to be restored.
                    if (pendingRestore?.photos != i.photos) backups.discardStaged(pendingRestore?.photos)
                    pendingRestore = i
                    _ui.update { it.copy(restorePrompt = i.summary) }
                }
                Inspection.NotABackup -> messages.send(UiMessage("That file isn't a HyperShop backup"))
                is Inspection.Newer -> messages.send(UiMessage("That backup was made by a newer HyperShop. Update the app first."))
                is Inspection.Broken -> messages.send(UiMessage("That backup file is damaged, so nothing was changed"))
                Inspection.Unreadable -> messages.send(UiMessage("Couldn't read that file"))
            }
        }

    fun cancelRestore() {
        backups.discardStaged(pendingRestore?.photos)
        pendingRestore = null
        _ui.update { it.copy(restorePrompt = null) }
    }

    /** "Restore": everything on the phone is replaced; Undo puts back what was there. */
    fun confirmRestore() =
        viewModelScope.launch {
            val inspected = pendingRestore ?: return@launch
            pendingRestore = null
            _ui.update { it.copy(restorePrompt = null, dataBusy = true) }
            val outcome = restoreSafely(inspected.file, inspected.photos)
            _ui.update { it.copy(dataBusy = false) }
            if (outcome !is RestoreOutcome.Restored) return@launch
            val s = inspected.summary
            // Undo is a whole-database restore, so it is refused once the list has moved on (a tick
            // on the phone or the watch, a finished trip): a purchase is never undone by it.
            val restoredAt = c.repository.epochAndRev()
            messages.send(
                UiMessage(
                    "Restored ${plural(s.recipes, "recipe")} and ${plural(s.itemsOnList, "item")}",
                    undo = {
                        if (c.repository.epochAndRev() == restoredAt) {
                            restoreSafely(outcome.before)
                        } else {
                            messages.send(UiMessage("Can't undo: the list has changed since the restore"))
                        }
                    },
                ),
            )
        }

    /** A restore, with anything but success said in words; "nothing was changed" only when that is true. */
    private suspend fun restoreSafely(
        file: BackupFile,
        photos: StagedPhotos? = null,
    ): RestoreOutcome {
        val unread = "Couldn't read that backup, so nothing was changed"
        val outcome =
            try {
                outliving(unread, said = { restoreWords(it) ?: "Restored from the backup" }) {
                    backups.restore(file, photos).also {
                        // A restore can put a whole file back under the name of a damaged one: nothing decoded before stands.
                        if (it is RestoreOutcome.Restored) PhotoCache.clear()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Thrown before the first write (the journal couldn't be saved): nothing changed.
                messages.send(UiMessage(unread))
                return RestoreOutcome.Failed(rolledBack = true)
            }
        restoreWords(outcome)?.let { messages.send(UiMessage(it)) }
        return outcome
    }

    /** What a restore that did not succeed has to say; null when it did. */
    private fun restoreWords(outcome: RestoreOutcome): String? =
        when (outcome) {
            is RestoreOutcome.Restored -> null
            is RestoreOutcome.Refused -> "That backup file is damaged, so nothing was changed"
            is RestoreOutcome.Failed ->
                if (outcome.rolledBack) {
                    "The restore couldn't be finished, so your data is as it was"
                } else {
                    "The restore stopped part-way. HyperShop will finish it the next time it starts."
                }
        }

    private fun plural(
        n: Int,
        word: String,
    ) = if (n == 1) "1 $word" else "$n ${word}s"

    // ---- Watch -----------------------------------------------------------------------------

    /** Every saved "This is wrong" note, for the share sheet. */
    suspend fun wrongNotesText(): String = c.wrongReports.all()

    fun deleteWrongNotes() =
        viewModelScope.launch {
            c.wrongReports.clear()
            _ui.update { it.copy(wrongNotes = 0) }
            messages.send(UiMessage("Notes deleted"))
        }

    /** Data › Clear shopping history. */
    fun clearHistory() =
        viewModelScope.launch {
            c.buyAgain.clearHistory()
            messages.send(UiMessage("Shopping history cleared"))
        }

    fun setClaudeAlways(on: Boolean) = viewModelScope.launch { c.settings.setClaudeAlways(on) }

    /** "Try again": the link starts over from wherever it stopped. */
    fun retryWatch() = c.watchLink.retry()

    // ---- Claude ----------------------------------------------------------------------------

    /** Saves the key and tests it straight away. */
    fun saveKey(text: String) {
        // Pasted keys can carry invisible characters (zero-width spaces, line breaks).
        val key = cleanKey(text)
        if (key.isEmpty()) return
        val gen = ++keyGen
        viewModelScope.launch {
            if (!KEY_CHARS.matches(key)) {
                keyStatus(gen, KeyStatus.MALFORMED)
                return@launch
            }
            if (!c.apiKeyStore.set(key)) {
                keyStatus(gen, KeyStatus.ERROR)
                return@launch
            }
            c.settings.setKeyWasSet(true)
            c.settings.setKeyLostNotice(false)
            test(gen, key)
        }
    }

    fun testKey() {
        val gen = ++keyGen
        viewModelScope.launch { c.apiKeyStore.get()?.let { test(gen, it) } }
    }

    private suspend fun test(
        gen: Int,
        key: String,
    ) {
        keyStatus(gen, KeyStatus.TESTING)
        val r =
            when (c.claudeClient.testKey(key)) {
                KeyCheck.OK -> KeyStatus.OK
                KeyCheck.BAD_KEY -> KeyStatus.BAD_KEY
                KeyCheck.OFFLINE -> KeyStatus.OFFLINE
                KeyCheck.ERROR -> KeyStatus.ERROR
            }
        keyStatus(gen, r)
    }

    /** Shows [status] unless a newer save, test or removal has started since [gen]. */
    private fun keyStatus(
        gen: Int,
        status: KeyStatus?,
    ) {
        if (gen == keyGen) _ui.update { it.copy(keyStatus = status) }
    }

    /** "Remove key": from disk and from memory. A test still running is ignored when it ends. */
    fun removeKey() {
        val gen = ++keyGen
        _ui.update { it.copy(keyStatus = null) }
        viewModelScope.launch {
            c.apiKeyStore.clear()
            c.settings.setKeyWasSet(false)
            keyStatus(gen, null)
        }
    }

    fun dismissKeyLost() = viewModelScope.launch { c.settings.setKeyLostNotice(false) }

    fun setModel(m: ClaudeModel) = viewModelScope.launch { c.settings.setModel(m) }

    /** False when [text] isn't a dollar amount. */
    fun setMonthlyCap(text: String): Boolean {
        val v = text.trim().removePrefix("$").toDoubleOrNull()?.takeIf { it in 0.0..100.0 } ?: return false
        viewModelScope.launch { c.settings.setMonthlyCap(v) }
        return true
    }

    fun setDailyCalls(text: String): Boolean {
        val v = text.trim().toIntOrNull()?.takeIf { it in 0..1000 } ?: return false
        viewModelScope.launch { c.settings.setDailyCalls(v) }
        return true
    }

    // ---- Advanced --------------------------------------------------------------------------

    fun setDebugLog(on: Boolean) = viewModelScope.launch { c.settings.setDebugLog(on) }

    fun refreshLog() = _ui.update { it.copy(logLines = c.log.snapshot().takeLast(LOG_LINES)) }

    private companion object {
        const val LOG_LINES = 100
    }
}

/** A key as typed or pasted, without whitespace or invisible format characters. */
internal fun cleanKey(text: String): String = text.replace(Regex("[\\s\\p{Cf}]"), "")

/** Printable ASCII only; anything else can't go in an HTTP header. */
private val KEY_CHARS = Regex("[\\x21-\\x7e]+")

/** Whether "Try again" is offered: the link is down, or up but reporting a problem. */
fun watchCanRetry(
    state: WatchLinkState,
    problem: String?,
): Boolean = problem != null || (state != WatchLinkState.CONNECTED && state != WatchLinkState.APP_READY)

/** One line for Settings › Watch. */
fun watchStatus(
    state: WatchLinkState,
    problem: String?,
): String =
    when (state) {
        WatchLinkState.APP_READY -> "Watch connected"
        WatchLinkState.CONNECTED -> "Watch connected"
        WatchLinkState.NOT_INSTALLED -> problem ?: "Install HyperShop on the watch"
        WatchLinkState.DISCONNECTED -> problem ?: "Watch not connected"
        WatchLinkState.NO_SDK -> problem ?: "Garmin Connect isn't available"
    }
