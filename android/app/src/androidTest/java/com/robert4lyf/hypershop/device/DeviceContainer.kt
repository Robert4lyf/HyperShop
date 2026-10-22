package com.robert4lyf.hypershop.device

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.RuleParser
import com.robert4lyf.hypershop.data.HyperShopDatabase
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.di.Startup
import com.robert4lyf.hypershop.secrets.InMemoryApiKeyStore
import com.robert4lyf.hypershop.watch.FakeWatchLink
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * A container for device tests: Room in memory on the device's own SQLite,
 * settings in a throwaway file, an in-memory key and a fake watch. The app's
 * own database and settings are never opened, but its files directory is
 * shared (the ingredient library and a restore's journal live there), so run
 * these on an emulator, never on the phone that holds your list.
 */
object DeviceContainer {
    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    fun create(
        key: String? = null,
        ready: Boolean = true,
        /** A database already in use: the same data under a new container, as after the app restarts. */
        db: HyperShopDatabase? = null,
    ): AppContainer {
        val ctx = context
        if (db == null) {
            File(ctx.filesDir, "library").deleteRecursively()
            File(ctx.noBackupFilesDir, "restore").deleteRecursively()
        }
        val dir = File(ctx.cacheDir, "device-${System.nanoTime()}").apply { mkdirs() }
        return AppContainer(
            context = ctx,
            db = db ?: HyperShopDatabase.inMemory(ctx),
            dataStore = PreferenceDataStoreFactory.create { File(dir, "settings.preferences_pb") },
            apiKeyStore = InMemoryApiKeyStore(key),
            watchLink = FakeWatchLink(),
        ).also { if (ready) it.startup.value = Startup.Ready }
    }
}

/** A recipe in the library and not on the list, as the Meals screen finds it. */
fun AppContainer.saveRecipe(
    title: String,
    vararg lines: String,
): Long =
    runBlocking {
        val parser = RuleParser(catalog)
        val stored =
            repository.storeRecipe(
                "https://example.com/${title.lowercase().replace(' ', '-')}", title, 4, UnitLocale.METRIC, "WEB",
                ParsedBy.RULES, null, false, lines.map { parser.parse(it) },
            )
        // Through the review's path (it joins the library), not planned: a saved recipe, nothing more.
        val applied =
            repository.apply(repository.preview(stored, 4, null), plan = false) as com.robert4lyf.hypershop.core.merge.ApplyResult.Applied
        repository.removeUse(applied.useId)
        stored.recipe.id
    }
