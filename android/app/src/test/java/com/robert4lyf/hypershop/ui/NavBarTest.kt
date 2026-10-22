package com.robert4lyf.hypershop.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The tab bar at 200 % font scale: labels whole, the plan screen under the List tab. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NavBarTest {
    @get:Rule val compose = createComposeRule()

    private val tabs =
        listOf(
            Tab(Routes.LIST, "List", Icons.Rounded.ShoppingCart, Icons.Outlined.ShoppingCart),
            Tab(Routes.SETTINGS, "Settings", Icons.Rounded.Settings, Icons.Outlined.Settings),
        )

    @Test
    fun labelsAreNotClippedAtTwiceTheFontSize() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2.625f, fontScale = 2f)) {
                HyperShopTheme(dynamicColor = false) { CompactNavBar(tabs, route = Routes.MEALS, onSelect = {}) }
            }
        }
        for (label in listOf("List", "Settings")) {
            val node = compose.onNodeWithText(label)
            assertEquals(label, node.getUnclippedBoundsInRoot(), node.getBoundsInRoot())
        }
        // The week's plan is about the list, so that tab stays lit there.
        compose.onNodeWithText("List").assertIsSelected()
    }
}
