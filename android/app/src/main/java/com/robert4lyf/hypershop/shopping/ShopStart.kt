package com.robert4lyf.hypershop.shopping

import com.robert4lyf.hypershop.data.StoreProfileEntity
import com.robert4lyf.hypershop.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Which store's walking order the list (and the watch) follow. Step Z: there is one list screen, so this is only the
 * store: "Change store" in the list's menu offers the stores when there are two or more ([change]), and [start] makes
 * the one chosen the active store.
 */
class ShopStart(private val c: AppContainer) {
    val choices = MutableStateFlow<List<StoreProfileEntity>>(emptyList())
    private val lock = Mutex()

    /** The list's "Change store": the stores to choose from, when there is a choice. */
    suspend fun change() {
        c.awaitReady()
        val stores = c.repository.storeProfiles()
        if (stores.size > 1) choices.value = stores
    }

    suspend fun start(id: Long) =
        lock.withLock {
            c.awaitReady()
            require(c.repository.storeProfiles().any { it.id == id }) { "Store is no longer configured" }
            if (c.settings.activeStore.first() != id) {
                c.settings.setActiveStore(id)
                c.repository.touchListRev()
            }
            choices.value = emptyList()
        }
}
