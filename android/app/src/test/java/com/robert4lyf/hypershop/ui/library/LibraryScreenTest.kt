package com.robert4lyf.hypershop.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
        loaded = true,
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
                    "",
                    onBack = {},
                    onSearch = { events += "search $it" },
                    onOpen = { events += "open $it" },
                    onAdd = { events += "add" },
                    onAisle = { events += "aisle $it" },
                )
            }
        }
        compose.onNodeWithText("Yours").assertExists()
        compose.onNodeWithText("Edited").assertExists()
        compose.onNodeWithText("456 ingredients").assertExists()
        compose.onNodeWithTag("entry-milk").performClick()
        compose.onNodeWithTag("library-search").performTextInput("ba")
        compose.onNodeWithTag("aisle-${AisleCode.PRODUCE.name}").performClick()
        compose.onNodeWithTag("add-ingredient").performClick()
        assertEquals(listOf("open milk", "search ba", "aisle ${AisleCode.PRODUCE}", "add"), events)
    }

    @Test
    fun nothingMatchesOnlyOnceLoadedAndSearching() {
        var query by mutableStateOf("")
        var loaded by mutableStateOf(false)
        compose.setContent {
            HyperShopTheme {
                LibraryScreen(LibraryUi(loaded = loaded), query, onBack = {}, onSearch = {}, onOpen = {}, onAdd = {})
            }
        }
        val copy = "Nothing matches"
        compose.onNodeWithText(copy).assertDoesNotExist()
        loaded = true
        compose.onNodeWithText(copy).assertDoesNotExist()
        query = "zz"
        compose.onNodeWithText(copy).assertExists()
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
        compose.onNodeWithText("Always at home").performScrollTo().performClick()
        compose.onNodeWithText("Always at home").assertIsOn()
        compose.onNodeWithText("Always at home").performClick()
        // The rarely-needed fields wait under "Advanced" for a new ingredient.
        compose.onNodeWithText("Weight").assertDoesNotExist()
        compose.onNodeWithTag("entry-advanced").performScrollTo().performClick()
        compose.onNodeWithText("Weight").performScrollTo().performClick()
        compose.onNodeWithTag("entry-save").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(
            EntryForm(name = "Gochugaru", synonyms = "korean chilli flakes", aisle = AisleCode.WORLD_FOODS, policy = DisplayPolicy.MASS),
            saved,
        )
    }

    @Test
    fun suggestionsFillTheSwapFieldsAndEditsClearTheError() {
        var cancelled = 0
        compose.setContent {
            HyperShopTheme {
                EntryEditor(
                    EntryForm(name = "agave syrup", swaps = "honey"),
                    onSave = { "No ingredient called \"x\"" },
                    onReset = {},
                    onCancel = { cancelled++ },
                    suggest = { typed -> listOf("maple syrup", "golden syrup").filter { it.startsWith(typed.trim()) } },
                )
            }
        }
        // Swaps has a value, so Advanced opens by itself.
        compose.onNodeWithTag("entry-swaps").performScrollTo().performTextReplacement("honey, ma")
        compose.onNodeWithTag("suggest-maple syrup").performScrollTo().performClick()
        compose.onNodeWithTag("entry-swaps").assertTextContains("honey, maple syrup")
        compose.onNodeWithTag("entry-replace").performScrollTo().performTextInput("g")
        compose.onNodeWithTag("suggest-golden syrup").performScrollTo().performClick()
        compose.onNodeWithTag("entry-replace").assertTextContains("golden syrup")
        compose.onNodeWithTag("entry-save").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("entry-error").assertExists()
        compose.onNodeWithTag("entry-name").performScrollTo().performTextInput("s")
        compose.onNodeWithTag("entry-error").assertDoesNotExist()
        compose.onNodeWithTag("entry-cancel").performScrollTo().performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun resetAsksFirst() {
        var reset = 0
        compose.setContent {
            HyperShopTheme { EntryEditor(EntryForm(id = "milk", name = "milk", edited = true), onSave = { null }, onReset = { reset++ }) }
        }
        compose.onNodeWithTag("entry-reset").performScrollTo().performClick()
        compose.onNodeWithText("Reset Milk to default?").assertExists()
        compose.onNodeWithText("Keep").performClick()
        assertEquals(0, reset)
        compose.onNodeWithTag("entry-reset").performClick()
        compose.onNodeWithTag("entry-reset-confirm").performClick()
        assertEquals(1, reset)
    }

    @Test
    fun recordLight() {
        compose.setContent { HyperShopTheme { LibraryScreen(UI, "", onBack = {}, onSearch = {}, onOpen = {}, onAdd = {}) } }
        compose.onRoot().captureRoboImage(Shots.path("library-light.png"))
    }

    @Test
    fun recordDark() {
        compose.setContent { HyperShopTheme(dark = true) { LibraryScreen(UI, "", onBack = {}, onSearch = {}, onOpen = {}, onAdd = {}) } }
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
