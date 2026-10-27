package com.robert4lyf.hypershop.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.robert4lyf.hypershop.claude.ClaudeModel
import com.robert4lyf.hypershop.claude.SpendGuard
import com.robert4lyf.hypershop.core.merge.UnitsSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Light, dark, or following the phone. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Settings (PLAN §3.5): list, aisles, watch, Claude, advanced. Backed up with the database. */
class Settings(
    private val store: DataStore<Preferences>,
) {
    // Distinct: any other setting's write re-emits store.data, and these redraw the list or the whole app.
    val units: Flow<UnitsSetting> =
        store.data.map { p ->
            p[UNITS]?.let { runCatching { UnitsSetting.valueOf(it) }.getOrNull() } ?: UnitsSetting.METRIC
        }.distinctUntilChanged()
    val theme: Flow<ThemeMode> =
        store.data
            .map { p -> p[THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM }
            .distinctUntilChanged()
    val model: Flow<ClaudeModel> = store.data.map { p -> p[MODEL]?.let { ClaudeModel.fromId(it) } ?: ClaudeModel.DEFAULT }
    val monthlyCapUsd: Flow<Double> = store.data.map { it[MONTHLY_CAP] ?: SpendGuard.DEFAULT_MONTHLY_CAP_USD }
    val dailyCalls: Flow<Int> = store.data.map { it[DAILY_CALLS] ?: SpendGuard.DEFAULT_DAILY_CALLS }

    /** W.1: with a key, Claude reads every new recipe (the rules read first and check it); off, only when the rules are unsure. */
    val claudeAlways: Flow<Boolean> = store.data.map { it[CLAUDE_ALWAYS] ?: true }
    val debugLog: Flow<Boolean> = store.data.map { it[DEBUG_LOG] ?: false }

    /** How the list is laid out. A "TYPE" from before the review of 2026-10-27 (cut 5) reads as the store order. */
    val listView: Flow<ListView> =
        store.data.map { p -> p[LIST_VIEW]?.let { runCatching { ListView.valueOf(it) }.getOrNull() } ?: ListView.STORE }

    /** Planning the list, or shopping with it: kept across process death, so a shop survives a restart. */
    val listMode: Flow<ListMode> =
        store.data
            .map { p -> p[LIST_MODE]?.let { runCatching { ListMode.valueOf(it) }.getOrNull() } ?: ListMode.PLAN }
            .distinctUntilChanged()

    /** The one-time "your key could not be read" notice is pending. */
    val keyLostNotice: Flow<Boolean> = store.data.map { it[KEY_LOST] ?: false }

    /**
     * A key was saved on this install's data. With no key in the store that
     * means it didn't survive a restore (the key file is never backed up).
     */
    val keyWasSet: Flow<Boolean> = store.data.map { it[KEY_SET] ?: false }

    suspend fun setKeyWasSet(on: Boolean) = store.edit { it[KEY_SET] = on }

    /** Missing 4 of the review: which store's walking order the list and the watch follow (1, or 2 for the second store). */
    val activeStore: Flow<Long> = store.data.map { it[ACTIVE_STORE]?.toLong() ?: 1L }.distinctUntilChanged()

    suspend fun setActiveStore(id: Long) = store.edit { it[ACTIVE_STORE] = id.toInt().coerceIn(1, 2) }

    /** Missing 8 of the review: the one-time "add a Claude key" nudge on a review read offline has been shown. */
    val keyNudged: Flow<Boolean> = store.data.map { it[KEY_NUDGED] ?: false }

    suspend fun setKeyNudged() = store.edit { it[KEY_NUDGED] = true }

    /** How often a review row's sheet has been opened: the review's "tap an ingredient" hint stops after a few. */
    val reviewSheetOpens: Flow<Int> = store.data.map { it[REVIEW_SHEET_OPENS] ?: 0 }

    suspend fun bumpReviewSheetOpens() = store.edit { it[REVIEW_SHEET_OPENS] = (it[REVIEW_SHEET_OPENS] ?: 0) + 1 }

    suspend fun setUnits(u: UnitsSetting) = store.edit { it[UNITS] = u.name }

    suspend fun setTheme(t: ThemeMode) = store.edit { it[THEME] = t.name }

    suspend fun setModel(m: ClaudeModel) = store.edit { it[MODEL] = m.id }

    suspend fun setMonthlyCap(usd: Double) = store.edit { it[MONTHLY_CAP] = usd.coerceIn(0.0, 100.0) }

    suspend fun setDailyCalls(n: Int) = store.edit { it[DAILY_CALLS] = n.coerceIn(0, 1000) }

    suspend fun setClaudeAlways(on: Boolean) = store.edit { it[CLAUDE_ALWAYS] = on }

    suspend fun setDebugLog(on: Boolean) = store.edit { it[DEBUG_LOG] = on }

    suspend fun setListView(v: ListView) = store.edit { it[LIST_VIEW] = v.name }

    suspend fun setListMode(m: ListMode) = store.edit { it[LIST_MODE] = m.name }

    suspend fun setKeyLostNotice(on: Boolean) = store.edit { it[KEY_LOST] = on }

    /**
     * A backup's settings in one write, so a restore never leaves half of them
     * changed; null leaves that one as it is. The list comes back planned, not
     * mid-shop. The key's own flags are not touched: the key never travels.
     */
    suspend fun restore(
        units: UnitsSetting?,
        theme: ThemeMode?,
        listView: ListView?,
        model: ClaudeModel?,
        monthlyCapUsd: Double?,
        dailyCalls: Int?,
    ) = store.edit { p ->
        units?.let { p[UNITS] = it.name }
        theme?.let { p[THEME] = it.name }
        listView?.let { p[LIST_VIEW] = it.name }
        model?.let { p[MODEL] = it.id }
        monthlyCapUsd?.takeIf { it.isFinite() }?.let { p[MONTHLY_CAP] = it.coerceIn(0.0, 100.0) }
        dailyCalls?.let { p[DAILY_CALLS] = it.coerceIn(0, 1000) }
        p[LIST_MODE] = ListMode.PLAN.name
    }

    suspend fun guard(): SpendGuard = SpendGuard(monthlyCapUsd.first(), dailyCalls.first())

    private companion object {
        val UNITS = stringPreferencesKey("units")
        val MODEL = stringPreferencesKey("claude_model")
        val THEME = stringPreferencesKey("theme")
        val MONTHLY_CAP = doublePreferencesKey("claude_monthly_cap_usd")
        val DAILY_CALLS = intPreferencesKey("claude_daily_calls")
        val CLAUDE_ALWAYS = booleanPreferencesKey("claude_always")
        val DEBUG_LOG = booleanPreferencesKey("debug_log")
        val LIST_VIEW = stringPreferencesKey("list_view")
        val LIST_MODE = stringPreferencesKey("list_mode")
        val KEY_LOST = booleanPreferencesKey("claude_key_lost_notice")
        val KEY_SET = booleanPreferencesKey("claude_key_was_set")
        val REVIEW_SHEET_OPENS = intPreferencesKey("review_sheet_opens")
        val ACTIVE_STORE = intPreferencesKey("active_store")
        val KEY_NUDGED = booleanPreferencesKey("claude_key_nudged")
    }
}

/** The list's layouts: aisles in walking order (groups labelled when an aisle has more than one), or one card per recipe. */
enum class ListView { STORE, RECIPE }

/** What the List tab is for right now: planning (add, review, edit) or shopping (tick, next aisle, finish). */
enum class ListMode { PLAN, SHOP }
