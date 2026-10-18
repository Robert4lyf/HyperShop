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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** "Test key": null until tried. */
enum class KeyStatus { TESTING, OK, BAD_KEY, OFFLINE, ERROR }

data class SettingsUi(
    val units: UnitsSetting = UnitsSetting.METRIC,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val staples: List<String> = emptyList(),
    val aisles: List<AisleCode> = emptyList(),
    val watchVibrate: Boolean = true,
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

    private var keyWasSet = false

    init {
        val s = c.settings
        // Everything the screen shows first, read once before anything is drawn; the
        // watchers below keep it current afterwards.
        viewModelScope.launch {
            val first =
                SettingsUi(
                    units = s.units.first(),
                    theme = s.theme.first(),
                    model = s.model.first(),
                    monthlyCapUsd = s.monthlyCapUsd.first(),
                    dailyCalls = s.dailyCalls.first(),
                    watchVibrate = s.watchVibrate.first(),
                    hasKey = c.apiKeyStore.isSaved(),
                    aisles = c.repository.aisleOrder(),
                )
            _ui.update {
                it.copy(
                    units = first.units,
                    theme = first.theme,
                    model = first.model,
                    monthlyCapUsd = first.monthlyCapUsd,
                    dailyCalls = first.dailyCalls,
                    watchVibrate = first.watchVibrate,
                    hasKey = first.hasKey,
                    aisles = first.aisles,
                    loaded = true,
                )
            }
        }
        watch(s.units) { copy(units = it) }
        watch(s.theme) { copy(theme = it) }
        watch(s.model) { copy(model = it) }
        watch(s.monthlyCapUsd) { copy(monthlyCapUsd = it) }
        watch(s.dailyCalls) { copy(dailyCalls = it) }
        watch(s.watchVibrate) { copy(watchVibrate = it) }
        watch(s.debugLog) { copy(debugLog = it, logLines = if (it) c.log.snapshot().takeLast(LOG_LINES) else emptyList()) }
        watch(s.keyLostNotice) { copy(keyLost = it) }
        watch(s.keyWasSet) {
            keyWasSet = it
            copy(keyMissingAfterRestore = it && !hasKey)
        }
        watch(c.apiKeyStore.hasKey) { copy(hasKey = it, keyMissingAfterRestore = keyWasSet && !it) }
        watch(c.repository.observeStaples()) { keys -> copy(staples = keys.map(::stapleName).sorted()) }
        viewModelScope.launch { _ui.update { it.copy(aisles = c.repository.aisleOrder()) } }
        // Spend changes with each import, and each import moves the list rev.
        viewModelScope.launch { c.repository.observe().collect { refreshSpend() } }
    }

    private fun <T> watch(
        flow: Flow<T>,
        apply: SettingsUi.(T) -> SettingsUi,
    ) = viewModelScope.launch { flow.collect { v -> _ui.update { it.apply(v) } } }

    private fun stapleName(key: String): String =
        key.removePrefix("cat:").removePrefix("name:").let { id -> c.catalog.byId[id]?.name ?: id.replace('_', ' ') }

    suspend fun refreshSpend() {
        val history = c.usageHistory()
        val g = SpendGuard()
        _ui.update { it.copy(spentThisMonthUsd = g.spentThisMonth(history, c.today()), callsToday = g.callsToday(history, c.today())) }
    }

    // ---- List ------------------------------------------------------------------------------

    fun setTheme(t: ThemeMode) = viewModelScope.launch { c.settings.setTheme(t) }

    fun setUnits(u: UnitsSetting) =
        viewModelScope.launch {
            c.settings.setUnits(u)
            c.repository.unitsChanged()
        }

    fun removeStaple(name: String) =
        viewModelScope.launch {
            val key = c.repository.staples().firstOrNull { stapleName(it) == name } ?: return@launch
            c.repository.setStaple(key, false)
        }

    // ---- Aisles ----------------------------------------------------------------------------

    /** Move [aisle] one place up (-1) or down (+1) in the walking order. */
    fun moveAisle(
        aisle: AisleCode,
        delta: Int,
    ) = viewModelScope.launch {
        val order = _ui.value.aisles.toMutableList()
        val i = order.indexOf(aisle)
        val j = i + delta
        if (i < 0 || j !in order.indices) return@launch
        order[i] = order[j].also { order[j] = order[i] }
        c.repository.setAisleOrder(order)
        _ui.update { it.copy(aisles = order) }
    }

    // ---- Watch -----------------------------------------------------------------------------

    fun setWatchVibrate(on: Boolean) = viewModelScope.launch { c.settings.setWatchVibrate(on) }

    // ---- Claude ----------------------------------------------------------------------------

    /** Saves the key and tests it straight away. */
    fun saveKey(text: String) =
        viewModelScope.launch {
            // Pasted keys can carry invisible characters (zero-width spaces, line breaks).
            val key = cleanKey(text)
            if (key.isEmpty()) return@launch
            if (!KEY_CHARS.matches(key)) {
                _ui.update { it.copy(keyStatus = KeyStatus.BAD_KEY) }
                return@launch
            }
            if (!c.apiKeyStore.set(key)) {
                _ui.update { it.copy(keyStatus = KeyStatus.ERROR) }
                return@launch
            }
            c.settings.setKeyWasSet(true)
            c.settings.setKeyLostNotice(false)
            test(key)
        }

    fun testKey() = viewModelScope.launch { c.apiKeyStore.get()?.let { test(it) } }

    private suspend fun test(key: String) {
        _ui.update { it.copy(keyStatus = KeyStatus.TESTING) }
        val r =
            when (c.claudeClient.testKey(key)) {
                KeyCheck.OK -> KeyStatus.OK
                KeyCheck.BAD_KEY -> KeyStatus.BAD_KEY
                KeyCheck.OFFLINE -> KeyStatus.OFFLINE
                KeyCheck.ERROR -> KeyStatus.ERROR
            }
        _ui.update { it.copy(keyStatus = r) }
    }

    /** "Remove key": from disk and from memory. */
    fun removeKey() =
        viewModelScope.launch {
            c.apiKeyStore.clear()
            c.settings.setKeyWasSet(false)
            _ui.update { it.copy(keyStatus = null) }
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
