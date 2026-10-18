package com.robert4lyf.hypershop.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
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
                val tabs =
                    listOf(
                        Tab(Routes.LIST, "List", Icons.Rounded.ShoppingCart, Icons.Outlined.ShoppingCart),
                        Tab(Routes.RECIPES, "Recipes", Icons.AutoMirrored.Rounded.MenuBook, Icons.AutoMirrored.Outlined.MenuBook),
                        Tab(Routes.SETTINGS, "Settings", Icons.Rounded.Settings, Icons.Outlined.Settings),
                    )
                CompactNavBar(tabs, route) { r ->
                    // Each tab keeps its state (and its view model) while you are on another,
                    // so coming back doesn't rebuild it.
                    nav.navigate(r) {
                        popUpTo(Routes.LIST) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        },
    ) { padding ->
        val openReview: (Long) -> Unit = { nav.navigate(Routes.review(it)) { launchSingleTop = true } }
        // A quick fade, not Navigation's default 700 ms cross-fade, which made tab switches feel slow.
        NavHost(
            nav,
            startDestination = Routes.LIST,
            modifier = Modifier.padding(padding),
            enterTransition = { fadeIn(tween(150)) },
            exitTransition = { fadeOut(tween(100)) },
            popEnterTransition = { fadeIn(tween(150)) },
            popExitTransition = { fadeOut(tween(100)) },
        ) {
            composable(Routes.LIST) { ListRoute(container, snackbar, onReview = openReview) }
            composable(Routes.RECIPES) { RecipesRoute(container, snackbar, onReview = openReview) }
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

/**
 * The tab bar: 60 dp instead of Material's 80, with a small pill behind the
 * selected icon. Each tab is one TalkBack node (a selected tab and its label).
 */
@Composable
private fun CompactNavBar(
    tabs: List<Tab>,
    route: String?,
    onSelect: (String) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(60.dp)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((r, label, on, off) in tabs) {
                val selected = route == r
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(r) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val muted = MaterialTheme.colorScheme.onSurfaceVariant
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .padding(horizontal = 18.dp, vertical = 3.dp),
                    ) {
                        Icon(
                            if (selected) on else off,
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else muted,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
