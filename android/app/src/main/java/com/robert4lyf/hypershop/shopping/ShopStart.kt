package com.robert4lyf.hypershop.shopping

import com.robert4lyf.hypershop.data.StoreProfileEntity
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.settings.ListMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Every entry point uses the same store choice and changes mode only after the order is ready. */
class ShopStart(private val c: AppContainer) {
    val choices = MutableStateFlow<List<StoreProfileEntity>>(emptyList())
    private val lock = Mutex()

    /**
     * Step Q: the store used last is remembered, so Shopping view opens at once; the question is asked only when that
     * store is no longer configured. [change] asks on purpose (Shopping view's "Change store").
     */
    suspend fun request() {
        c.awaitReady()
        val stores = c.repository.storeProfiles()
        val active = c.settings.activeStore.first()
        when {
            stores.any { it.id == active } -> start(active)
            stores.size > 1 -> choices.value = stores
            else -> start(stores.firstOrNull()?.id ?: 1L)
        }
    }

    /** Shopping view's "Change store": the stores to choose from. */
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
            c.settings.setListMode(ListMode.SHOP)
            choices.value = emptyList()
        }
}
