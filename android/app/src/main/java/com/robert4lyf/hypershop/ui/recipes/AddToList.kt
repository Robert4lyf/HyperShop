package com.robert4lyf.hypershop.ui.recipes

import com.robert4lyf.hypershop.core.merge.ApplyResult
import com.robert4lyf.hypershop.di.AppContainer

/** A saved recipe just put on the list: its use (what Undo takes back) and how many items are now to buy. */
data class RecipeAdded(
    val useId: Long,
    val toBuy: Int,
)

/**
 * Step Z "Add to list", the one way a saved recipe goes on the list from any screen: its ingredients go on at once,
 * planned for this week and linked in the same transaction ([com.robert4lyf.hypershop.data.Planner.addToList]).
 * Null when it could not be added.
 */
suspend fun AppContainer.putRecipeOnList(libraryKey: String): RecipeAdded? {
    awaitReady()
    val added = planner.addToList(libraryKey) as? ApplyResult.Applied ?: return null
    val list = repository.snapshot()
    return RecipeAdded(added.useId, list.liveItems.count { !list.isDone(it.id) && !list.isUnavailable(it.id) })
}
