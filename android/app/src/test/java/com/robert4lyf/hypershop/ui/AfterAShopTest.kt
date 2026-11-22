package com.robert4lyf.hypershop.ui

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Step AD.11: after a shop, the empty list's "Add recipes" opens the Recipes tab, through the real app shell. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AfterAShopTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun addRecipesOnTheEmptyListOpensRecipes() {
        val c = TestContainer.create()
        runBlocking {
            c.awaitReady()
            c.repository.manualAdd("milk", QtyBase.PRESENCE, null)
            c.repository.toggle(c.repository.snapshot().liveItems.single().id)
            c.repository.finish()
        }
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            HyperShopTheme(dynamicColor = false) { HyperShopApp(c, nav) }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("empty-recipes")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("empty-last-shop").assertExists()
        compose.onNodeWithTag("empty-recipes").performClick()
        compose.waitForIdle()
        assertEquals(Routes.RECIPES, nav.currentBackStackEntry?.destination?.route)
    }
}
