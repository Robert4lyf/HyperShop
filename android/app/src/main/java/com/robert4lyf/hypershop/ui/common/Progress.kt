package com.robert4lyf.hypershop.ui.common

import com.robert4lyf.hypershop.core.merge.UseProgress
import com.robert4lyf.hypershop.core.merge.UseState

/**
 * How a recipe's shopping stands, in the same words on the Recipes tab, its
 * sheet and Meals: "3 still needed", "All bought", "Nothing to buy — all at home".
 * Read from [UseProgress], so no screen counts items of its own.
 */
fun UseProgress.label(): String =
    when (state) {
        // Not "(1 partly bought)": that is the item's state, shown on the list, and read
        // under a recipe whose own share is not bought at all it says the opposite.
        UseState.STILL_NEEDED -> "$toBuy still needed"
        UseState.ALL_BOUGHT -> "All bought"
        UseState.BOUGHT_THE_REST -> "Nothing left to buy"
        UseState.NOTHING_TO_BUY -> "Nothing to buy — all at home"
        UseState.DROPPED -> "Taken off the list"
    }

/** [label] for a chip: shorter where it can be. */
fun UseProgress.chip(): String =
    when (state) {
        UseState.STILL_NEEDED -> "$toBuy still needed"
        UseState.NOTHING_TO_BUY -> "All at home"
        else -> label()
    }
