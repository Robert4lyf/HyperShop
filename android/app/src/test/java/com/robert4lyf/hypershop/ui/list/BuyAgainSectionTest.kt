package com.robert4lyf.hypershop.ui.list

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.data.BuyAgainView
import com.robert4lyf.hypershop.data.Regular
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Buy again's section (it was tested with the Meals screen, which Step Z removed). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class BuyAgainSectionTest {
    @get:Rule val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private fun regular(
        name: String,
        onList: Boolean = false,
    ) = Regular("cat:$name", name, QtyBase.PRESENCE, null, false, 3, onList)

    private val ui =
        BuyAgainUi(
            loaded = true,
            buyAgain =
                BuyAgainView(
                    regulars = listOf(regular("milk"), regular("bread", onList = true), regular("eggs")),
                    lastShop = listOf(regular("toilet roll")),
                    lastShopAt = 1L,
                    hasHistory = true,
                ),
        )
    private val actions =
        BuyAgainActions(
            onRegular = { events += "regular ${it.name}" },
            onAllRegulars = { events += "all regulars" },
            onRepeatLastShop = { events += "last shop" },
        )

    @Test
    fun theBuyAgainSectionAddsChipsAllAndLastShop() {
        compose.setContent { HyperShopTheme(dynamicColor = false) { BuyAgainSection(ui, actions) } }
        compose.onNodeWithTag("regular-cat:milk").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("regular-cat:milk").performClick()
        // On the list already: a tap does nothing.
        compose.onNodeWithTag("regular-cat:bread").performClick()
        compose.onNodeWithTag("buy-again-all").performClick()
        compose.onNodeWithText("Add all 2").assertExists()
        compose.onNodeWithTag("repeat-last-shop").performClick()
        assertEquals(listOf("regular milk", "all regulars", "last shop"), events)
    }
}
