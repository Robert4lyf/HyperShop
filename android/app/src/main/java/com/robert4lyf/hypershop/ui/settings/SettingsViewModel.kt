package com.robert4lyf.hypershop.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robert4lyf.hypershop.claude.ClaudeModel
import com.robert4lyf.hypershop.claude.KeyCheck
import com.robert4lyf.hypershop.claude.SpendGuard
import com.robert4lyf.hypershop.core.merge.UnitsSetting
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.settings.ThemeMode
import com.robert4lyf.hypershop.ui.common.UiMessage
import com.robert4lyf.hypershop.watch.WatchLinkState
import kotlinx.coroutines.CompletableDeferred
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
    val watchVibrate: Boolean = true,
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
        watch(s.watchVibrate) { copy(watchVibrate = it) }
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
        watch(c.repository.observeStaples()) { keys -> copy(staples = keys.map(::stapleName).sorted()) }
        val startUp =
            viewModelScope.launch {
                val saved = c.apiKeyStore.isSaved()
                if (!hasKeyMoved) _ui.update { it.copy(hasKey = saved, keyMissingAfterRestore = keyWasSet && !saved) }
                val aisles = c.repository.aisleOrder()
                if (!aislesMoved) _ui.update { it.copy(aisles = aisles) }
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
            messages.send(UiMessage("$name is no longer always in stock", undo = { c.repository.setStaple(key, true) }))
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

    /** "Reset order": the aisles as the app ships them. */
    fun resetAisleOrder() = write(AisleCode.entries.toList())

    private fun write(order: List<AisleCode>) {
        if (order == _ui.value.aisles) return
        aislesMoved = true
        _ui.update { it.copy(aisles = order) }
        viewModelScope.launch { aisleWrites.withLock { c.repository.setAisleOrder(order) } }
    }

    // ---- Watch -----------------------------------------------------------------------------

    fun setWatchVibrate(on: Boolean) = viewModelScope.launch { c.settings.setWatchVibrate(on) }

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
