package com.robert4lyf.hypershop.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.robert4lyf.hypershop.di.AppContainer
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
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (route != Routes.REVIEW) {
                NavigationBar {
                    listOf(
                        Triple(Routes.LIST, "List", Icons.AutoMirrored.Filled.List),
                        Triple(Routes.RECIPES, "Recipes", Icons.Filled.MenuBook),
                        Triple(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
                    ).forEach { (r, label, icon) ->
                        NavigationBarItem(
                            selected = route == r,
                            onClick = {
                                nav.navigate(r) {
                                    popUpTo(Routes.LIST)
                                    launchSingleTop = true
                                }
                            },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.LIST, modifier = Modifier.padding(padding)) {
            composable(Routes.LIST) { ListRoute(container, snackbar, onReview = { nav.navigate(Routes.review(it)) }) }
            composable(Routes.RECIPES) { RecipesRoute(container, snackbar, onReview = { nav.navigate(Routes.review(it)) }) }
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
