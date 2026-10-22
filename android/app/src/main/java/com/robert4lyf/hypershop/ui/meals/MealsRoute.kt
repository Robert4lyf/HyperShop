package com.robert4lyf.hypershop.ui.meals

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robert4lyf.hypershop.data.HomeRow
import com.robert4lyf.hypershop.data.MealShopping
import com.robert4lyf.hypershop.data.Regular
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.ui.common.BottomActionBar
import com.robert4lyf.hypershop.ui.common.EmptyHero
import com.robert4lyf.hypershop.ui.common.PillShape
import com.robert4lyf.hypershop.ui.common.SectionLabel
import com.robert4lyf.hypershop.ui.common.cardLift
import com.robert4lyf.hypershop.ui.common.fieldLift
import com.robert4lyf.hypershop.ui.common.label
import com.robert4lyf.hypershop.ui.common.pillFieldColors
import com.robert4lyf.hypershop.ui.list.show
import com.robert4lyf.hypershop.ui.theme.card
import com.robert4lyf.hypershop.ui.theme.lowPoly
import com.robert4lyf.hypershop.ui.util.factoryFor

data class MealsActions(
    val onPlan: (Pick) -> Unit = {},
    /** One more (+1) or one fewer (-1) serving. */
    val onServings: (Meal, Int) -> Unit = { _, _ -> },
    val onRemove: (Meal) -> Unit = {},
    val onSearch: (String) -> Unit = {},
    val onPreviousWeek: () -> Unit = {},
    val onNextWeek: () -> Unit = {},
    val onThisWeek: () -> Unit = {},
    val onRepeat: () -> Unit = {},
    val onAddToList: () -> Unit = {},
    val onRegular: (Regular) -> Unit = {},
    val onAllRegulars: () -> Unit = {},
    val onRepeatLastShop: () -> Unit = {},
    val onPin: (Regular) -> Unit = {},
    val onHide: (Regular) -> Unit = {},
    val onHave: (List<HomeRow>) -> Unit = {},
    val onNeed: (HomeRow) -> Unit = {},
    val onStartShopping: () -> Unit = {},
    val onBack: () -> Unit = {},
    /** "Go to the list": the List tab, where the combined ingredients are. */
    val onList: () -> Unit = {},
)

@Composable
fun MealsRoute(
    container: AppContainer,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onList: () -> Unit,
) {
    val vm: MealsViewModel = viewModel(factory = factoryFor { MealsViewModel(container) })
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.events.collect { show(container, snackbar, it) } }
    MealsScreen(
        ui,
        MealsActions(
            onPlan = vm::plan,
            onServings = { m, d -> vm.stepServings(m, d) },
            onRemove = vm::remove,
            onSearch = vm::search,
            onPreviousWeek = vm::previousWeek,
            onNextWeek = vm::nextWeek,
            onThisWeek = vm::thisWeek,
            onRepeat = vm::repeat,
            onAddToList = vm::addToList,
            onRegular = { vm.addRegular(it) },
            onAllRegulars = { vm.addAllRegulars() },
            onRepeatLastShop = { vm.repeatLastShop() },
            onPin = { vm.pin(it) },
            onHide = { vm.hide(it) },
            onHave = vm::have,
            onNeed = vm::need,
            onStartShopping = {
                vm.startShopping()
                onList()
            },
            onBack = onBack,
            onList = onList,
        ),
    )
}

/**
 * "Plan this week's shop", stateless (docs/design/release-b.md): the week's
 * meals, then (for this week) Buy again and the At home check, in the order a
 * weekly shop is prepared, with one primary action at the bottom: put the
 * waiting meals on the list, or start shopping. Nothing here is required:
 * a quick list never needs this screen.
 */
