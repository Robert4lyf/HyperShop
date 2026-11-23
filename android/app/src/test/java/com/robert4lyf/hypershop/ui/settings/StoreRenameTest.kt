package com.robert4lyf.hypershop.ui.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Step AD.2: a store is renamed from the pencil on its tab, in a small dialog. At Robolectric's default size: with the
 * `w411dp-h891dp-xxhdpi` qualifiers a dialog holding a text field never lets Compose go idle (a test-only hang, seen
 * with a bare AlertDialog too).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StoreRenameTest {
    /** No container here: the last test's is wound down first (see [quietStart]). */
    @get:Rule(order = 0)
    val quiet = com.robert4lyf.hypershop.testutil.quietStart()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Test
    fun thePencilRenamesItsOwnStore() {
        val events = mutableListOf<String>()
        val ui =
            SettingsUi(aisles = AisleCode.entries.take(3), loaded = true, storeNames = mapOf(1L to "Tesco", 2L to "Second store"))
        compose.setContent {
            HyperShopTheme(dynamicColor = false) {
                AislesScreen(
                    ui,
                    SettingsActions(
                        onEditStore = { events += "edit $it" },
                        onRenameStore = { store, name -> events += "rename $store $name" },
                    ),
                )
            }
        }
        compose.onNodeWithContentDescription("Rename Second store").performClick()
        compose.onNodeWithTag("store-name").performTextReplacement("  ")
        compose.onNodeWithTag("store-name-save").assertIsNotEnabled()
        compose.onNodeWithTag("store-name").performTextReplacement("Lidl")
        compose.onNodeWithTag("store-name-save").performClick()
        compose.onNodeWithTag("store-name").assertDoesNotExist()
        // The store whose pencil it was, not the one being edited (store 1); and the tab stays as it was.
        assertEquals(listOf("rename 2 Lidl"), events)
        compose.onNodeWithContentDescription("Rename Tesco").performClick()
        compose.onNodeWithTag("store-name").performTextReplacement("Big Tesco")
        compose.onNodeWithTag("store-name-save").performClick()
        assertEquals(listOf("rename 2 Lidl", "rename 1 Big Tesco"), events)
    }
}
