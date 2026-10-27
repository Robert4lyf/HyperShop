package com.robert4lyf.hypershop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The tab bar at 200 % font scale: labels whole; This week is a tab of its own (X.10). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class NavBarTest {
    @get:Rule val compose = createComposeRule()

    private val tabs =
        listOf(
            Tab(Routes.MEALS, "This week", Icons.Rounded.CalendarMonth, Icons.Outlined.CalendarMonth),
            Tab(Routes.LIST, "Shopping", Icons.Rounded.ShoppingCart, Icons.Outlined.ShoppingCart),
            Tab(Routes.SETTINGS, "Settings", Icons.Rounded.Settings, Icons.Outlined.Settings),
        )

    @Test
    fun labelsAreNotClippedAtTwiceTheFontSize() {
        compose.setContent {
            // The real density, only the font scaled: the bounds below are read in the root's own dp.
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                HyperShopTheme(dynamicColor = false) { CompactNavBar(tabs, route = Routes.MEALS, onSelect = {}) }
            }
        }
        for (label in listOf("This week", "Shopping", "Settings")) {
            val node = compose.onNodeWithText(label)
            assertEquals(label, node.getUnclippedBoundsInRoot(), node.getBoundsInRoot())
        }
        compose.onNodeWithText("This week").assertIsSelected()
        compose.onNodeWithText("Shopping").assertIsNotSelected()
        // Taller than 60 dp for the big labels, but a bar, not a screen: Scaffold offers it the whole screen.
        val height = compose.onRoot().getBoundsInRoot().height
        assertTrue("bar height $height", height >= 60.dp && height <= 120.dp)
    }

    @Test
    fun theBarIsSixtyDpAtTheNormalFontSizeHoweverTallItsParent() {
        compose.setContent {
            HyperShopTheme(dynamicColor = false) {
                Box(Modifier.fillMaxSize()) { CompactNavBar(tabs, route = Routes.LIST, onSelect = {}) }
            }
        }
        val bar = compose.onNodeWithText("Shopping").onParent().onParent()
        val height = bar.getBoundsInRoot().height
        assertTrue("bar height $height", height >= 60.dp && height <= 72.dp)
    }
}
