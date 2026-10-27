package com.robert4lyf.hypershop.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.rounded.CalendarMonth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
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
import com.robert4lyf.hypershop.di.Startup
import com.robert4lyf.hypershop.ui.common.UiMessage
import com.robert4lyf.hypershop.ui.library.LibraryRoute
import com.robert4lyf.hypershop.ui.list.ListRoute
import com.robert4lyf.hypershop.ui.list.show
import com.robert4lyf.hypershop.ui.meals.MealsRoute
import com.robert4lyf.hypershop.ui.recipes.RecipesRoute
import com.robert4lyf.hypershop.ui.review.ReviewRoute
import com.robert4lyf.hypershop.ui.settings.SettingsRoute
import com.robert4lyf.hypershop.ui.voicetest.VoiceTestRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

object Routes {
    const val LIST = "list"
    const val RECIPES = "recipes"
    const val SETTINGS = "settings"
    const val REVIEW = "review/{job}"
    const val DEBUG = "debug"
    const val LIBRARY = "library"
    const val VOICE_TEST = "voice-test"
    const val MEALS = "meals"

    fun review(job: Long) = "review/$job"
}

private val TABS = setOf(Routes.MEALS, Routes.LIST, Routes.RECIPES, Routes.SETTINGS)

internal data class Tab(
    val route: String,
    val label: String,
    val on: androidx.compose.ui.graphics.vector.ImageVector,
    val off: androidx.compose.ui.graphics.vector.ImageVector,
)

/**
 * The app's navigation: four tabs in the order of the week (plan it, shop it, the recipes behind it, settings),
 * plus the review screen opened by an import.
 */
@Composable
fun HyperShopApp(
    container: AppContainer,
    nav: NavHostController = rememberNavController(),
    debugScreen: (@Composable (AppContainer) -> Unit)? = null,
) {
    // Nothing that can read or write the list is composed until start-up has finished.
    val startup by container.startup.collectAsState()
    if (startup != Startup.Ready) {
        StartupScreen(
            startup,
            onRetry = { container.retryStartUp() },
            onKeep = { container.abandonStuckRestore() },
        )
        return
    }
    AppShell(container, nav, debugScreen)
}

