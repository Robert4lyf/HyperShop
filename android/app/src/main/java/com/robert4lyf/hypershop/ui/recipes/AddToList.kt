package com.robert4lyf.hypershop.ui.recipes

import com.robert4lyf.hypershop.core.merge.ApplyResult
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.ui.common.UiMessage

/**
 * Step Z "Add to list", the one way a saved recipe goes on the list from any screen: its ingredients go on at once,
 * planned for this week and linked in the same transaction ([com.robert4lyf.hypershop.data.Planner.addToList]).
 * The message says what is now to buy; its Undo takes back only what is still unbought.
 */
suspend fun AppContainer.addRecipeToList(
    libraryKey: String,
    title: String,
): UiMessage {
    awaitReady()
    val added = planner.addToList(libraryKey) as? ApplyResult.Applied ?: return UiMessage("Couldn't add $title to the list")
    val list = repository.snapshot()
    val toBuy = list.liveItems.count { !list.isDone(it.id) && !list.isUnavailable(it.id) }
    return UiMessage("Added $title · $toBuy to buy", undo = { planner.takeBack(added.useId) })
}
