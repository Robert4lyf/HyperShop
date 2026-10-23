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

/** [n] saved recipes, each with a photo-like 1280-pixel JPEG of its own (T.4's probe, U.4's cold start). */
internal fun AppContainer.photographed(n: Int): List<Long> {
    val dir = java.io.File(context.filesDir, com.robert4lyf.hypershop.importing.RecipePhotos.DIR).apply { mkdirs() }
    val rnd = java.util.Random(n.toLong())
    return (1..n).map { i ->
        val id = saveRecipe("Recipe $i", "2 onions", "500g beef mince")
        // A photo-like JPEG: blocks of colour with grain, about the size a real 1280-pixel photo compresses to.
        val bmp = android.graphics.Bitmap.createBitmap(1280, 960, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint()
        for (b in 0 until 600) {
            paint.color = android.graphics.Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
            val x = rnd.nextInt(1280).toFloat()
            val y = rnd.nextInt(960).toFloat()
            canvas.drawRect(x, y, x + 20 + rnd.nextInt(200), y + 20 + rnd.nextInt(200), paint)
        }
        val bytes =
            java.io.ByteArrayOutputStream().also {
                bmp.compress(
                    android.graphics.Bitmap.CompressFormat.JPEG,
                    85,
                    it,
                )
            }.toByteArray()
        bmp.recycle()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).take(12).joinToString("") { "%02x".format(it) }
        val name = "$hash.jpg"
        java.io.File(dir, name).writeBytes(bytes)
        runBlocking { repository.setRecipePhoto(repository.libraryKeyOf(id)!!, name, null) }
        id
    }
}

/** Waits until no photo has been decoded for [quietMs]: the screen has drawn what it is going to. */
