package com.robert4lyf.hypershop.ui.recipes

import android.net.Uri
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.importing.ImportInput
import com.robert4lyf.hypershop.importing.ImportResult
import com.robert4lyf.hypershop.importing.PhotoStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * The add-a-recipe sheet's three ways in (a link, typed in, a photo), shared by
 * Recipes and This week (X.10). Each ends on the review screen: a link through
 * the share path, which opens it when it is ready; the other two return the job
 * to open, or null when there is nothing to review.
 */
internal class NewRecipe(
    private val c: AppContainer,
) {
    /** The same path as sharing a page to the app. */
    fun link(text: String) {
        c.applicationScope.launch { c.importShared(text, null) }
    }

    /** The photo is saved, scaled down, and reviewed like a shared page; null when it can't be opened. */
    suspend fun photo(
        uri: Uri,
        book: String,
        page: String,
    ): Long? {
        val file = PhotoStore.save(c.context, uri)
        // The camera's own copy (a photo of someone's cookbook) is not left in the cache.
        File(File(c.context.cacheDir, "camera"), "shot.jpg").delete()
        if (file == null) return null
        val result =
            ImportResult(
                url = null,
                title = null,
                servings = null,
                locale = UnitLocale.METRIC,
                method = "PHOTO",
                claudePending = true,
                photo = file.path,
                book = book.trim().ifEmpty { null },
                page = page.trim().ifEmpty { null },
            )
        return c.importJobs.enqueueReady(ImportInput("", "Photo of a recipe"), result)
    }

    /** Typed lines are rule-parsed here, never sent to Claude; the review screen calls Claude once it is open. */
    suspend fun manual(
        title: String,
        servings: Int?,
        ingredients: String,
    ): Long? {
        val lines = ingredients.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val name = title.trim()
        if (lines.isEmpty() || name.isEmpty()) return null
        val parsed = c.importer.fromLines(lines, name, allowClaude = false, model = c.settings.model.first())
        return c.importJobs.enqueueReady(ImportInput("", name), parsed.copy(servings = servings))
    }
}
