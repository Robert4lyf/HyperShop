package com.robert4lyf.hypershop.ui.library

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.robert4lyf.hypershop.core.catalog.DisplayPolicy
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.testutil.Shots
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val UI =
    LibraryUi(
        rows =
            listOf(
                LibraryRow("basil", "Basil", AisleCode.PRODUCE, "Also: sweet basil · 1 × 30 g pack", edited = false, custom = false),
                LibraryRow("milk", "Milk", AisleCode.CHILLED, "Also: whole milk, semi-skimmed milk", edited = true, custom = false),
                LibraryRow("user_gochugaru", "Gochugaru", AisleCode.WORLD_FOODS, null, edited = false, custom = true),
            ),
        total = 456,
    )

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LibraryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun openSearchAndAdd() {
        val events = mutableListOf<String>()
        compose.setContent {
            HyperShopTheme {
                LibraryScreen(
                    UI,
                    onBack = {},
                    onSearch = { events += "search $it" },
                    onOpen = { events += "open $it" },
                    onAdd = { events += "add" },
                )
            }
        }
        compose.onNodeWithText("Yours").assertExists()
        compose.onNodeWithText("Edited").assertExists()
        compose.onNodeWithTag("entry-milk").performClick()
        compose.onNodeWithTag("library-search").performTextInput("ba")
        compose.onNodeWithTag("add-ingredient").performClick()
        assertEquals(listOf("open milk", "search ba", "add"), events)
    }

    @Test
    fun theEditorSavesWhatYouTyped() {
        var saved: EntryForm? = null
        compose.setContent {
            HyperShopTheme {
                EntryEditor(EntryForm(), onSave = {
                    saved = it
                    null
                }, onReset = {})
            }
        }
        compose.onNodeWithTag("entry-name").performTextInput("Gochugaru")
        compose.onNodeWithTag("entry-synonyms").performTextInput("korean chilli flakes")
        compose.onNodeWithText(AisleCode.WORLD_FOODS.displayName).performScrollTo().performClick()
        compose.onNodeWithText("Weight").performScrollTo().performClick()
        compose.onNodeWithTag("entry-save").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(
            EntryForm(name = "Gochugaru", synonyms = "korean chilli flakes", aisle = AisleCode.WORLD_FOODS, policy = DisplayPolicy.MASS),
            saved,
        )
    }

    @Test
    fun recordLight() {
        compose.setContent { HyperShopTheme { LibraryScreen(UI, onBack = {}, onSearch = {}, onOpen = {}, onAdd = {}) } }
        compose.onRoot().captureRoboImage(Shots.path("library-light.png"))
    }

    @Test
    fun recordDark() {
        compose.setContent { HyperShopTheme(dark = true) { LibraryScreen(UI, onBack = {}, onSearch = {}, onOpen = {}, onAdd = {}) } }
        compose.onRoot().captureRoboImage(Shots.path("library-dark.png"))
    }

    @Test
    fun recordEditor() {
        compose.setContent {
            HyperShopTheme {
                EntryEditor(
                    EntryForm(
                        "milk",
                        "milk",
                        "milk",
                        "whole milk, semi-skimmed milk",
                        AisleCode.DAIRY_EGGS,
                        DisplayPolicy.VOLUME,
                        "4 pint bottle",
                        edited = true,
                    ),
                    onSave = { null },
                    onReset = {},
                )
            }
        }
        compose.onRoot().captureRoboImage(Shots.path("library-editor-light.png"))
    }
}
