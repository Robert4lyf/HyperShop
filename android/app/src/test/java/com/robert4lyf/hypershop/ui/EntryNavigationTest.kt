package com.robert4lyf.hypershop.ui

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.EntryRequest
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.di.Startup
import com.robert4lyf.hypershop.testutil.Photos.saved
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.testutil.awaitTrue
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The widget's and launcher's requests through the real app shell and its navigation: the list request opens
 * Shopping as it is, the Add request opens it with the field focused, from a cold start or another tab, and a
 * focus is never repeated later.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class EntryNavigationTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var nav: NavHostController

    private fun show(c: AppContainer) =
        compose.setContent {
            nav = rememberNavController()
            HyperShopTheme(dynamicColor = false) { HyperShopApp(c, nav) }
        }

    private fun route() = nav.currentBackStackEntry?.destination?.route

    private fun waitFor(tag: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    /** The request is handled on the main looper, which waitUntil alone does not run: idle it while waiting. */
    private fun awaitRoute(want: String) {
        val end = System.currentTimeMillis() + 10_000
        while (route() != want) {
            check(System.currentTimeMillis() < end) { "still on ${route()}, waiting for $want" }
            compose.waitForIdle()
            Thread.sleep(20)
        }
        compose.waitForIdle()
    }

    private fun settle() {
        // The start-up choice of tab reads the database off the main thread; give it time to have its say.
        Thread.sleep(300)
        compose.waitForIdle()
    }

    @Test
    fun aListTapOnAColdStartOpensShoppingAsItIsEvenForAReturningUserWithAnEmptyList() {
        val c = TestContainer.create()
        // A returning user with nothing to buy normally opens on This week (X.10); the widget asked for the list.
        c.saved("Weeknight chilli")
        c.entryRequests.tryEmit(EntryRequest.LIST)
        show(c)
        waitFor("add-item")
        settle()
        assertEquals(Routes.LIST, route())
        compose.onNodeWithTag("add-item").assertIsNotFocused()
        assertEquals(emptyList<EntryRequest>(), c.entryRequests.replayCache)
    }

    @Test
    fun launchOpensWhereTheAppWasLeftNotWhereTheEmptyListPointsAndSettingsIsTheGear() {
        val c = TestContainer.create()
        // A returning user with nothing to buy used to be moved to This week (X.10). Now a first launch is the list.
        c.saved("Weeknight chilli")
        show(c)
        waitFor("add-item")
        settle()
        assertEquals(Routes.LIST, route())
        // Recipes, then Settings from its gear: a screen of its own, with no tab bar, and Back returns to Recipes.
        compose.onNodeWithTag("tab-${Routes.RECIPES}").performClick()
        awaitRoute(Routes.RECIPES)
        compose.onNodeWithTag("open-settings").performClick()
        awaitRoute(Routes.SETTINGS)
        compose.onNodeWithTag("tab-${Routes.LIST}").assertDoesNotExist()
        compose.onNodeWithTag("settings-back").performClick()
        awaitRoute(Routes.RECIPES)
        // Remembered for the next launch.
        compose.waitUntil(5_000) { runBlocking { c.settings.lastTab.first() } == Routes.RECIPES }
    }

    @Test
    fun theNextLaunchRestoresTheLastMainDestination() {
        val c = TestContainer.create()
        runBlocking { c.settings.setLastTab(Routes.MEALS) }
        show(c)
        awaitRoute(Routes.MEALS)
    }

    @Test
    fun anAddRequestWaitsForStartUpThenFocusesTheField() {
        val c = TestContainer.create(ready = false)
        c.entryRequests.tryEmit(EntryRequest.ADD)
        show(c)
        compose.onNodeWithTag("startup").assertExists()
        compose.onNodeWithTag("add-item").assertDoesNotExist()
        c.startUp()
        awaitTrue { c.startup.value == Startup.Ready }
        waitFor("add-item")
        compose.waitForIdle()
        compose.onNodeWithTag("add-item").assertIsFocused()
        assertEquals(Routes.LIST, route())
    }

    @Test
    fun fromAnotherTabAddFocusesTheFieldOnceAndListOpensWithoutFocus() {
        val c = TestContainer.create()
        show(c)
        waitFor("add-item")
        compose.onNodeWithTag("tab-${Routes.MEALS}").performClick()
        awaitRoute(Routes.MEALS)
        // The widget's preview while the app is open on another tab.
        c.entryRequests.tryEmit(EntryRequest.LIST)
        awaitRoute(Routes.LIST)
        waitFor("add-item")
        compose.onNodeWithTag("add-item").assertIsNotFocused()
        // Add item from This week.
        compose.onNodeWithTag("tab-${Routes.MEALS}").performClick()
        awaitRoute(Routes.MEALS)
        c.entryRequests.tryEmit(EntryRequest.ADD)
        awaitRoute(Routes.LIST)
        waitFor("add-item")
        compose.waitForIdle()
        compose.onNodeWithTag("add-item").assertIsFocused()
        // Away and back by the tabs: the request was used up, so the field is not focused again.
        compose.onNodeWithTag("tab-${Routes.MEALS}").performClick()
        awaitRoute(Routes.MEALS)
        compose.onNodeWithTag("tab-${Routes.LIST}").performClick()
        awaitRoute(Routes.LIST)
        waitFor("add-item")
        compose.waitForIdle()
        compose.onNodeWithTag("add-item").assertIsNotFocused()
    }
}