@Composable
fun MealsScreen(
    ui: MealsUi,
    actions: MealsActions,
) {
    var removing by rememberSaveable { mutableStateOf<Long?>(null) }
    var allPicks by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().imePadding()) {
            WeekHeader(ui, actions)
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().testTag("meals"),
                contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (ui.meals.isNotEmpty()) {
                    item(key = "planned") { SectionLabel("Meals", Modifier.padding(horizontal = 20.dp)) }
                    items(ui.meals, key = { "meal-${it.id}" }) { m ->
                        MealCard(m, past = ui.isPast, onServings = { actions.onServings(m, it) }, onRemove = { removing = m.id })
                    }
                } else if (ui.loaded && ui.repeatFrom != null && !ui.isPast) {
                    item(key = "repeat") { RepeatCard(ui, actions.onRepeat) }
                }
                if (ui.isCurrent && (ui.buyAgain.regulars.isNotEmpty() || ui.buyAgain.lastShop.isNotEmpty())) {
                    item(key = "buy-again") { BuyAgainSection(ui, actions) }
                }
                if (ui.isCurrent && ui.home.isNotEmpty()) {
                    item(key = "home-label") { HomeHeader(ui, actions) }
                    items(ui.home, key = { "home-${it.mergeKey}" }) { r -> HomeRowItem(r, actions) }
                }
                if (ui.loaded && !ui.hasLibrary) {
                    item(key = "none") {
                        EmptyHero(
                            "No saved recipes yet",
                            body = "Every recipe you add is saved. Share a link to HyperShop, or tap + on the Recipes tab.",
                        )
                    }
                }
                if (ui.hasLibrary && !ui.isPast) pickSection(ui, actions, allPicks) { allPicks = true }
            }
            BottomBar(ui, actions)
        }
    }
    removing?.let { id -> ui.meals.firstOrNull { it.id == id } }?.let { m ->
        val toBuy = m.progress?.toBuy?.takeIf { m.shopping == MealShopping.TO_BUY } ?: 0
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${m.title}?") },
            text = {
                Text(
                    if (toBuy > 0) {
                        "${plural(toBuy, "item")} it still needs come off the list. Anything bought stays bought. The recipe stays saved."
                    } else {
                        "It comes off this week's plan. The list and the recipe stay as they are."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    actions.onRemove(m)
                }, modifier = Modifier.testTag("meals-remove-confirm")) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun WeekHeader(
    ui: MealsUi,
    actions: MealsActions,
) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions.onBack, modifier = Modifier.testTag("back")) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
        }
        Column(Modifier.weight(1f)) {
            Text(
                ui.weekName.ifEmpty { "This week" },
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }.testTag("week-name"),
            )
            if (ui.loaded) {
                Text(
                    listOfNotNull(ui.range, summary(ui)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("week-summary"),
                )
            }
        }
        if (!ui.isCurrent) {
            TextButton(onClick = actions.onThisWeek, modifier = Modifier.testTag("week-today")) { Text("Today") }
        }
        IconButton(onClick = actions.onPreviousWeek, modifier = Modifier.testTag("week-prev")) {
            Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous week")
        }
        IconButton(onClick = actions.onNextWeek, modifier = Modifier.testTag("week-next")) {
            Icon(Icons.Rounded.ChevronRight, contentDescription = "Next week")
        }
    }
}

/** "3 meals · 5 items still needed": what am I cooking, what is left to buy. */
private fun summary(ui: MealsUi): String? =
    when {
        ui.meals.isEmpty() -> if (ui.isPast) "Nothing was planned" else "Nothing planned yet"
        ui.pending > 0 -> "${plural(ui.meals.size, "meal")} · ${ui.pending} not on the list yet"
        ui.itemsLeft > 0 -> "${plural(ui.meals.size, "meal")} · ${plural(ui.itemsLeft, "item")} still needed"
        else -> plural(ui.meals.size, "meal")
    }

@Composable
private fun RepeatCard(
    ui: MealsUi,
    onRepeat: () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).cardLift(MaterialTheme.shapes.large).testTag("repeat-week"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.card,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Same as ${ui.repeatName}?", style = MaterialTheme.typography.titleMedium)
            Text(
                ui.repeatTitles.take(4).joinToString(", ") + if (ui.repeatTitles.size > 4) " and ${ui.repeatTitles.size - 4} more" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            FilledTonalButton(onClick = onRepeat, modifier = Modifier.testTag("repeat-week-button")) {
                Icon(Icons.Rounded.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Plan the same ${plural(ui.repeatTitles.size, "meal")}")
            }
        }
    }
}

private fun mealState(m: Meal): String =
    when (m.shopping) {
        MealShopping.NOT_ON_LIST -> "Not on the list yet"
        MealShopping.TO_BUY -> m.progress!!.label()
        MealShopping.BOUGHT -> "All bought"
        MealShopping.NOTHING_TO_BUY -> "Nothing to buy"
        MealShopping.TAKEN_OFF -> "Taken off the list"
    }

