package com.robert4lyf.hypershop.ui.list

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private val ONION =
    ItemSheet(
        1,
        "onion",
        listOf("2"),
        listOf(ItemSource("Weeknight chilli", "2")),
        null,
        AisleCode.PRODUCE,
        swaps = listOf("shallot"),
        canAlways = true,
        amount = "2",
    )

/**
 * The item sheet as the list hosts it: [ItemSheetHost] around the real content, with actions that behave as
 * the list's do (each closes the sheet, as `ListRoute` wires them). A dirty editor asks before a tick, swap or
 * removal and then does exactly that once; nothing asked and backed out of runs later.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ItemSheetHostTest {
    @get:Rule val compose = createComposeRule()

    private val events = mutableListOf<String>()
    private var sheet by mutableStateOf<ItemSheet?>(ONION)
    private var clock = 0L

    /** Whether an action closes the sheet, as the list's do; off to test taps that land before it closes. */
    private var closes = true

    private fun actions() =
        ItemSheetActions(
            onSave = { ch ->
                events += "save ${ch.name ?: ""}${ch.amount ?: ""}"
                true
            },
            onDelete = {
                events += "delete"
                if (closes) sheet = null
            },
            onSwap = { to, always ->
                events += "swap $to $always"
                if (closes) sheet = null
            },
            onToggle = {
                events += "toggle"
                if (closes) sheet = null
            },
        )

    private fun show(editing: Boolean = true) =
        compose.setContent {
            HyperShopTheme(dynamicColor = false) {
                sheet?.let { s ->
                    ItemSheetHost(s, actions(), onDismiss = {
                        events += "dismiss"
                        sheet = null
                    }, now = { clock }, initiallyEditing = editing)
                }
            }
        }

    private fun edit() = compose.onNodeWithTag("edit-name").performScrollTo().performTextReplacement("red onion")

    @Test
    fun aCleanSheetMarksBoughtStraightAwayAndOnlyOnceForRepeatedTaps() {
        closes = false
        // Step AC: Mark bought is on the item's summary; the form (Change amount) holds only name, amount and aisle.
        show(editing = false)
        compose.onNodeWithTag("toggle-item").performScrollTo().performClick()
        compose.onNodeWithTag("toggle-item").performClick()
        compose.onNodeWithTag("toggle-item").performClick()
        assertEquals(listOf("toggle"), events)
        compose.onNodeWithTag("discard-confirm").assertDoesNotExist()
        // Later, a deliberate tap acts again.
        clock += 5_000
        compose.onNodeWithTag("toggle-item").performClick()
        assertEquals(listOf("toggle", "toggle"), events)
    }

    // Step AC: the form no longer shows Mark bought, Remove or the swaps, so "discard edits and …" can no longer be asked
    // from it; what is left to ask is Back on a form with edits.
    @Test
    fun backOnADirtySheetAsksAndThenOnlyCloses() {
        show()
        edit()
        Espresso.pressBack()
        compose.onNodeWithText("Discard changes").performClick()
        assertEquals(listOf("dismiss"), events)
    }

    @Test
    fun anUnreadableSavedDescriptionRestoresAsNothingPending() {
        val saver = PendingSheetAction.Saver
        val scope = androidx.compose.runtime.saveable.SaverScope { true }
        val cases =
            listOf(
                PendingSheetAction.Close,
                PendingSheetAction.Remove,
                PendingSheetAction.Tick(true),
                PendingSheetAction.Tick(false),
                PendingSheetAction.Swap("leek", true),
                null,
            )
        for (a in cases) assertEquals(a, saver.restore(with(saver) { scope.save(a) }!!))
        assertEquals(null, saver.restore(arrayListOf("tick", "maybe")))
        assertEquals(null, saver.restore(arrayListOf("launch-missiles")))
        assertEquals(null, saver.restore("garbage"))
    }
}
