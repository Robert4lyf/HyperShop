package com.robert4lyf.hypershop.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.testutil.Shots
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Step AC.2: an aisle opens to show its groups. Step AD.1: aisles and groups move by their drag handles, each within
 * its own level; TalkBack's Move up / down / to top / to bottom do the same; no arrows or ⋮ on the rows.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class WalkingOrderScreenTest {
    @get:Rule val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private val ui =
        SettingsUi(
            aisles = AisleCode.entries.toList(),
            groups = mapOf(AisleCode.PRODUCE to listOf("Vegetables", "Salad", "Herbs", "Fruit", null)),
            loaded = true,
        )

    private fun show(start: SettingsUi = ui) {
        var state by mutableStateOf(start)
        compose.setContent {
            HyperShopTheme(dynamicColor = false) {
                AislesScreen(
                    state,
                    SettingsActions(
                        onMoveAisleTo = { a, i ->
                            events += "aisle $a to $i"
                            state =
                                state.copy(
                                    aisles =
                                        state.aisles.toMutableList().apply {
                                            remove(a)
                                            add(i, a)
                                        },
                                )
                        },
                        onMoveGroup = { a, g, by -> events += "move $a $g $by" },
                        onMoveGroupTo = { a, g, i ->
                            events += "to $a $g $i"
                            val gs =
                                state.groups.getValue(a).toMutableList().apply {
                                    remove(g)
                                    add(i, g)
                                }
                            state = state.copy(groups = state.groups + (a to gs))
                        },
                    ),
                )
            }
        }
    }

    /** Hold the handle tagged [tag] and drag it [rows] rows of [rowPx] (negative: up), in small steps. */
    private fun drag(
        tag: String,
        dy: Float,
    ) {
        compose.onNodeWithTag(tag).performTouchInput {
            down(center)
            val steps = 20
            repeat(steps) {
                advanceEventTime(16)
                moveBy(Offset(0f, dy / steps))
            }
            advanceEventTime(16)
            up()
        }
        compose.waitForIdle()
    }

    private fun height(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height

    private fun custom(
        tag: String,
        label: String,
    ) {
        val actions = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.single { it.label == label }.action() }
    }

    private fun labels(tag: String) =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }

    @Test
    fun aGroupIsDraggedAboveTheOneBeforeItWithinItsAisle() {
        show()
        compose.onNodeWithTag("group-PRODUCE-Salad").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show the groups in Produce").performScrollTo().performClick()
        compose.onNodeWithTag("group-PRODUCE-Salad").performScrollTo()
        drag("drag-group-PRODUCE-Salad", -height("group-PRODUCE-Vegetables"))
        assertEquals(listOf("to PRODUCE Salad 0"), events)
        // The rows have no arrows or ⋮ any more: the handle, and TalkBack's moves.
        compose.onNodeWithTag("group-up-PRODUCE-Vegetables").assertDoesNotExist()
        compose.onNodeWithContentDescription("More for Salad").assertDoesNotExist()
        compose.onNodeWithContentDescription("Move Salad up").assertDoesNotExist()
        assertEquals(listOf("Move Salad down", "Move Salad to bottom"), labels("group-PRODUCE-Salad"))
        compose.onNodeWithText("Everything else").performScrollTo().assertExists()
        compose.onRoot().captureRoboImage(Shots.path("walking-order-groups-light.png"))
    }

    @Test
    fun aGroupDraggedFarStaysInItsAisle() {
        show()
        compose.onNodeWithContentDescription("Show the groups in Produce").performScrollTo().performClick()
        compose.onNodeWithTag("group-PRODUCE-Vegetables").performScrollTo()
        // Far below the aisle's last group: it goes last in Produce, never into the next aisle.
        drag("drag-group-PRODUCE-Vegetables", height("group-PRODUCE-Salad") * 12)
        assertEquals(listOf("to PRODUCE Vegetables 4"), events)
    }

    @Test
    fun anAisleIsDraggedDownPastTheNext() {
        show()
        val first = AisleCode.entries[0]
        val second = AisleCode.entries[1]
        drag("drag-${first.name}", height("aisle-row-${second.name}") + 4)
        assertEquals(listOf("aisle $first to 1"), events)
        assertTrue(
            "the moved aisle is drawn below the next",
            compose.onNodeWithTag("aisle-row-${first.name}").fetchSemanticsNode().boundsInRoot.top >
                compose.onNodeWithTag("aisle-row-${second.name}").fetchSemanticsNode().boundsInRoot.top,
        )
    }

    @Test
    fun aHeldAisleNearTheBottomScrollsTheScreen() {
        show()
        val before = compose.onNodeWithTag("aisles-back").getUnclippedBoundsInRoot().top.value
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot.height
        // Held at the screen's bottom edge for a while: the column scrolls, so the first aisle's place moves up.
        val handle = compose.onNodeWithTag("drag-${AisleCode.entries[0].name}")
        handle.performTouchInput {
            down(center)
            repeat(10) {
                advanceEventTime(16)
                moveBy(Offset(0f, screen / 10))
            }
        }
        // Still held, for a second of frames.
        compose.mainClock.advanceTimeBy(1_000)
        handle.performTouchInput { up() }
        compose.waitForIdle()
        val moved = events.singleOrNull()
        assertTrue("one move, to well past the first screen: $events", moved != null && moved.substringAfterLast(' ').toInt() > 3)
        // The top bar scrolled up off its place: the screen moved, not only the row.
        val after = compose.onNodeWithTag("aisles-back").getUnclippedBoundsInRoot().top.value
        assertTrue("scrolled: the back arrow went from $before to $after", after < before - 40f)
    }

    @Test
    fun talkBackMovesAnAisleAndAGroup() {
        show()
        val second = AisleCode.entries[1]
        custom("aisle-row-${second.name}", "Move ${second.displayName} up")
        custom("aisle-row-${second.name}", "Move ${second.displayName} to bottom")
        compose.onNodeWithContentDescription("Show the groups in Produce").performScrollTo().performClick()
        custom("group-PRODUCE-Herbs", "Move Herbs to top")
        assertEquals(
            listOf("aisle $second to 0", "aisle $second to ${AisleCode.entries.lastIndex}", "to PRODUCE Herbs 0"),
            events,
        )
        // The first row can't move up, the last can't move down.
        assertEquals(
            listOf("Move ${AisleCode.entries[0].displayName} down", "Move ${AisleCode.entries[0].displayName} to bottom"),
            labels("aisle-row-${AisleCode.entries[0].name}"),
        )
    }
}
