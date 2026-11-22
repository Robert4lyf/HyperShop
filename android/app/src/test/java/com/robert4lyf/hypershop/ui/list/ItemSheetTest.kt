package com.robert4lyf.hypershop.ui.list

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.testutil.Shots
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val SHEET =
    ItemSheet(
        1,
        "onion",
        listOf("2", "200g"),
        listOf(ItemSource("Weeknight chilli", "2"), ItemSource("Added by hand", "200g")),
        "1 × 1kg bag",
        AisleCode.PRODUCE,
        swaps = listOf("shallot", "red onion"),
        canAlways = true,
    )

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ItemSheetTest {
    @get:Rule val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private fun show(
        dark: Boolean = false,
        editing: Boolean = true,
    ) = compose.setContent {
        HyperShopTheme(dark = dark, dynamicColor = false) {
            ItemSheetContent(
                SHEET,
                ItemSheetActions(
                    onSave = { ch ->
                        events += "save $ch"
                        ch.amount != "lots"
                    },
                    onDelete = { events += "delete" },
                    onSwap = { to, always -> events += "swap $to $always" },
                    onToggle = { events += "toggle" },
                ),
                initiallyEditing = editing,
            )
        }
    }

    @Test
    fun theSheetOpensOnWhatToBuyAndWhatItIsForWithTheFormBehindChangeAmount() {
        compose.setContent {
            HyperShopTheme(dynamicColor = false) {
                ItemSheetContent(
                    SHEET.copy(toBuy = listOf("1"), explanation = "Required 2 · At home 0 · Bought 1 · Buy 1"),
                    ItemSheetActions(onSave = { true }, onToggle = { events += "toggle" }),
                )
            }
        }
        compose.onNodeWithText("Buy 1").assertExists()
        compose.onNodeWithText("For Weeknight chilli, Added by hand").assertExists()
        // No dense form, and the bookkeeping is one tap away.
        compose.onNodeWithTag("edit-name").assertDoesNotExist()
        compose.onNodeWithTag("item-breakdown").assertDoesNotExist()
        compose.onNodeWithTag("delete-item").assertDoesNotExist()
        compose.onRoot().captureRoboImage(Shots.path("item-summary-light.png"))
        compose.onNodeWithTag("item-more").performClick()
        compose.onNodeWithTag("item-breakdown").assertTextEquals("Required 2 · At home 0 · Bought 1 · Buy 1")
        compose.onNodeWithTag("delete-item").performScrollTo().assertExists()
        compose.onNodeWithTag("toggle-item").performScrollTo().performClick()
        compose.onNodeWithTag("item-change").performScrollTo().performClick()
        compose.onNodeWithTag("edit-qty").assertExists()
        compose.onNodeWithTag("save-item").assertExists()
        assertEquals(listOf("toggle"), events)
    }

    @Test
    fun aChangedDraftSurvivesRecreation() {
        val restore = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restore.setContent {
            HyperShopTheme(dynamicColor = false) { ItemSheetContent(SHEET, ItemSheetActions(onSave = { true }), initiallyEditing = true) }
        }
        compose.onNodeWithTag("edit-name").performTextReplacement("A corrected long ingredient name")
        compose.onNodeWithTag("edit-qty").performTextReplacement("450 g")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("edit-name").assertTextContains("A corrected long ingredient name")
        compose.onNodeWithTag("edit-qty").assertTextContains("450 g")
        compose.onNodeWithTag("save-item").assertIsEnabled()
    }

    @Test
    fun aSourceWithAPageOpensIt() {
        val opened = mutableListOf<String>()
        val handler =
            object : UriHandler {
                override fun openUri(uri: String) {
                    opened += uri
                }
            }
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                HyperShopTheme(dynamicColor = false) {
                    ItemSheetContent(
                        SHEET.copy(
                            sources =
                                listOf(
                                    ItemSource("Weeknight chilli", "2", "https://recipes.example/chilli"),
                                    ItemSource("Added by hand", "200 g"),
                                ),
                        ),
                        ItemSheetActions(onSave = { true }),
                    )
                }
            }
        }
        // Step AC: the sources are in More details, not in the form.
        compose.onNodeWithTag("item-more").performScrollTo().performClick()
        compose.onNodeWithText("Weeknight chilli").performScrollTo().performClick()
        compose.onNodeWithText("Added by hand").performScrollTo().performClick()
        assertEquals(listOf("https://recipes.example/chilli"), opened)
    }

    @Test
    fun theFormIsOnlyNameAmountAndAisleWithSave() {
        // Step AC: Change amount is one small form; the rest stays on the item's summary and More details.
        show(editing = true)
        compose.onNodeWithTag("edit-name").assertExists()
        compose.onNodeWithTag("edit-qty").assertExists()
        compose.onNodeWithTag("edit-aisle").assertExists()
        for (gone in listOf(
            "totals",
            "toggle-item",
            "delete-item",
            "swap-shallot",
            "item-more",
        )) compose.onNodeWithTag(gone).assertDoesNotExist()
        compose.onNodeWithText("Usually sold as 1 × 1kg bag").assertDoesNotExist()
        compose.onNodeWithText("Added by hand").assertDoesNotExist()
        compose.onNodeWithText("Edit item").assertDoesNotExist()
        compose.onNodeWithTag("edit-qty").performScrollTo().performTextInput("lots")
        compose.onNodeWithTag("save-item").performClick()
        compose.onNodeWithText("Enter an amount, like 2 or 200 ml").assertExists()
        assertEquals(listOf("save ${ItemChanges(amount = "lots")}"), events)
    }

    @Test
    fun anItemInTwoKindsOfUnitOpensOnTheOneItIsBoughtByAndSaysTheOther() {
        compose.setContent {
            HyperShopTheme(dynamicColor = false) {
                ItemSheetContent(
                    SHEET.copy(amount = "2", amountRest = "200g"),
                    ItemSheetActions(onSave = { true }),
                    initiallyEditing = true,
                )
            }
        }
        compose.onNodeWithTag("edit-qty").assertTextContains("2")
        compose.onNodeWithText("Also 200g on the list, kept as it is").assertExists()
    }

    @Test
    fun theSummaryKeepsTotalsSourcesPackAndRemove() {
        show(editing = false)
        compose.onNodeWithTag("item-more").performScrollTo().performClick()
        compose.onNodeWithText("Usually sold as 1 × 1kg bag").performScrollTo().assertExists()
        compose.onNodeWithText("Added by hand").performScrollTo().assertExists()
        compose.onNodeWithTag("delete-item").performScrollTo().performClick()
        compose.onNodeWithText("Remove from list").assertExists()
        assertEquals(listOf("delete"), events)
    }

    @Test
    fun oneSaveCarriesNameUnitAndAisle() {
        show()
        // Nothing changed yet: nothing to save.
        compose.onNodeWithTag("save-item").assertIsNotEnabled()
        compose.onNodeWithTag("edit-name").performScrollTo().performTextReplacement("red onion")
        compose.onNodeWithTag("edit-qty").performScrollTo().performTextInput("3")
        // A count has no conversion to kg: the amount is asked for, not carried over.
        compose.onNodeWithTag("more-units").performScrollTo().performClick()
        compose.onNodeWithTag("unit-kg").performScrollTo().performClick()
        compose.onNodeWithTag("unit-amount").performTextInput("3")
        compose.onNodeWithText("Use 3 kg").performClick()
        compose.onNodeWithTag("edit-qty").assertTextContains("3 kg")
        compose.onNodeWithTag("edit-aisle").performScrollTo().performClick()
        compose.onNodeWithTag("aisle-${AisleCode.DAIRY_EGGS.name}").performClick()
        compose.onNodeWithTag("always-aisle").performScrollTo().performClick()
        compose.onNodeWithTag("save-item").performClick()
        compose.onNodeWithText("Rename and save").performClick()
        val expected = ItemChanges("red onion", "3 kg", AisleCode.DAIRY_EGGS, alwaysAisle = false)
        assertEquals(listOf("save $expected"), events)
    }

    @Test
    fun categoryAndUsualPackAreTheirOwnSmallFormFromMoreDetails() {
        show(editing = false)
        compose.onNodeWithTag("item-more").performScrollTo().performClick()
        compose.onNodeWithTag("item-extras").performScrollTo().performClick()
        compose.onNodeWithTag("edit-name").assertDoesNotExist()
        compose.onNodeWithTag("new-group").performScrollTo().performTextInput("odds and ends")
        compose.onNodeWithText("Add").performScrollTo().performClick()
        compose.onNodeWithText("The category is kept for next time too.").assertExists()
        compose.onNodeWithTag("save-item").performClick()
        assertEquals(listOf("save ${ItemChanges(groupChanged = true, group = "Odds and ends")}"), events)
    }

    @Test
    fun aHandAddedItemsMoveIsNotAskedAbout() {
        // Step AB: no "Always put … in …" for an item only added by hand: the move is remembered, the message says so.
        compose.setContent {
            HyperShopTheme(dynamicColor = false) {
                ItemSheetContent(SHEET.copy(byHand = true), ItemSheetActions(onSave = { true }), initiallyEditing = true)
            }
        }
        compose.onNodeWithTag("edit-aisle").performScrollTo().performClick()
        compose.onNodeWithTag("aisle-${AisleCode.HOUSEHOLD.name}").performClick()
        compose.onNodeWithTag("always-aisle").assertDoesNotExist()
    }

    @Test
    fun tickAtTheTopFollowsTheRowsState() {
        var sheet by mutableStateOf(SHEET)
        compose.setContent {
            HyperShopTheme(dynamicColor = false) {
                val actions = ItemSheetActions(onSave = { true }, onToggle = { events += "toggle" })
                ItemSheetContent(sheet, actions)
            }
        }
        compose.onNodeWithText("Mark bought").performScrollTo().performClick()
        assertEquals(listOf("toggle"), events)
        sheet = SHEET.copy(done = true)
        compose.onNodeWithTag("toggle-item").assertTextEquals("Mark not bought")
    }

    @Test
    fun doneOnTheKeyboardSetsTheAmount() {
        show()
        compose.onNodeWithTag("edit-qty").performScrollTo().performTextInput("3")
        compose.onNodeWithTag("edit-qty").performImeAction()
        assertEquals(listOf("save ${ItemChanges(amount = "3")}"), events)
    }

    @Test
    fun keyboardSaveRequiresTheSameNameAsTheSaveButton() {
        show()
        compose.onNodeWithTag("edit-name").performTextReplacement("")
        compose.onNodeWithTag("edit-qty").performTextReplacement("3")
        compose.onNodeWithTag("save-item").assertIsNotEnabled()
        compose.onNodeWithTag("edit-qty").performImeAction()
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun clearingAnExistingAmountIsValidatedRatherThanTreatedAsUnchanged() {
        compose.setContent {
            HyperShopTheme(dynamicColor = false) {
                ItemSheetContent(
                    SHEET.copy(amount = "400 g"),
                    ItemSheetActions(onSave = { ch ->
                        events += "save $ch"
                        ch.amount?.let { com.robert4lyf.hypershop.core.parse.QuantityInput.parse(it) != null } ?: true
                    }),
                    initiallyEditing = true,
                )
            }
        }
        compose.onNodeWithTag("edit-qty").performTextReplacement("")
        compose.onNodeWithTag("save-item").performClick()
        compose.onNodeWithText("Enter an amount, like 2 or 200 ml").assertExists()
        assertEquals(listOf("save ${ItemChanges(amount = "")}"), events)
        compose.onNodeWithTag("edit-qty").performTextReplacement("500 g")
        compose.onNodeWithTag("save-item").performClick()
        assertEquals(listOf("save ${ItemChanges(amount = "")}", "save ${ItemChanges(amount = "500 g")}"), events)
    }

    @Test
    fun recordLight() {
        show()
        compose.onRoot().captureRoboImage(Shots.path("item-sheet-light.png"))
    }

    @Test
    fun recordDark() {
        show(dark = true)
        compose.onRoot().captureRoboImage(Shots.path("item-sheet-dark.png"))
    }

    @Test
    fun swapsAskJustThisTimeOrAlways() {
        show(editing = false)
        compose.onNodeWithTag("item-more").performScrollTo().performClick()
        compose.onNodeWithTag("swap-shallot").performScrollTo().performClick()
        compose.onNodeWithText("Always").performClick()
        compose.onNodeWithTag("swap-other").performScrollTo().performTextInput("leek")
        compose.onNodeWithText("Swap").performScrollTo().performClick()
        // Something typed isn't in the swaps, so only "Just this time" is offered, and Cancel backs out.
        compose.onNodeWithText("Always").assertDoesNotExist()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Just this time").assertDoesNotExist()
        compose.onNodeWithText("Swap").performScrollTo().performClick()
        compose.onNodeWithText("Just this time").performClick()
        assertEquals(listOf("swap shallot true", "swap leek false"), events)
    }
}
