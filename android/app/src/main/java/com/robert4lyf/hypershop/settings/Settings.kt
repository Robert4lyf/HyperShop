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
    val watchVibrate: Flow<Boolean> = store.data.map { it[WATCH_VIBRATE] ?: true }
    val debugLog: Flow<Boolean> = store.data.map { it[DEBUG_LOG] ?: false }

    /**
     * How the list is laid out. Read from the older on/off switch when it has
     * never been set, so "Group by type" survives the update.
     */
    val listView: Flow<ListView> =
        store.data.map { p ->
            p[LIST_VIEW]?.let { runCatching { ListView.valueOf(it) }.getOrNull() }
                ?: if (p[LIST_CATEGORISED] == true) ListView.TYPE else ListView.STORE
        }

    /** The one-time "your key could not be read" notice is pending. */
    val keyLostNotice: Flow<Boolean> = store.data.map { it[KEY_LOST] ?: false }

    /**
     * A key was saved on this install's data. With no key in the store that
     * means it didn't survive a restore (the key file is never backed up).
     */
    val keyWasSet: Flow<Boolean> = store.data.map { it[KEY_SET] ?: false }

    suspend fun setKeyWasSet(on: Boolean) = store.edit { it[KEY_SET] = on }

    suspend fun setUnits(u: UnitsSetting) = store.edit { it[UNITS] = u.name }

    suspend fun setTheme(t: ThemeMode) = store.edit { it[THEME] = t.name }

    suspend fun setModel(m: ClaudeModel) = store.edit { it[MODEL] = m.id }

    suspend fun setMonthlyCap(usd: Double) = store.edit { it[MONTHLY_CAP] = usd.coerceIn(0.0, 100.0) }

    suspend fun setDailyCalls(n: Int) = store.edit { it[DAILY_CALLS] = n.coerceIn(0, 1000) }

    suspend fun setWatchVibrate(on: Boolean) = store.edit { it[WATCH_VIBRATE] = on }

    suspend fun setDebugLog(on: Boolean) = store.edit { it[DEBUG_LOG] = on }

    suspend fun setListView(v: ListView) = store.edit { it[LIST_VIEW] = v.name }

    suspend fun setKeyLostNotice(on: Boolean) = store.edit { it[KEY_LOST] = on }

    suspend fun guard(): SpendGuard = SpendGuard(monthlyCapUsd.first(), dailyCalls.first())

    private companion object {
        val UNITS = stringPreferencesKey("units")
        val MODEL = stringPreferencesKey("claude_model")
        val THEME = stringPreferencesKey("theme")
        val MONTHLY_CAP = doublePreferencesKey("claude_monthly_cap_usd")
        val DAILY_CALLS = intPreferencesKey("claude_daily_calls")
        val WATCH_VIBRATE = booleanPreferencesKey("watch_vibrate")
        val DEBUG_LOG = booleanPreferencesKey("debug_log")
        val LIST_CATEGORISED = booleanPreferencesKey("list_categorised")
        val LIST_VIEW = stringPreferencesKey("list_view")
        val KEY_LOST = booleanPreferencesKey("claude_key_lost_notice")
        val KEY_SET = booleanPreferencesKey("claude_key_was_set")
    }
}

/** The list's layouts: aisles in walking order, aisles split by type, or one card per recipe. */
enum class ListView { STORE, TYPE, RECIPE }
