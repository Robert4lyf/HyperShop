package com.robert4lyf.hypershop.ui.list

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
        listOf("2", "200 g"),
        listOf(ItemSource("Weeknight chilli", "2"), ItemSource("Added by hand", "200 g")),
        "1 × 1 kg bag",
        AisleCode.PRODUCE,
    )

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ItemSheetTest {
    @get:Rule val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private fun show(dark: Boolean = false) =
        compose.setContent {
            HyperShopTheme(dark = dark, dynamicColor = false) {
                ItemSheetContent(
                    SHEET,
                    ItemSheetActions(
                        onMove = { a, always -> events += "move $a $always" },
                        onEditQuantity = { t ->
                            events += "qty $t"
                            t != "lots"
                        },
                    ),
                )
            }
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
                        ItemSheetActions(onMove = { _, _ -> }, onEditQuantity = { true }),
                    )
                }
            }
        }
        compose.onNodeWithText("Weeknight chilli").performClick()
        compose.onNodeWithText("Added by hand").performClick()
        assertEquals(listOf("https://recipes.example/chilli"), opened)
    }

    @Test
    fun totalsSourcesQuantityAndAisle() {
        show()
        compose.onNodeWithTag("totals").assertExists()
        compose.onNodeWithText("On the list: 2 + 200 g").assertExists()
        compose.onNodeWithText("Usually sold as 1 × 1 kg bag").assertExists()
        compose.onNodeWithText("Added by hand").assertExists()
        compose.onNodeWithText("200 g").assertExists()
        compose.onNodeWithTag("edit-qty").performTextInput("lots")
        compose.onNodeWithText("Set").performClick()
        compose.onNodeWithText("Enter an amount, like 2 or 200 ml").assertExists()
        compose.onNodeWithText(AisleCode.DAIRY_EGGS.displayName).performClick()
        compose.onNodeWithText("Always").performClick()
        assertEquals(listOf("qty lots", "move DAIRY_EGGS true"), events)
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
}
