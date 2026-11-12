package com.robert4lyf.hypershop.ui.list

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
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
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val FLOUR = ItemSheet(1, "flour", listOf("400 g"), emptyList(), null, AisleCode.BAKING, amount = "400 g")

/**
 * The editor's unit chips: an exact conversion where one exists, otherwise an amount typed for the new unit,
 * never the old number carried over as if it were one. A change only edits the draft; Save keeps it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UnitChangeTest {
    @get:Rule val compose = createComposeRule()

    private val saves = mutableListOf<ItemChanges>()

    private fun show(
        sheet: ItemSheet = FLOUR,
        dark: Boolean = false,
    ) = compose.setContent {
        HyperShopTheme(dark = dark, dynamicColor = false) {
            ItemSheetContent(
                sheet,
                ItemSheetActions(onSave = {
                    saves += it
                    true
                }),
                initiallyEditing = true,
            )
        }
    }

    private fun chip(unit: String) {
        if (compose.onAllNodesWithTagCount("unit-$unit") == 0) compose.onNodeWithTag("more-units").performScrollTo().performClick()
        compose.onNodeWithTag("unit-$unit").performScrollTo().performClick()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    @Test
    fun compatibleMeasuresConvertExactlyWithoutAsking() {
        show()
        chip("kg")
        compose.onNodeWithTag("unit-amount-dialog").assertDoesNotExist()
        compose.onNodeWithTag("edit-qty").assertTextContains("0.4 kg")
        chip("g")
        compose.onNodeWithTag("edit-qty").assertTextContains("400 g")
        compose.onNodeWithTag("edit-qty").performTextReplacement("250 ml")
        chip("l")
        compose.onNodeWithTag("edit-qty").assertTextContains("0.25 l")
        assertEquals(emptyList<ItemChanges>(), saves)
    }

    @Test
    fun anIncompatibleMeasureAsksForTheAmountWithNothingSuggested() {
        show()
        chip("ml")
        compose.onNodeWithText("There is no conversion from g to ml. Enter the amount you need.").assertExists()
        compose.onNodeWithTag("unit-amount").assertTextContains("")
        compose.onNodeWithTag("unit-amount-unit").assertTextEquals("ml")
        compose.onNodeWithText("400 ml").assertDoesNotExist()
        compose.onNodeWithTag("unit-amount-use").assertIsNotEnabled()
        compose.onNodeWithTag("unit-amount").performTextInput("300")
        compose.onNodeWithTag("unit-amount-use").assertIsEnabled().assertTextEquals("Use 300 ml").performClick()
        // The draft only: nothing is saved until Save.
        compose.onNodeWithTag("edit-qty").assertTextContains("300 ml")
        assertEquals(emptyList<ItemChanges>(), saves)
        compose.onNodeWithTag("save-item").performClick()
        assertEquals(listOf(ItemChanges(amount = "300 ml")), saves)
    }

    @Test
    fun aCountToKilogramsNeedsAnExplicitAmountAndKeepsFractionsExact() {
        show(FLOUR.copy(amount = "3", totals = listOf("3")))
        chip("kg")
        compose.onNodeWithTag("unit-amount").performTextInput("1/3")
        compose.onNodeWithText("Use 1/3 kg").performClick()
        compose.onNodeWithTag("edit-qty").assertTextContains("1/3 kg")
    }

    @Test
    fun invalidZeroAndNegativeAmountsAreKeptWithWhatToFix() {
        show()
        chip("ml")
        val field = compose.onNodeWithTag("unit-amount")
        for ((typed, error) in listOf(
            "0" to "Enter an amount above zero",
            "-2" to "Enter an amount above zero",
            "lots" to "Enter a number, like 2, 1.5 or 1/2",
            "2 cups" to "Enter a number, like 2, 1.5 or 1/2",
        )) {
            field.performTextReplacement(typed)
            field.assertTextContains(typed)
            compose.onNodeWithText(error).assertExists()
            compose.onNodeWithTag("unit-amount-use").assertIsNotEnabled()
        }
        // Done on the keyboard with nothing usable keeps the dialog and says why.
        field.performTextReplacement("")
        field.performImeAction()
        compose.onNodeWithText("Enter the amount in ml").assertExists()
        compose.onNodeWithTag("unit-amount-dialog").assertExists()
        field.performTextReplacement("1.5")
        field.performImeAction()
        compose.onNodeWithTag("unit-amount-dialog").assertDoesNotExist()
        compose.onNodeWithTag("edit-qty").assertTextContains("1.5 ml")
    }

    @Test
    fun cancelLeavesTheEditorAsItWas() {
        show()
        chip("ml")
        compose.onNodeWithTag("unit-amount").performTextInput("300")
        compose.onNodeWithTag("unit-amount-cancel").performClick()
        compose.onNodeWithTag("unit-amount-dialog").assertDoesNotExist()
        compose.onNodeWithTag("edit-qty").assertTextContains("400 g")
        compose.onNodeWithTag("save-item").assertIsNotEnabled()
        // Opened again, it starts empty: the cancelled number is not offered.
        chip("ml")
        compose.onNodeWithText("Use 300 ml").assertDoesNotExist()
    }

    @Test
    fun anOpenUnitQuestionAndItsTypedAmountSurviveRecreation() {
        val restore = StateRestorationTester(compose)
        restore.setContent {
            HyperShopTheme(dynamicColor = false) { ItemSheetContent(FLOUR, ItemSheetActions(onSave = { true }), initiallyEditing = true) }
        }
        chip("ml")
        compose.onNodeWithTag("unit-amount").performTextInput("0")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("unit-amount").assertTextContains("0")
        compose.onNodeWithText("Enter an amount above zero").assertExists()
        compose.onNodeWithTag("unit-amount").performTextReplacement("250")
        compose.onNodeWithText("Use 250 ml").performClick()
        compose.onNodeWithTag("edit-qty").assertTextContains("250 ml")
    }

    @Test
    fun theCurrentUnitAndItsAlternativesShowTheRestBehindMoreUnits() {
        show()
        for (u in listOf(ItemEdit.EACH, "g", "kg")) compose.onNodeWithTag("unit-$u").assertExists()
        for (u in listOf("ml", "l", "tin", "jar", "packet", "bunch")) compose.onNodeWithTag("unit-$u").assertDoesNotExist()
        compose.onNodeWithTag("more-units").performScrollTo().performClick()
        for (u in ItemEdit.UNITS) compose.onNodeWithTag("unit-$u").assertExists()
        compose.onNodeWithText("Fewer units").assertExists()
    }

    @Test
    fun proposalRules() {
        assertEquals("2 kg", ItemEdit.propose("2", "kg").text)
        assertEquals("1 ½ kg", ItemEdit.propose(" 1 ½ ", "kg").text)
        assertEquals("3", ItemEdit.propose("3", ItemEdit.EACH).text)
        for (bad in listOf("", "0", "0.0", "-1", "abc", "2kg", "1/0", "..")) assertNull(bad, ItemEdit.propose(bad, "kg").text)
        assertEquals(listOf(ItemEdit.EACH, "g", "kg"), ItemEdit.commonUnits("kg"))
        assertEquals(listOf(ItemEdit.EACH, "ml", "l"), ItemEdit.commonUnits("ml"))
        assertEquals(listOf(ItemEdit.EACH, "g", "ml"), ItemEdit.commonUnits(ItemEdit.EACH))
        assertEquals(listOf(ItemEdit.EACH, "jar"), ItemEdit.commonUnits("jar"))
    }

    @Test
    fun recordDialogLight() {
        show()
        chip("ml")
        compose.onNodeWithTag("unit-amount").performTextInput("0")
        compose.waitForIdle()
        compose.onNodeWithTag("unit-amount-dialog").captureRoboImage(Shots.path("unit-change-light.png"))
    }

    @Test
    fun recordEditorDarkLargeText() {
        // The phone's own font scale, so dialogs (their own windows) scale as well.
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        show(dark = true)
        compose.onRoot().captureRoboImage(Shots.path("item-editor-dark-large.png"))
    }

    @Test
    fun recordDialogDarkLargeText() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        show(dark = true)
        chip("ml")
        compose.onNodeWithTag("unit-amount").performTextInput("300")
        compose.waitForIdle()
        compose.onNodeWithTag("unit-amount-dialog").captureRoboImage(Shots.path("unit-change-dark-large.png"))
    }
}
