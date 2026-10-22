package com.robert4lyf.hypershop.ui.review

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private fun ReviewState.ui() = (this as ReviewState.Ready).ui

private fun ReviewUi.row(key: String) = aisles.flatMap { it.second }.first { it.key == key }

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
        assertEquals("milk: +200ml → 700ml on list", vm.sheet("cat:milk")!!.effect)
        assertEquals("1 × 4 pint (2.27l) bottle", vm.sheet("cat:milk")!!.packNote)
        assertEquals("200 ml milk", vm.sheet("cat:milk")!!.lines.single().raw)
    }

    @Test
    fun alwaysInStockMakesAStaple() {
        val (c, vm) = vm("1 tsp ground cumin")
        vm.alwaysInStock("cat:cumin")
        await(vm.state) { it is ReviewState.Ready && it.ui().row("cat:cumin").inStock }
        assertTrue("cat:cumin" in runBlocking { c.repository.staples() })
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
    fun theAmountIsSetFromTypedText() {
        val (_, vm) = vm("2 onions")
        // The recipe is stored again after each change, so the sheet's line ids are read afresh.
        val id = { vm.sheet("cat:onion")!!.lines.single().id }
        assertEquals("2", vm.sheet("cat:onion")!!.lines.single().amount)
        assertFalse(vm.setAmount(id(), "a few"))
        assertTrue(vm.setAmount(id(), "5"))
        await(vm.state) { it is ReviewState.Ready && it.ui().row("cat:onion").quantity == "5" }
        assertTrue(vm.setAmount(id(), "500 g"))
        await(vm.state) { it is ReviewState.Ready && it.ui().row("cat:onion").quantity == "500g" }
        assertEquals("500 g", vm.sheet("cat:onion")!!.lines.single().amount)
    }

    @Test
    fun aSwapOrARenameSettlesAFlaggedLine() {
        val (_, vm) = vm("4 tablespoons butter or margarine", "1 lemon or lime")
        assertTrue(vm.sheet("cat:butter")!!.lines.single().checkReason != null)
        vm.swap(vm.sheet("cat:butter")!!.lines.single().id)
        val ui = await(vm.state) { s -> s is ReviewState.Ready && s.ui().row("cat:vegan_butter", or = null) != null }.ui()
        assertFalse(ui.row("cat:vegan_butter").check)
        assertTrue(ui.row("cat:lemon").check)
        vm.rename("cat:lemon", "lime")
        val renamed = await(vm.state) { s -> s is ReviewState.Ready && s.ui().row("cat:lime", or = null) != null }.ui()
        assertFalse(renamed.row("cat:lime").check)
        assertEquals(0, renamed.checkCount)
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
        val (_, vm) = vm("1 lemon or lime")
        val row = (vm.state.value as ReviewState.Ready).ui.row("cat:lemon")
        assertEquals("1 lemon or lime", row.raw)
        assertEquals(
            "the recipe offers a choice: swap to the other on the sheet if you'd rather",
            ReviewViewModel.plainReason(row.checkReason!!),
        )
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
            effect = "milk: +200ml → 700ml on list",
            packNote = "1 × 4 pint (2.27 l) bottle",
            lines =
                listOf(
                    SheetLine(
                        7,
                        "200ml whole milk or oat milk",
                        "200 ml whole milk (or oat milk)",
                        "oat milk",
                        "\"A or B\": one of two",
                        amount = "200 ml",
                    ),
                ),
            aisle = AisleCode.DAIRY_EGGS,
            inStock = false,
            isStaple = false,
        )
    private val events = mutableListOf<String>()
    private val actions =
        SheetActions(
            onAlwaysInStock = { events += "always" },
            onSwap = { events += "swap $it" },
            onMoveAisle = { a, always -> events += "move $a $always" },
            onEditLine = { id, t -> events += "edit $id $t" },
            reparse = { "parsed:$it" },
            onRename = { events += "rename $it" },
            onSetAmount = { id, t ->
                events += "amount $id $t"
                true
            },
            onInStock = { events += "stock $it" },
            renamePreview = { "preview:$it" },
        )

    private fun show(dark: Boolean = false) =
        compose.setContent { HyperShopTheme(dark = dark, dynamicColor = false) { ReviewRowSheet(sheet, actions) } }

    @Test
    fun actions() {
        show()
        compose.onNodeWithText("milk: +200ml → 700ml on list").fetchSemanticsNode()
        compose.onNodeWithText("Usually sold as 1 × 4 pint (2.27 l) bottle").fetchSemanticsNode()
        compose.onNodeWithText("Use oat milk instead").performClick()
        compose.onNodeWithText("Always at home").performClick()
        compose.onNodeWithTag("line-7").performTextReplacement("300ml milk")
        compose.onNodeWithText("Read as: parsed:300ml milk").fetchSemanticsNode()
        compose.onNodeWithText("Use this line").performClick()
        compose.onNodeWithTag("amount-7").performTextReplacement("300 ml")
        compose.onNodeWithText("Set").performClick()
        // A chip asks: just this time, or always.
        compose.onNodeWithText("Frozen").performScrollTo().performClick()
        compose.onNodeWithText("Just this time").performClick()
        compose.onNodeWithText("Chilled").performScrollTo().performClick()
        compose.onNodeWithText("Always").performClick()
        compose.onNodeWithTag("switch-At home").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("switch-At home").performClick()
        compose.onNodeWithTag("rename").performTextReplacement("Oat milk")
        compose.onNodeWithTag("rename-preview").assertTextEquals("preview:Oat milk")
        compose.onNodeWithTag("rename-go").performClick()
        assertEquals(
            listOf(
                "swap 7",
                "always",
                "edit 7 300ml milk",
                "amount 7 300 ml",
                "move FROZEN false",
                "move CHILLED true",
                "stock true",
                "rename Oat milk",
            ),
            events,
        )
    }

    @Test
    fun theLastLineCannotBeRemoved() {
        compose.setContent { HyperShopTheme(dark = false, dynamicColor = false) { ReviewRowSheet(sheet.copy(canRemove = false), actions) } }
        compose.onNodeWithText("Remove from recipe").assertDoesNotExist()
        compose.onNodeWithText("Check: the recipe offers a choice: swap to the other on the sheet if you'd rather").fetchSemanticsNode()
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
