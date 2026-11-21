package com.robert4lyf.hypershop.ui.list

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.data.Regular
import com.robert4lyf.hypershop.testutil.Shots
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private fun regular(name: String) = Regular("name:$name", name, QtyBase.PRESENCE, null, pinned = false, trips = 2, onList = false)

/** Step AB: the Add field's usual items, shown while it is focused and empty. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class AddFieldChipsTest {
    @get:Rule val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private val usual =
        UsualItems(
            listOf("toothpaste", "kitchen roll", "bin bags", "shampoo", "batteries", "foil", "cat food", "tea", "coffee").map(::regular),
            lastShop = 5,
        )

    private fun show(
        u: UsualItems = usual,
        dark: Boolean = false,
    ) = compose.setContent {
        HyperShopTheme(dark = dark, dynamicColor = false) {
            AddItemField(
                onAdd = { events += "add $it" },
                suggestions = { q -> if (q.startsWith("to")) listOf("toothpaste") else emptyList() },
                usual = u,
                onUsual = { events += "usual ${it.name}" },
                onLastShop = { events += "last shop" },
                onDontSuggest = { events += "hide ${it.name}" },
            )
        }
    }

    @Test
    fun chipsShowOnFocusTypingHandsOverToSuggestionsAndAChipKeepsTheFocus() {
        show()
        compose.onNodeWithTag("usual-chips").assertDoesNotExist()
        compose.onNodeWithTag("add-item").performClick()
        compose.onNodeWithTag("usual-chips").assertExists()
        // Seven regulars and last shop's chip, last: eight.
        compose.onNodeWithTag("usual-name:tea").assertDoesNotExist()
        compose.onNodeWithTag("usual-last-shop").assertExists()
        compose.onNodeWithTag("usual-name:toothpaste").performClick()
        compose.onNodeWithTag("add-item").assertIsFocused()
        compose.onNodeWithTag("usual-last-shop").performClick()
        // Typing: the typed suggestions, not the chips.
        compose.onNodeWithTag("add-item").performTextInput("to")
        compose.onNodeWithTag("usual-chips").assertDoesNotExist()
        compose.onNodeWithTag("suggestion-toothpaste").assertExists()
        compose.onNodeWithTag("add-item").performTextClearance()
        compose.onNodeWithTag("usual-chips").assertExists()
        assertEquals(listOf("usual toothpaste", "last shop"), events)
    }

    @Test
    fun longPressOrTalkBackOffersDontSuggest() {
        show()
        compose.onNodeWithTag("add-item").performClick()
        compose.onNodeWithTag("usual-name:shampoo").performTouchInput { longClick() }
        compose.onNodeWithTag("dont-suggest").performClick()
        val action =
            compose.onNodeWithTag("usual-name:foil").fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
        assertEquals("Don't suggest", action.label)
        compose.runOnIdle { action.action() }
        assertEquals(listOf("hide shampoo", "hide foil"), events)
    }

    @Test
    fun withNothingUsualThereIsNoChipAndNoWordAboutThem() {
        show(UsualItems())
        compose.onNodeWithTag("add-item").performClick()
        compose.onNodeWithTag("usual-chips").assertDoesNotExist()
    }

    @Test
    fun recordLight() {
        show()
        compose.onNodeWithTag("add-item").performClick()
        compose.onRoot().captureRoboImage(Shots.path("add-chips-light.png"))
    }

    @Test
    fun recordDark() {
        show(dark = true)
        compose.onNodeWithTag("add-item").performClick()
        compose.onRoot().captureRoboImage(Shots.path("add-chips-dark.png"))
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi", fontScale = 2f)
    fun recordAtTwiceTheFontSize() {
        show()
        compose.onNodeWithTag("add-item").performClick()
        compose.onRoot().captureRoboImage(Shots.path("add-chips-large.png"))
    }
}
