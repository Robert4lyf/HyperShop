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
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.rounded.CalendarMonth
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.robert4lyf.hypershop.BuildConfig
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
import com.robert4lyf.hypershop.ui.settings.AislesRoute
import com.robert4lyf.hypershop.ui.settings.SettingsRoute
import com.robert4lyf.hypershop.ui.voicetest.VoiceTestRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Routes {
    const val LIST = "list"
    const val RECIPES = "recipes"
    const val SETTINGS = "settings"
    const val REVIEW = "review/{job}"
    const val DEBUG = "debug"
    const val LIBRARY = "library"
    const val AISLES = "aisles"
    const val VOICE_TEST = "voice-test"
    const val MEALS = "meals"

    /** Step Q: the focused food-at-home check. */
    const val HOME = "home"

    fun review(job: Long) = "review/$job"
}

/** Step Q: the three main destinations. Settings is reached from the gear at the top of each. */
private val TABS = setOf(Routes.MEALS, Routes.LIST, Routes.RECIPES)

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
    val storeChoices by container.shopStart.choices.collectAsState()
    val shopScope = rememberCoroutineScope()
    if (storeChoices.isNotEmpty()) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { container.shopStart.choices.value = emptyList() },
            title = { Text("Which store are you shopping in?") },
            text = {
                androidx.compose.foundation.layout.Column {
                    storeChoices.forEach { store ->
                        androidx.compose.material3.TextButton(
                            onClick = { shopScope.launch { container.shopStart.start(store.id) } },
                        ) { Text(store.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { container.shopStart.choices.value = emptyList() },
                ) { Text("Cancel") }
            },
        )
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
    // The widget and the launcher shortcut (missing 7 of the review): Shopping, and for Add the Add field focused.
    // A focus request waits (above zero) until the field takes it, then is gone: it never comes back later.
    var addFocus by rememberSaveable { mutableIntStateOf(0) }
    // Once an entry point has said where to go, the start-up choice of tab below leaves it there.
    var entered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        container.entryRequests.collect { request ->
            if (request == com.robert4lyf.hypershop.EntryRequest.ADD) {
                container.settings.setListMode(com.robert4lyf.hypershop.settings.ListMode.PLAN)
            }
            // Navigation's state belongs to the main thread, wherever the setting was written. A request waiting
            // from a cold start arrives before NavHost has set its graph, and navigating then throws: wait for it.
            withContext(Dispatchers.Main) {
                nav.currentBackStackEntryFlow.first()
                entered = true
                selectTab(Routes.LIST)
                // Add focuses the field once; the list on its own drops any focus still waiting, so it never comes later.
                addFocus = if (request == com.robert4lyf.hypershop.EntryRequest.ADD) addFocus + 1 else 0
            }
            container.entryRequestHandled()
        }
    }
    // Step Q: the app opens where it was last left (Meals, Shopping list or Recipes), the list on a first run. An
    // empty list no longer moves it to Meals. Once per launch (not on rotation); a share's review, the widget or the
    // launcher shortcut, which say where to go, come first.
    var startChosen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (startChosen) return@LaunchedEffect
        startChosen = true
        if (container.entryRequests.replayCache.isNotEmpty()) return@LaunchedEffect
        val last = container.settings.lastTab.first()?.takeIf { it in TABS }
        // Navigation's state belongs to the main thread, wherever the setting was read.
        withContext(Dispatchers.Main) {
            nav.currentBackStackEntryFlow.first()
            if (!entered && last != null && last != Routes.LIST && nav.currentBackStackEntry?.destination?.route == Routes.LIST) {
                selectTab(last)
            }
        }
    }
    // Where to open next time: the main destination last shown.
    LaunchedEffect(route) { route?.takeIf { it in TABS }?.let { container.settings.setLastTab(it) } }
    val openSettings: () -> Unit = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } }
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
                    route == Routes.MEALS || route == Routes.HOME -> Modifier.padding(bottom = 80.dp)
                    else -> Modifier
                },
            )
        },
        bottomBar = {
            // The three main destinations only: Settings and its screens, and the review, have their own way back.
            if (route == null || route in TABS) {
                val tabs =
                    listOf(
                        Tab(Routes.MEALS, "Meals", Icons.Rounded.CalendarMonth, Icons.Outlined.CalendarMonth),
                        Tab(Routes.LIST, "Shopping list", Icons.Rounded.ShoppingCart, Icons.Outlined.ShoppingCart),
                        Tab(Routes.RECIPES, "Recipes", Icons.AutoMirrored.Rounded.MenuBook, Icons.AutoMirrored.Outlined.MenuBook),
                    )
                CompactNavBar(tabs, route, selectTab)
            }
        },
    ) { padding ->
        val openReview: (Long) -> Unit = { nav.navigate(Routes.review(it)) { launchSingleTop = true } }
        // A quick fade, not Navigation's default 700 ms cross-fade, which made tab switches feel slow.
        CompositionLocalProvider(com.robert4lyf.hypershop.ui.common.LocalOpenSettings provides openSettings) {
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
                    ListRoute(
                        container,
                        snackbar,
                        onReview = openReview,
                        onPlan = { selectTab(Routes.MEALS) },
                        focusAdd = addFocus,
                        onAddFocused = { addFocus = 0 },
                        onHomeCheck = { nav.navigate(Routes.HOME) { launchSingleTop = true } },
                    )
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
                        // View shopping list: the Shopping list tab.
                        onList = { selectTab(Routes.LIST) },
                        onReview = openReview,
                        onHomeCheck = { nav.navigate(Routes.HOME) { launchSingleTop = true } },
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsRoute(
                        container,
                        onDebug = debugScreen?.let { { nav.navigate(Routes.DEBUG) } },
                        onLibrary = { nav.navigate(Routes.LIBRARY) { launchSingleTop = true } },
                        onAisles = { nav.navigate(Routes.AISLES) { launchSingleTop = true } },
                        // A developer harness with a microphone service: not in a release until voice mode exists.
                        onVoiceTest = if (BuildConfig.DEBUG) ({ nav.navigate(Routes.VOICE_TEST) { launchSingleTop = true } }) else null,
                        snackbar = snackbar,
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(Routes.HOME) {
                    com.robert4lyf.hypershop.ui.meals.HomeCheckRoute(container, snackbar, onBack = { nav.popBackStack() })
                }
                composable(Routes.VOICE_TEST) { VoiceTestRoute(onBack = { nav.popBackStack() }) }
                composable(Routes.LIBRARY) { LibraryRoute(container, snackbar, onBack = { nav.popBackStack() }) }
                composable(Routes.AISLES) { AislesRoute(container, onBack = { nav.popBackStack() }) }
                composable(Routes.REVIEW, arguments = listOf(navArgument("job") { type = NavType.LongType })) { e ->
                    ReviewRoute(
                        container,
                        e.arguments!!.getLong("job"),
                        snackbar,
                        onDone = {
                            // Back to the tab the review was opened from, or to Settings › Library (not past it to the
                            // list, review B.18); the list when it was opened by a share.
                            val prev = nav.previousBackStackEntry?.destination?.route
                            if (prev in TABS || prev == Routes.LIBRARY) {
                                nav.popBackStack()
                            } else {
                                nav.popBackStack(
                                    Routes.LIST,
                                    inclusive = false,
                                )
                            }
                        },
                        // "Try again" opens the new job only while the review is still on screen; left already, the job
                        // waits on the Shopping tab's card (review B.20).
                        onSettings = openSettings,
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
                val selected = route == r
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