@Composable
private fun MealCard(
    m: Meal,
    past: Boolean,
    onServings: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).cardLift(MaterialTheme.shapes.large).testTag("meal-${m.id}"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.card,
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).lowPoly(m.title))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                // The title and its state read as one TalkBack node; the servings controls stay their own.
                Column(Modifier.semantics(mergeDescendants = true) {}) {
                    Text(m.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val bits = listOfNotNull(mealState(m), m.source, "No longer saved".takeIf { !m.saved })
                    Text(
                        bits.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (m.shopping.pending && !past) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        modifier = Modifier.testTag("meal-state-${m.id}"),
                    )
                }
                m.servings?.takeIf { !past }?.let { s ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp).semantics(mergeDescendants = true) { contentDescription = "Serves $s" },
                    ) {
                        IconButton(onClick = { onServings(-1) }, enabled = s > 1, modifier = Modifier.testTag("meal-fewer-${m.id}")) {
                            Icon(Icons.Rounded.Remove, contentDescription = "Fewer servings")
                        }
                        Text("Serves $s", style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("meal-serves-${m.id}"))
                        IconButton(
                            onClick = { onServings(1) },
                            enabled = s < MealsViewModel.MAX_SERVINGS,
                            modifier = Modifier.testTag("meal-more-${m.id}"),
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "More servings")
                        }
                    }
                }
            }
            if (!past) {
                IconButton(onClick = onRemove, modifier = Modifier.testTag("meal-remove-${m.id}")) {
                    Icon(Icons.Rounded.Close, contentDescription = "Remove ${m.title}")
                }
            }
        }
    }
}

/**
 * Buy again: one tap adds a chip, a chip already on the list says so and does
 * nothing; a long press pins or hides it. "Add all" and "Repeat last shop" are
 * the two quick ways through.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BuyAgainSection(
    ui: MealsUi,
    actions: MealsActions,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp).testTag("buy-again")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Buy again", Modifier.weight(1f), icon = Icons.Rounded.History)
            if (ui.toAdd.size > 1) {
                TextButton(onClick = actions.onAllRegulars, modifier = Modifier.testTag("buy-again-all")) {
                    Text("Add all ${ui.toAdd.size}")
                }
            }
        }
        if (ui.buyAgain.regulars.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in ui.buyAgain.regulars) RegularChip(r, actions)
            }
        }
        if (ui.buyAgain.lastShop.isNotEmpty()) {
            TextButton(
                onClick = actions.onRepeatLastShop,
                modifier = Modifier.padding(top = 4.dp).testTag("repeat-last-shop"),
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                Icon(Icons.Rounded.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                val names = ui.buyAgain.lastShop.take(3).joinToString(", ") { it.name }
                Text("Add the rest of last shop: $names" + more(ui.buyAgain.lastShop.size))
            }
        }
    }
}

private fun more(n: Int) = if (n > 3) " +${n - 3}" else ""

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RegularChip(
    r: Regular,
    actions: MealsActions,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .clip(PillShape)
                .background(if (r.onList) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.card)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = if (r.onList) null else "Add to the list",
                    onLongClickLabel = "Pin or hide",
                    onLongClick = { menu = true },
                    onClick = { if (!r.onList) actions.onRegular(r) },
                )
                .semantics { stateDescription = if (r.onList) "On the list" else "Not on the list" }
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .testTag("regular-${r.mergeKey}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                if (r.onList) Icons.Rounded.Check else Icons.Rounded.Add,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (r.onList) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
            )
            Text(
                r.name.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelLarge,
                color = if (r.onList) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (!r.pinned) {
                DropdownMenuItem(text = { Text("Always offer") }, onClick = {
                    menu = false
                    actions.onPin(r)
                })
            }
            DropdownMenuItem(text = { Text("Don't suggest") }, onClick = {
                menu = false
                actions.onHide(r)
            })
        }
    }
}

@Composable
private fun HomeHeader(
    ui: MealsUi,
    actions: MealsActions,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp).testTag("home-check")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Already at home?", Modifier.weight(1f), icon = Icons.Rounded.Inventory2)
            if (ui.toCheck.size > 1) {
                TextButton(onClick = { actions.onHave(ui.toCheck) }, modifier = Modifier.testTag("home-have-all")) {
                    Text("Have all ${ui.toCheck.size}")
                }
            }
        }
        Text(
            "Cupboard things the meals need. Have it takes it off this shop's list.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One ingredient, one decision: "Have" or "Need", however many recipes want it. */