@Composable
private fun AppShell(
    container: AppContainer,
    nav: NavHostController,
    debugScreen: (@Composable (AppContainer) -> Unit)?,
) {
    val snackbar = remember { SnackbarHostState() }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val share by container.shares.collectAsState()
    LaunchedEffect(share) {
        val s = share ?: return@LaunchedEffect
        when (s) {
            is ShareOutcome.Review -> container.queueReview(s.jobId)
            is ShareOutcome.NoLink -> show(container, snackbar, UiMessage("There was no link or ingredient list in what you shared"))
        }
        container.shareHandled(s)
    }
    val notice by container.notice.collectAsState()
    LaunchedEffect(notice) {
        val n = notice ?: return@LaunchedEffect
        container.notice.value = null
        show(container, snackbar, UiMessage(n))
    }
    // One review at a time: the next share opens once this review closes (its job waits on the ready card meanwhile).
    // The queue lives in the container, so a rotation loses nothing; an id leaves it only once its review is opened.
    val pending by container.pendingReviews.collectAsState()
    LaunchedEffect(route, pending) {
        val next = pending.firstOrNull() ?: return@LaunchedEffect
        if (nav.currentBackStackEntry?.destination?.route == Routes.REVIEW) return@LaunchedEffect
        nav.navigate(Routes.review(next)) { launchSingleTop = true }
        container.reviewOpened(next)
    }
    // The review screens pin their main action to the bottom edge (BottomActionBar pads for
    // the navigation bar itself), so the snackbar sits above that bar instead of over it.
    val reviewing = route == Routes.REVIEW
    // Each tab keeps its state (and its view model) while you are on another, so coming back doesn't rebuild it.
    val selectTab: (String) -> Unit = { r ->
        nav.navigate(r) {
            popUpTo(Routes.LIST) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    // X.10: with nothing to buy the app opens on This week, where a shop starts; otherwise on the shopping list.
    // Once per launch (not on rotation), and only if nothing has navigated yet (a share's review).
    var startChosen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (startChosen) return@LaunchedEffect
        startChosen = true
        val empty = container.repository.list.first().liveItems.isEmpty()
        // Navigation's state belongs to the main thread, wherever the list was read.
        withContext(Dispatchers.Main) {
            if (empty && nav.currentBackStackEntry?.destination?.route == Routes.LIST) selectTab(Routes.MEALS)
        }
    }
    Scaffold(
        contentWindowInsets =
            if (reviewing) {
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            } else {
                ScaffoldDefaults.contentWindowInsets
            },
        snackbarHost = {
            // Above a screen's own bottom action (the review's Add, the plan's Add / Start shopping), never over it:
            // an Undo up for its few seconds must not stand between a shopper and the next step.
            SnackbarHost(
                snackbar,
                when {
                    reviewing -> Modifier.navigationBarsPadding().padding(bottom = 80.dp)
                    route == Routes.MEALS -> Modifier.padding(bottom = 80.dp)
                    else -> Modifier
                },
            )
        },
        bottomBar = {
            if (route != Routes.REVIEW) {
                val tabs =
                    listOf(
                        Tab(Routes.MEALS, "This week", Icons.Rounded.CalendarMonth, Icons.Outlined.CalendarMonth),
                        Tab(Routes.LIST, "Shopping", Icons.Rounded.ShoppingCart, Icons.Outlined.ShoppingCart),
                        Tab(Routes.RECIPES, "Recipes", Icons.AutoMirrored.Rounded.MenuBook, Icons.AutoMirrored.Outlined.MenuBook),
                        Tab(Routes.SETTINGS, "Settings", Icons.Rounded.Settings, Icons.Outlined.Settings),
                    )
                CompactNavBar(tabs, route, selectTab)
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
            composable(Routes.LIST) {
                ListRoute(container, snackbar, onReview = openReview, onPlan = { selectTab(Routes.MEALS) })
            }
            composable(Routes.RECIPES) {
                RecipesRoute(
                    container,
                    snackbar,
                    onReview = openReview,
                    onMeals = { selectTab(Routes.MEALS) },
                )
            }
            composable(Routes.MEALS) {
                MealsRoute(
                    container,
                    snackbar,
                    // Start shopping: the Shopping tab, which is the root of every stack.
                    onList = { nav.popBackStack(Routes.LIST, inclusive = false) },
                    onReview = openReview,
                )
            }
            composable(Routes.SETTINGS) {
                SettingsRoute(
                    container,
                    onDebug = debugScreen?.let { { nav.navigate(Routes.DEBUG) } },
                    onLibrary = { nav.navigate(Routes.LIBRARY) { launchSingleTop = true } },
                    onVoiceTest = { nav.navigate(Routes.VOICE_TEST) { launchSingleTop = true } },
                    snackbar = snackbar,
                )
            }
            composable(Routes.VOICE_TEST) { VoiceTestRoute(onBack = { nav.popBackStack() }) }
            composable(Routes.LIBRARY) { LibraryRoute(container, snackbar, onBack = { nav.popBackStack() }) }
            composable(Routes.REVIEW, arguments = listOf(navArgument("job") { type = NavType.LongType })) { e ->
                ReviewRoute(
                    container,
                    e.arguments!!.getLong("job"),
                    snackbar,
                    onDone = {
                        // Back to the tab the review was opened from, or to Settings › Library (not past it to the
                        // list, review B.18); the list when it was opened by a share.
                        val prev = nav.previousBackStackEntry?.destination?.route
                        if (prev in TABS || prev == Routes.LIBRARY) nav.popBackStack() else nav.popBackStack(Routes.LIST, inclusive = false)
                    },
                    // "Try again" opens the new job only while the review is still on screen; left already, the job
                    // waits on the Shopping tab's card (review B.20).
                    onReopen = { id ->
                        if (nav.currentBackStackEntry?.destination?.route == Routes.REVIEW) {
                            nav.navigate(Routes.review(id)) {
                                popUpTo(Routes.REVIEW) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    },
                    // Undo after Cancel, up to 10 s later: a review opened meanwhile is left alone (the job is back on the card).
                    onUndo = { id ->
                        if (nav.currentBackStackEntry?.destination?.route != Routes.REVIEW) {
                            nav.navigate(Routes.review(id)) { launchSingleTop = true }
                        }
                    },
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
internal fun CompactNavBar(
    tabs: List<Tab>,
    route: String?,
    onSelect: (String) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                // As tall as its tallest tab on one line and at least 60 dp: at 200 % font scale the label needs
                // the room. Intrinsic, not a bare minimum: Scaffold offers the bar the whole screen as its maximum
                // and the tabs fill the bar's height, so a bare minimum made the bar fill the screen on a device.
                // (The max intrinsic, not the min: the min lets a label wrap letter by letter.)
                .heightIn(min = 60.dp)
                .height(IntrinsicSize.Max)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((r, label, on, off) in tabs) {
                // The ingredient library and the voice test are part of Settings, so its tab stays lit there.
                val selected =
                    route == r ||
                        (r == Routes.SETTINGS && (route == Routes.LIBRARY || route == Routes.VOICE_TEST))
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(r) })
                        .testTag("tab-$r"),
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
