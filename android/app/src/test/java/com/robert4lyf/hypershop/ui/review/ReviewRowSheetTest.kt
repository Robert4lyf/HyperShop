package com.robert4lyf.hypershop.ui.review

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.testutil.Shots
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.testutil.await
import com.robert4lyf.hypershop.testutil.readyJob
import com.robert4lyf.hypershop.ui.common.UiMessage
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private fun ReviewState.ui() = (this as ReviewState.Ready).ui

private fun ReviewUi.row(key: String) = aisles.flatMap { it.second }.first { it.key == key }

/** Rows flagged to check and not at home. */
private fun ReviewUi.toCheck() = aisles.flatMap { it.second }.count { it.check && !it.inStock }

/** The row, or [or] when the key is not on screen (yet). */
private fun ReviewUi.row(
    key: String,
    or: ReviewRow?,
) = aisles.flatMap { it.second }.firstOrNull { it.key == key } ?: or

/** Step 5.5: the row sheet. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReviewRowSheetViewModelTest {
    private fun vm(vararg lines: String): Pair<com.robert4lyf.hypershop.di.AppContainer, ReviewViewModel> {
        val c = TestContainer.create()
        val vm = ReviewViewModel(c, runBlocking { c.readyJob(*lines) })
        await(vm.state) { it is ReviewState.Ready }
        return c to vm
    }

    @Test
    fun effectShowsWhatMergingWillDo() {
        val (c, vm0) = vm("500 ml milk")
        vm0.confirm()
        await(vm0.state) { it is ReviewState.Added }
        val vm = ReviewViewModel(c, runBlocking { c.readyJob("200 ml milk") })
        await(vm.state) { it is ReviewState.Ready }
        // Step AA: no "+"; what the list will hold, in words.
        assertEquals("milk: 200ml · 700ml with what's on your list", vm.sheet("cat:milk")!!.effect)
        val row = (vm.state.value as ReviewState.Ready).ui.row("cat:milk")
        assertEquals("200ml · 700ml with what's on your list", row.withList)
        assertEquals("4 pint (2.27l) bottle", vm.sheet("cat:milk")!!.packNote)
        assertEquals("200 ml milk", vm.sheet("cat:milk")!!.lines.single().raw)
    }

    @Test
    fun anAOrBLineIsTwoOptionsNotACheck() {
        // Step AA: the row shows "Lemon" and "Lime" as buttons; no "Check: …" line, here or on the sheet.
        val (_, vm) = vm("1 lemon or lime")
        val row = (vm.state.value as ReviewState.Ready).ui.row("cat:lemon")
        assertFalse(row.check)
        val choice = row.choice!!
        assertEquals("lemon" to "lime", choice.chosen to choice.other)
        assertNull(vm.sheet("cat:lemon")!!.lines.single().checkReason)
        vm.swap(choice.lineId)
        val ui = await(vm.state) { s -> s is ReviewState.Ready && s.ui().aisles.flatMap { it.second }.none { it.key == "cat:lemon" } }.ui()
        val swapped = ui.aisles.flatMap { it.second }.single()
        assertEquals("lime" to "lemon", swapped.choice!!.chosen to swapped.choice!!.other)
    }

    @Test
    fun swapAOrB() {
        val (_, vm) = vm("4 tablespoons butter or margarine")
        val line = vm.sheet("cat:butter")!!.lines.single()
        assertEquals("margarine", line.alternative)
        vm.swap(line.id)
        val ui = await(vm.state) { s -> s is ReviewState.Ready && s.ui().aisles.flatMap { it.second }.none { it.key == "cat:butter" } }.ui()
        assertEquals("cat:vegan_butter", ui.aisles.flatMap { it.second }.single().key)
    }

    @Test
    fun moveAisleJustThisTimeOrAlways() {
        val (c, vm) = vm("2 onions")
        vm.moveAisle("cat:onion", AisleCode.FROZEN, always = false)
        await(vm.state) { it is ReviewState.Ready && it.ui().row("cat:onion").aisle == AisleCode.FROZEN }
        assertTrue(runBlocking { c.repository.overrides() }.isEmpty())
        // The latest choice wins: "always" replaces the earlier "just this time".
        vm.moveAisle("cat:onion", AisleCode.WORLD_FOODS, always = true)
        await(vm.state) { it is ReviewState.Ready && it.ui().row("cat:onion").aisle == AisleCode.WORLD_FOODS }
        assertEquals(mapOf("cat:onion" to AisleCode.WORLD_FOODS), runBlocking { c.repository.overrides() })
    }

    @Test
    fun renameIsForThisRecipeOnly() {
        val (c, vm) = vm("2 tbsp agave syrup", "1 tsp agave nectar", "2 onions")
        vm.setInStock("cat:agave_syrup", true)
        vm.rename("cat:agave_syrup", "Maple syrup")
        val keys = { s: ReviewState -> (s as? ReviewState.Ready)?.ui?.aisles?.flatMap { it.second }?.map { it.key }.orEmpty() }
        val ui = await(vm.state) { "cat:maple_syrup" in keys(it) }.ui()
        // Both lines moved, and the row's "in stock" went with them.
        assertEquals(2, vm.sheet("cat:maple_syrup")!!.lines.size)
        assertTrue(ui.row("cat:maple_syrup").inStock)
        // A name the library doesn't know is an item of its own.
        vm.rename("cat:onion", "Banana shallots from the market")
        await(vm.state) { s -> s is ReviewState.Ready && "cat:onion" !in keys(s) }
        // The library is untouched.
        assertEquals("agave syrup", c.catalog.byId.getValue("agave_syrup").name)
        assertEquals(null, c.catalog.byId.getValue("agave_syrup").replaceWith)
    }

    @Test
    fun editTheSourceLine() {
        val (_, vm) = vm("2 onoins")
        assertEquals("2 red onion", vm.reparse("2 red onions").replace("onions", "onion"))
        val id = vm.sheet("name:onoins")!!.lines.single().id
        var newKey: String? = null
        vm.editLine(id, "2 red onions") { newKey = it }
        await(vm.state) { s -> s is ReviewState.Ready && s.ui().aisles.flatMap { it.second }.any { it.key == "cat:red_onion" } }
        // The sheet follows the line to its new row.
        assertEquals("cat:red_onion", newKey)
        assertEquals("Joins maple syrup in Tins & Jars", vm.renamePreview("Maple syrup"))
        assertEquals("A new item in Other", vm.renamePreview("Banana shallots from the market"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReviewCorrectionsTest {
    private fun vm(vararg lines: String): Pair<com.robert4lyf.hypershop.di.AppContainer, ReviewViewModel> {
        val c = TestContainer.create()
        val vm = ReviewViewModel(c, runBlocking { c.readyJob(*lines) })
        await(vm.state) { it is ReviewState.Ready }
        return c to vm
    }

    private fun ReviewViewModel.watch(): Pair<MutableList<UiMessage>, Job> {
        val got = mutableListOf<UiMessage>()
        return got to CoroutineScope(Dispatchers.Unconfined).launch { events.collect { got += it } }
    }

    @Test
    fun aRemovedLineComesBackWithUndo() {
        val (_, vm) = vm("2 onions", "1 carrot")
        val (got, watching) = vm.watch()
        vm.removeLine(vm.sheet("cat:onion")!!.lines.single().id)
        await(vm.state) { s -> s is ReviewState.Ready && s.ui().aisles.flatMap { it.second }.none { it.key == "cat:onion" } }
        assertEquals("Removed Onion from this recipe", got.single().text)
        runBlocking { got.single().undo!!.invoke() }
        val ui = await(vm.state) { s -> s is ReviewState.Ready && s.ui().aisles.flatMap { it.second }.any { it.key == "cat:onion" } }.ui()
        // Back where it was, before the carrot.
        assertEquals(listOf("cat:onion", "cat:carrot"), ui.aisles.flatMap { it.second }.map { it.key })
        assertEquals("2", ui.row("cat:onion").quantity)
        watching.cancel()
    }

    @Test
    fun aSwapIsOneTapBeforeAnythingIsBought() {
        // X.4: the catalogue's usual swaps are on the review's sheet; one becomes the line, for this recipe only.
        val (_, vm) = vm("1 tsp dried thyme")
        val swaps = vm.sheet("cat:dried_thyme")!!.swaps
        assertTrue(swaps.toString(), "dried oregano" in swaps)
        vm.rename("cat:dried_thyme", "dried oregano")
        await(vm.state) { it is ReviewState.Ready && it.ui().row("cat:dried_oregano", null) != null }
    }

    @Test
    fun theAmountIsSetFromTypedText() {
        val (_, vm) = vm("2 onions")
        // The recipe is stored again after each change, so the sheet's line ids are read afresh.
        val id = { vm.sheet("cat:onion")!!.lines.single().id }
        assertEquals("2", vm.sheet("cat:onion")!!.lines.single().amount)
        assertFalse(vm.setAmount(id(), "a few"))
        assertTrue(vm.setAmount(id(), "5"))
        await(vm.state) { it is ReviewState.Ready && it.ui().row("cat:onion").quantity == "5" }
        assertTrue(vm.setAmount(id(), "1 kg"))
        // The row says what the list will: a kilo of onions is seven UK ones (150 g each), the line keeps the kilo.
        await(vm.state) { it is ReviewState.Ready && it.ui().row("cat:onion").quantity == "7" }
        assertEquals("1 kg", vm.sheet("cat:onion")!!.lines.single().amount)
    }

    @Test
    fun aTypedAmountSettlesAnUnknownMeasure() {
        val (_, vm) = vm("2 handfuls of spinach")
        val key = (vm.state.value as ReviewState.Ready).ui.aisles.flatMap { it.second }.single().key
        assertTrue(vm.sheet(key)!!.lines.single().checkReason!!.contains("unit"))
        assertTrue(vm.setAmount(vm.sheet(key)!!.lines.single().id, "100 g"))
        val ui = await(vm.state) { s -> s is ReviewState.Ready && s.ui().row(key, or = null)?.quantity == "100g" }.ui()
        assertFalse(ui.row(key).check)
    }

    @Test
    fun aSwapOrARenameSettlesAFlaggedLine() {
        // Step AA: an "A or B" line is never "to check" (its two options say it); a swap or a rename keeps it so.
        val (_, vm) = vm("4 tablespoons butter or margarine", "1 lemon or lime")
        assertEquals(0, (vm.state.value as ReviewState.Ready).ui.toCheck())
        vm.swap(vm.sheet("cat:butter")!!.lines.single().id)
        val ui = await(vm.state) { s -> s is ReviewState.Ready && s.ui().row("cat:vegan_butter", or = null) != null }.ui()
        assertFalse(ui.row("cat:vegan_butter").check)
        assertFalse(ui.row("cat:lemon").check)
        vm.rename("cat:lemon", "lime")
        val renamed = await(vm.state) { s -> s is ReviewState.Ready && s.ui().row("cat:lime", or = null) != null }.ui()
        assertFalse(renamed.row("cat:lime").check)
        assertEquals(0, renamed.toCheck())
    }

    @Test
    fun anAisleMoveIsAnnouncedWithUndo() {
        val (c, vm) = vm("2 onions")
        val (got, watching) = vm.watch()
        vm.moveAisle("cat:onion", AisleCode.FROZEN, always = false)
        await(vm.state) { it is ReviewState.Ready && it.ui().row("cat:onion").aisle == AisleCode.FROZEN }
        assertEquals("Onion moved to Frozen", got.single().text)
        runBlocking { got.single().undo!!.invoke() }
        await(vm.state) { it is ReviewState.Ready && it.ui().row("cat:onion").aisle == AisleCode.PRODUCE }
        vm.moveAisle("cat:onion", AisleCode.WORLD_FOODS, always = true)
        await(vm.state) { it is ReviewState.Ready && it.ui().row("cat:onion").aisle == AisleCode.WORLD_FOODS }
        assertEquals("Onion moved to World Foods, from now on", got.last().text)
        assertEquals(null, got.last().undo)
        assertEquals(AisleCode.WORLD_FOODS, runBlocking { c.repository.overrides() }["cat:onion"])
        watching.cancel()
    }

    @Test
    fun flaggedRowsCarryTheRecipesOwnLineAndAPlainReason() {
        val (_, vm) = vm("3-2 eggs")
        val row = (vm.state.value as ReviewState.Ready).ui.aisles.flatMap { it.second }.single()
        assertTrue(row.check)
        assertEquals("3-2 eggs", row.raw)
        assertEquals("The recipe's range reads backwards", ReviewViewModel.plainReason(row.checkReason!!))
        // Step AC: a button for each reading, each setting the line's amount.
        assertEquals("3–2" to listOf("3", "2"), row.range!!.said to row.range!!.readings)
        assertEquals("Claude's own words", ReviewViewModel.plainReason("Claude's own words"))
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ReviewRowSheetScreenTest {
    @get:Rule val compose = createComposeRule()

    private val sheet =
        RowSheet(
            key = "cat:milk",
            name = "milk",
            effect = "milk: 200ml · 700ml with what's on your list",
            packNote = "1 × 4 pint (2.27 l) bottle",
            lines =
                listOf(
                    SheetLine(
                        7,
                        "200ml whole milk or oat milk",
                        "200 ml whole milk (or oat milk)",
                        "oat milk",
                        null,
                        amount = "200 ml",
                        name = "whole milk",
                    ),
                ),
            aisle = AisleCode.DAIRY_EGGS,
            inStock = false,
        )
    private val events = mutableListOf<String>()
    private val actions =
        SheetActions(
            onSwap = { events += "swap $it" },
            onMoveAisle = { a, always -> events += "move $a $always" },
            onEditLine = { id, t -> events += "edit $id $t" },
            reparse = { "parsed:$it" },
            onRename = { events += "rename $it" },
            onSetAmount = { id, t ->
                events += "amount $id $t"
                true
            },
            renamePreview = { "preview:$it" },
        )

    private fun show(dark: Boolean = false) =
        compose.setContent { HyperShopTheme(dark = dark, dynamicColor = false) { ReviewRowSheet(sheet, actions) } }

    @Test
    fun actions() {
        show()
        compose.onNodeWithText("milk: 200ml · 700ml with what's on your list").fetchSemanticsNode()
        compose.onNodeWithText("Usually sold as 1 × 4 pint (2.27 l) bottle").fetchSemanticsNode()
        // Step AA: the line's two options; the one it reads now does nothing, the other swaps.
        compose.onNodeWithTag("chosen-7").assertIsSelected().performClick()
        compose.onNodeWithText("Oat milk").assertIsNotSelected().performClick()
        compose.onNodeWithText("Amount in the recipe").fetchSemanticsNode()
        // Step Z: at home is said on the review's row, not here.
        compose.onNodeWithText("Always at home").assertDoesNotExist()
        compose.onNodeWithTag("switch-At home").assertDoesNotExist()
        // The line's words and name sit behind "Fix the recipe's text" (UX review 6; Step AA's words).
        compose.onNodeWithText("Fix the recipe's text").fetchSemanticsNode()
        compose.onNodeWithText("Report a wrong reading").performScrollTo().fetchSemanticsNode()
        compose.onNodeWithTag("edit-line").performScrollTo().performClick()
        compose.onNodeWithTag("line-7").performScrollTo().performTextReplacement("300ml milk")
        compose.onNodeWithText("Read as: parsed:300ml milk").fetchSemanticsNode()
        compose.onNodeWithText("Use this line").performClick()
        compose.onNodeWithTag("amount-7").performTextReplacement("300 ml")
        compose.onNodeWithText("Set").performClick()
        // Said under the field: the change itself may be a word on a row further up the screen.
        compose.onNodeWithText("Set to 300 ml").fetchSemanticsNode()
        // A chip asks: just this time, or always.
        // The aisles are behind "Change aisle".
        compose.onNodeWithText("Frozen").assertDoesNotExist()
        compose.onNodeWithTag("change-aisle").performScrollTo().performClick()
        compose.onNodeWithText("Frozen").performScrollTo().performClick()
        compose.onNodeWithText("Just this time").performClick()
        compose.onNodeWithText("Chilled").performScrollTo().performClick()
        compose.onNodeWithText("Always").performClick()
        compose.onNodeWithTag("rename").performTextReplacement("Oat milk")
        compose.onNodeWithTag("rename-preview").assertTextEquals("preview:Oat milk")
        compose.onNodeWithTag("rename-go").performClick()
        assertEquals(
            listOf(
                "swap 7",
                "edit 7 300ml milk",
                "amount 7 300 ml",
                "move FROZEN false",
                "move CHILLED true",
                "rename Oat milk",
            ),
            events,
        )
    }

    @Test
    fun theLastLineCannotBeRemoved() {
        compose.setContent { HyperShopTheme(dark = false, dynamicColor = false) { ReviewRowSheet(sheet.copy(canRemove = false), actions) } }
        compose.onNodeWithText("Remove from recipe").assertDoesNotExist()
        compose.onAllNodesWithText("Check:", substring = true).assertCountEquals(0)
    }

    @Test
    fun recordLight() {
        show()
        compose.onRoot().captureRoboImage(Shots.path("review-sheet-light.png"))
    }

    @Test
    fun recordDark() {
        show(dark = true)
        compose.onRoot().captureRoboImage(Shots.path("review-sheet-dark.png"))
    }
}