@Composable
private fun HomeRowItem(
    r: HomeRow,
    actions: MealsActions,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.card)
            .heightIn(min = 56.dp)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .testTag("home-${r.mergeKey}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // One TalkBack node for the name and what it is for; the button stays its own.
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(r.name.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall)
            Text(
                listOfNotNull("for ${r.from.joinToString(", ")}", "usually at home".takeIf { r.usual }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (r.have) {
            Text(
                "At home",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            TextButton(onClick = { actions.onNeed(r) }, modifier = Modifier.testTag("home-need-${r.mergeKey}")) { Text("Need it") }
        } else {
            OutlinedButton(onClick = { actions.onHave(listOf(r)) }, modifier = Modifier.testTag("home-have-${r.mergeKey}")) {
                Text("Have it")
            }
        }
    }
}

/**
 * The saved recipes to plan: the most recently added first, a handful of them
 * until a search or "Show all", so a long library never buries the screen.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.pickSection(
    ui: MealsUi,
    actions: MealsActions,
    all: Boolean,
    onAll: () -> Unit,
) {
    val shown = if (all || ui.query.isNotBlank()) ui.picks else ui.picks.takeLast(PICKS_SHOWN).reversed()
    item(key = "pick") {
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(if (ui.meals.isEmpty()) "Choose meals" else "Add a meal")
            OutlinedTextField(
                value = ui.query,
                onValueChange = actions.onSearch,
                placeholder = { Text("Search your recipes") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (ui.query.isNotEmpty()) {
                        IconButton(onClick = { actions.onSearch("") }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") }
                    }
                },
                singleLine = true,
                shape = PillShape,
                colors = pillFieldColors(),
                modifier = Modifier.fillMaxWidth().fieldLift().testTag("meals-search"),
            )
            if (ui.picks.isEmpty()) {
                Text("No matches", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    items(shown, key = { "pick-${it.key}" }) { p -> PickRow(p, onAdd = { actions.onPlan(p) }) }
    if (shown.size < ui.picks.size) {
        item(key = "picks-all") {
            TextButton(onClick = onAll, modifier = Modifier.padding(horizontal = 20.dp).testTag("picks-all")) {
                Text("Show all ${ui.picks.size} recipes")
            }
        }
    }
}

/** Recent recipes shown before a search: about a screenful. */
private const val PICKS_SHOWN = 8

@Composable
private fun PickRow(
    p: Pick,
    onAdd: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .cardLift(MaterialTheme.shapes.large)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.card)
            .clickable(onClickLabel = if (p.planned > 0) "Plan again" else "Plan for this week", onClick = onAdd)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            .testTag("pick-${p.key}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).lowPoly(p.title))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    plural(p.lines, "ingredient"),
                    p.source,
                    when (p.planned) {
                        0 -> null
                        1 -> "Planned"
                        else -> "Planned ×${p.planned}"
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (p.planned > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalButton(onClick = onAdd, modifier = Modifier.testTag("pick-add-${p.key}")) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(4.dp))
            Text(if (p.planned > 0) "Again" else "Plan")
        }
    }
}

/**
 * One primary action, chosen by where the week stands: meals waiting to go on
 * the list come first, then shopping. A past week has none.
 */
@Composable
private fun BottomBar(
    ui: MealsUi,
    actions: MealsActions,
) {
    if (!ui.loaded || ui.isPast) return
    val p = ui.preview
    when {
        p != null && ui.pending > 0 ->
            BottomActionBar(navigationBarInset = false) {
                Button(onClick = actions.onAddToList, modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("meals-add-to-list")) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (ui.pending == 1) "Add 1 meal to the list" else "Add ${ui.pending} meals to the list")
                        Text(previewLine(p), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        ui.isCurrent && ui.listHasItems ->
            BottomActionBar(navigationBarInset = false) {
                Button(onClick = actions.onStartShopping, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("meals-shop")) {
                    Icon(Icons.Rounded.ShoppingCart, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Start shopping")
                }
            }
        else -> Unit
    }
}

/** "12 new items · 3 join the list · 2 usually at home": exactly what the button will do. */
private fun previewLine(p: com.robert4lyf.hypershop.data.WeekPreview): String =
    listOfNotNull(
        plural(p.newItems, "new item"),
        p.joined.takeIf { it > 0 }?.let { "$it join the list" },
        p.atHome.takeIf { it > 0 }?.let { "$it usually at home" },
    ).joinToString(" · ")

private fun plural(
    n: Int,
    word: String,
) = if (n == 1) "1 $word" else "$n ${word}s"
