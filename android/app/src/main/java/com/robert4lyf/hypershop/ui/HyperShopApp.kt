package com.robert4lyf.hypershop.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.di.ShareOutcome
import com.robert4lyf.hypershop.ui.list.ListRoute
import com.robert4lyf.hypershop.ui.recipes.RecipesRoute
import com.robert4lyf.hypershop.ui.review.ReviewRoute
import com.robert4lyf.hypershop.ui.settings.SettingsRoute

object Routes {
    const val LIST = "list"
    const val RECIPES = "recipes"
    const val SETTINGS = "settings"
    const val REVIEW = "review/{job}"
    const val DEBUG = "debug"

    fun review(job: Long) = "review/$job"
}

private data class Tab(
    val route: String,
    val label: String,
    val on: androidx.compose.ui.graphics.vector.ImageVector,
    val off: androidx.compose.ui.graphics.vector.ImageVector,
)

/** The app's navigation: three tabs, plus the review screen opened by an import. */
@Composable
fun HyperShopApp(
    container: AppContainer,
    nav: NavHostController = rememberNavController(),
    debugScreen: (@Composable (AppContainer) -> Unit)? = null,
) {
    val snackbar = remember { SnackbarHostState() }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val share by container.shares.collectAsState()
    LaunchedEffect(share) {
        when (val s = share) {
            is ShareOutcome.Review -> nav.navigate(Routes.review(s.jobId)) { launchSingleTop = true }
            ShareOutcome.NoLink -> snackbar.showSnackbar("There was no https link in what you shared")
            null -> return@LaunchedEffect
        }
        container.shares.value = null
    }
    // The review screens pin their main action to the bottom edge (BottomActionBar pads for
    // the navigation bar itself), so the snackbar sits above that bar instead of over it.
    val reviewing = route == Routes.REVIEW
    Scaffold(
        contentWindowInsets =
            if (reviewing) {
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            } else {
                ScaffoldDefaults.contentWindowInsets
            },
        snackbarHost = {
            SnackbarHost(snackbar, if (reviewing) Modifier.navigationBarsPadding().padding(bottom = 80.dp) else Modifier)
        },
        bottomBar = {
            if (route != Routes.REVIEW) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    listOf(
                        Tab(Routes.LIST, "List", Icons.Rounded.ShoppingCart, Icons.Outlined.ShoppingCart),
                        Tab(Routes.RECIPES, "Recipes", Icons.AutoMirrored.Rounded.MenuBook, Icons.AutoMirrored.Outlined.MenuBook),
                        Tab(Routes.SETTINGS, "Settings", Icons.Rounded.Settings, Icons.Outlined.Settings),
                    ).forEach { (r, label, on, off) ->
                        val selected = route == r
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(r) {
                                    popUpTo(Routes.LIST)
                                    launchSingleTop = true
                                }
                            },
                            icon = { Icon(if (selected) on else off, contentDescription = null) },
                            label = { Text(label) },
                            colors =
                                NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.LIST, modifier = Modifier.padding(padding)) {
            composable(Routes.LIST) { ListRoute(container, snackbar, onReview = { nav.navigate(Routes.review(it)) { launchSingleTop = true } }) }
            composable(Routes.RECIPES) { RecipesRoute(container, snackbar, onReview = { nav.navigate(Routes.review(it)) { launchSingleTop = true } }) }
            composable(Routes.SETTINGS) { SettingsRoute(container, onDebug = debugScreen?.let { { nav.navigate(Routes.DEBUG) } }) }
            composable(Routes.REVIEW, arguments = listOf(navArgument("job") { type = NavType.LongType })) { e ->
                ReviewRoute(
                    container,
                    e.arguments!!.getLong("job"),
                    snackbar,
                    onDone = { nav.popBackStack(Routes.LIST, inclusive = false) },
                )
            }
            if (debugScreen != null) composable(Routes.DEBUG) { debugScreen(container) }
        }
    }
}
