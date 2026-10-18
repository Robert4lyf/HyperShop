package com.robert4lyf.hypershop.ui.review

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.testutil.Shots
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.testutil.await
import com.robert4lyf.hypershop.testutil.readyJob
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
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
        assertEquals("milk: +200 ml → 700 ml on list", vm.sheet("cat:milk")!!.effect)
        assertEquals("1 × 4 pint (2.27 l) bottle", vm.sheet("cat:milk")!!.packNote)
        assertEquals("200 ml milk", vm.sheet("cat:milk")!!.lines.single().raw)
    }

    @Test
    fun iHaveSomeSubtracts() {
        val (_, vm) = vm("500 ml milk")
        assertFalse(vm.haveSome("cat:milk", "lots"))
        assertTrue(vm.haveSome("cat:milk", "200 ml"))
        val ui = await(vm.state) { it is ReviewState.Ready && it.ui().row("cat:milk").quantity == "300 ml" }.ui()
        assertEquals("300 ml", ui.row("cat:milk").quantity)
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
    fun editTheSourceLine() {
        val (_, vm) = vm("2 onoins")
        assertEquals("2 red onion", vm.reparse("2 red onions").replace("onions", "onion"))
        val id = vm.sheet("name:onoins")!!.lines.single().id
        vm.editLine(id, "2 red onions")
        await(vm.state) { s -> s is ReviewState.Ready && s.ui().aisles.flatMap { it.second }.any { it.key == "cat:red_onion" } }
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
            effect = "milk: +200 ml → 700 ml on list",
            packNote = "1 × 4 pint (2.27 l) bottle",
            lines = listOf(SheetLine(7, "200ml whole milk or oat milk", "200 ml whole milk (or oat milk)", "oat milk")),
            aisle = AisleCode.DAIRY_EGGS,
            inStock = false,
            isStaple = false,
        )
    private val events = mutableListOf<String>()
    private val actions =
        SheetActions(
            onHaveSome = {
                events += "have $it"
                true
            },
            onAlwaysInStock = { events += "always" },
            onSwap = { events += "swap $it" },
            onMoveAisle = { a, always -> events += "move $a $always" },
            onEditLine = { id, t -> events += "edit $id $t" },
            reparse = { "parsed:$it" },
        )

    private fun show(dark: Boolean = false) =
        compose.setContent { HyperShopTheme(dark = dark, dynamicColor = false) { ReviewRowSheet(sheet, actions) } }

    @Test
    fun actions() {
        show()
        compose.onNodeWithText("milk: +200 ml → 700 ml on list").fetchSemanticsNode()
        compose.onNodeWithText("Usually sold as 1 × 4 pint (2.27 l) bottle").fetchSemanticsNode()
        compose.onNodeWithText("Use oat milk instead").performClick()
        compose.onNodeWithText("Always in stock").performClick()
        compose.onNodeWithTag("have-some").performTextReplacement("1 l")
        compose.onNodeWithText("Subtract").performClick()
        compose.onNodeWithTag("line-7").performTextReplacement("300ml milk")
        compose.onNodeWithText("Read as: parsed:300ml milk").fetchSemanticsNode()
        compose.onNodeWithText("Use this line").performClick()
        compose.onNodeWithText("Frozen").performClick()
        compose.onNodeWithText("Just this time").performClick()
        assertEquals(listOf("swap 7", "always", "have 1 l", "edit 7 300ml milk", "move FROZEN false"), events)
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
