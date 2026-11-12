package com.robert4lyf.hypershop.ui.list

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
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

    private fun show() =
        compose.setContent {
            HyperShopTheme(dynamicColor = false) {
                sheet?.let { s ->
                    ItemSheetHost(s, actions(), onDismiss = {
                        events += "dismiss"
                        sheet = null
                    }, now = { clock })
                }
            }
        }

    private fun edit() = compose.onNodeWithTag("edit-name").performScrollTo().performTextReplacement("red onion")

    @Test
    fun aCleanSheetMarksBoughtStraightAwayAndOnlyOnceForRepeatedTaps() {
        closes = false
        show()
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

    @Test
    fun discardingEditsMarksBoughtExactlyOnce() {
        show()
        edit()
        compose.onNodeWithTag("toggle-item").performScrollTo().performClick()
        assertEquals(emptyList<String>(), events)
        compose.onNodeWithText("Discard edits and mark bought").performClick()
        assertEquals(listOf("toggle"), events)
        compose.onNodeWithTag("discard-confirm").assertDoesNotExist()
        compose.onNodeWithTag("edit-name").assertDoesNotExist()
    }

    @Test
    fun aDoneItemOffersMarkNotBought() {
        sheet = ONION.copy(done = true)
        show()
        edit()
        compose.onNodeWithTag("toggle-item").performScrollTo().performClick()
        compose.onNodeWithText("Discard edits and mark not bought").performClick()
        assertEquals(listOf("toggle"), events)
    }

    @Test
    fun removeAndSwapNameWhatTheyWillDo() {
        show()
        edit()
        compose.onNodeWithTag("delete-item").performScrollTo().performClick()
        compose.onNodeWithText("Discard edits and remove").assertExists()
        compose.onNodeWithTag("discard-keep").performClick()
        compose.onNodeWithTag("swap-shallot").performScrollTo().performClick()
        compose.onNodeWithText("Just this time").performClick()
        compose.onNodeWithText("Discard edits and swap for shallot").performClick()
        assertEquals(listOf("swap shallot false"), events)
    }

    @Test
    fun keepEditingRunsNothingKeepsTheDraftAndLeavesNothingQueued() {
        closes = false
        show()
        edit()
        compose.onNodeWithTag("toggle-item").performScrollTo().performClick()
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithTag("edit-name").assertTextContains("red onion")
        assertEquals(emptyList<String>(), events)
        // The next thing asked is what runs, not the tick backed out of.
        compose.onNodeWithTag("delete-item").performScrollTo().performClick()
        compose.onNodeWithText("Discard edits and remove").performClick()
        assertEquals(listOf("delete"), events)
        // The draft went with the confirmation: the sheet (still open here) starts again from the item.
        compose.onNodeWithTag("edit-name").assertTextContains("onion")
        compose.onNodeWithTag("save-item").assertIsNotEnabled()
    }

    @Test
    fun dismissingTheConfirmationRunsNothingAndTheEditorStaysUsable() {
        show()
        edit()
        compose.onNodeWithTag("toggle-item").performScrollTo().performClick()
        compose.onNodeWithTag("discard-confirm").assertExists()
        Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithTag("discard-confirm").assertDoesNotExist()
        assertEquals(emptyList<String>(), events)
        compose.onNodeWithTag("edit-name").performScrollTo().performTextReplacement("spring onion")
        // Save is pinned below the scrolling form.
        compose.onNodeWithTag("save-item").performClick()
        compose.onNodeWithText("Rename and save").performClick()
        assertEquals(listOf("save spring onion"), events)
    }

    @Test
    fun backOnADirtySheetAsksAndThenOnlyCloses() {
        show()
        edit()
        Espresso.pressBack()
        compose.onNodeWithText("Discard changes").performClick()
        assertEquals(listOf("dismiss"), events)
    }

    @Test
    fun aTickMadeElsewhereMeanwhileIsNotUndone() {
        show()
        edit()
        compose.onNodeWithTag("toggle-item").performScrollTo().performClick()
        // The watch ticks it while the question is up.
        sheet = sheet!!.copy(done = true)
        compose.onNodeWithText("Discard edits and mark bought").performClick()
        assertEquals(listOf("dismiss"), events)
    }

    @Test
    fun nothingRunsWhileSaving() {
        show()
        edit()
        compose.onNodeWithTag("toggle-item").performScrollTo().performClick()
        sheet = sheet!!.copy(saving = true)
        compose.onNodeWithTag("discard-confirm").assertIsNotEnabled()
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun aPendingQuestionSurvivesRecreationAsADescriptionAndRunsOnlyWhenConfirmed() {
        val restore = StateRestorationTester(compose)
        restore.setContent {
            HyperShopTheme(dynamicColor = false) {
                sheet?.let { s -> ItemSheetHost(s, actions(), onDismiss = { events += "dismiss" }, now = { clock }) }
            }
        }
        edit()
        compose.onNodeWithTag("toggle-item").performScrollTo().performClick()
        restore.emulateSavedInstanceStateRestore()
        // Back as it was: the question, the draft, and nothing done.
        compose.onNodeWithText("Discard edits and mark bought").assertExists()
        assertEquals(emptyList<String>(), events)
        compose.onNodeWithText("Discard edits and mark bought").performClick()
        assertEquals(listOf("toggle"), events)
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
