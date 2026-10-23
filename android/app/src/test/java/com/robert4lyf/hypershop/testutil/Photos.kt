package com.robert4lyf.hypershop.testutil

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.RuleParser
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.importing.RecipePhotos
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File

/** Recipe photos for tests: real JPEGs, saved recipes to put them on, and the phone's photo folder. */
object Photos {
    fun jpeg(
        w: Int = 640,
        h: Int = 480,
        colour: Int = Color.rgb(200, 80, 40),
    ): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    /** A file in the cache to pick from "the phone's photos". */
    fun pickable(bytes: ByteArray = jpeg()): Uri = Uri.fromFile(File.createTempFile("pick", ".jpg").apply { writeBytes(bytes) })

    /** A recipe saved to the library; its library key. */
    fun AppContainer.saved(
        title: String,
        url: String? = "https://example.com/${title.lowercase().replace(' ', '-')}",
    ): String =
        runBlocking {
            val s =
                repository.storeRecipe(
                    url, title, 4, UnitLocale.METRIC, "WEB", ParsedBy.RULES, null, false,
                    listOf(RuleParser().parse("2 onions")),
                )
            repository.saveToLibrary(s.recipe.id, null)
            repository.libraryKeyOf(s.recipe.id)!!
        }

    fun AppContainer.photoOf(key: String): String? = runBlocking { repository.recipeNote(key)?.photo }

    /** The photo folder, as a fresh install of another phone would have it: empty. */
    fun AppContainer.photoFolder(): File = File(context.filesDir, RecipePhotos.DIR)

    fun AppContainer.photoFiles(): Set<String> = photoFolder().listFiles().orEmpty().map { it.name }.toSet()

    /** Every photo a recipe names is a file: the invariant nothing may break. */
    fun AppContainer.everyNamedPhotoIsThere(): Boolean = runBlocking { repository.photosInUse() }.all { recipePhotos.file(it) != null }
}
