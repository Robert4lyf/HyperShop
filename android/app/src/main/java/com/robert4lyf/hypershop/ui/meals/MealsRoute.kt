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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robert4lyf.hypershop.data.HomeRow
import com.robert4lyf.hypershop.data.MealShopping
import com.robert4lyf.hypershop.data.Regular
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.ui.common.BottomActionBar
import com.robert4lyf.hypershop.ui.common.PillShape
import com.robert4lyf.hypershop.ui.common.SectionLabel
import com.robert4lyf.hypershop.ui.common.cardLift
import com.robert4lyf.hypershop.ui.common.fieldLift
import com.robert4lyf.hypershop.ui.common.label
import com.robert4lyf.hypershop.ui.common.pillFieldColors
import com.robert4lyf.hypershop.ui.list.show
import com.robert4lyf.hypershop.ui.recipes.AddRecipeActions
import com.robert4lyf.hypershop.ui.recipes.AddRecipeSheet
import com.robert4lyf.hypershop.ui.recipes.RecipeArt
import com.robert4lyf.hypershop.ui.theme.card
import com.robert4lyf.hypershop.ui.util.factoryFor

data class MealsActions(
    val onPlan: (Pick) -> Unit = {},
    /** One more (+1) or one fewer (-1) serving. */
    val onServings: (Meal, Int) -> Unit = { _, _ -> },
    val onRemove: (Meal) -> Unit = {},
    /** "Cooked it" on a meal (missing 2 of the review). */
    val onCooked: (Meal) -> Unit = {},
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
    /** Y.19: some of it at home ("300 g"), and taking that back. */
    val onHaveSome: (HomeRow, String, (String?) -> Unit) -> Unit = { _, _, done -> done(null) },
    val onClearSome: (HomeRow) -> Unit = {},
    /** Y.21: lines typed under Miscellaneous (household things, extras). */
    val onAddMisc: (String) -> Unit = {},
    val onStartShopping: () -> Unit = {},
    /** "Go to the list": the Shopping tab, where the combined ingredients are. */
    val onList: () -> Unit = {},
    /** X.10: "New recipe": the add-a-recipe sheet, here where the week is planned. */
    val onNewRecipe: () -> Unit = {},
    /** Step Q: "Check food at home", the focused check of what the week's meals need. */
    val onHomeCheck: () -> Unit = {},
)

@Composable
fun MealsRoute(
    container: AppContainer,
    snackbar: SnackbarHostState,
    onList: () -> Unit,
    onReview: (Long) -> Unit,
    /** Step Q: the focused food-at-home check. */
    onHomeCheck: () -> Unit = {},
) {
    val vm: MealsViewModel = viewModel(factory = factoryFor { MealsViewModel(container) })
    val ui by vm.ui.collectAsStateWithLifecycle()
    val hasKey by container.apiKeyStore.hasKey.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(vm) { vm.events.collect { show(container, snackbar, it) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshWeek() }
    LaunchedEffect(vm) { vm.reviews.collect(onReview) }
    if (adding) {
        AddRecipeSheet(
            AddRecipeActions(
                onLink = vm::addLink,
                onManual = { n, sv, l -> vm.addManual(n, sv, l) },
                onPhoto = { uri, book, page -> vm.addPhoto(uri, book, page) },
            ),
            onDismiss = { adding = false },
            hasKey = hasKey,
        )
    }
    MealsScreen(
        ui,
        MealsActions(
            onPlan = vm::plan,
            onServings = { m, d -> vm.stepServings(m, d) },
            onRemove = vm::remove,
            onCooked = vm::cooked,
            onSearch = vm::search,
            onPreviousWeek = vm::previousWeek,
            onNextWeek = vm::nextWeek,
            onThisWeek = vm::thisWeek,
            onRepeat = vm::repeat,
            // Make shopping list ends on the list, with what it did and its Undo.
            onAddToList = {
                vm.addToList { m ->
                    show(container, snackbar, m)
                    onList()
                }
            },
            onHomeCheck = onHomeCheck,
            onRegular = { vm.addRegular(it) },
            onAllRegulars = { vm.addAllRegulars() },
            onRepeatLastShop = { vm.repeatLastShop() },
            onPin = { vm.pin(it) },
            onHide = { vm.hide(it) },
            onHave = vm::have,
            onNeed = vm::need,
            onHaveSome = { r, t, done -> vm.haveSome(r, t, done) },
            onClearSome = vm::clearSome,
            onAddMisc = vm::addMisc,
            onStartShopping = {
                vm.startShopping()
                onList()
            },
            onList = onList,
            onNewRecipe = { adding = true },
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
    // Step Q: the week's meals and one next step. Choosing a recipe opens from Add meal; Buy again, extras and the
    // food-at-home check live with the shopping list and on their own screen.
    var choosing by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().imePadding()) {
            WeekHeader(ui, actions)
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().testTag("meals"),
                contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (ui.meals.isNotEmpty()) {
                    items(ui.meals, key = { "meal-${it.id}" }) { m ->
                        MealCard(
                            m,
                            past = ui.isPast,
                            onServings = { actions.onServings(m, it) },
                            onRemove = { actions.onRemove(m) },
                            onCooked = { actions.onCooked(m) },
                        )
                    }
                    if (!ui.isPast) {
                        item(key = "add-meal") {
                            OutlinedButton(
                                onClick = { choosing = true },
                                modifier =
                                    Modifier.padding(
                                        horizontal = 16.dp,
                                    ).fillMaxWidth().heightIn(min = 48.dp).testTag("meals-add-meal-more"),
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text("Add meal")
                            }
                        }
                    }
                } else if (ui.loaded) {
                    item(key = "empty") { EmptyWeek(ui) }
                    if (ui.repeatFrom != null && !ui.isPast) item(key = "repeat") { RepeatCard(ui, actions.onRepeat) }
                }
                // Optional, once the meals are on the list: the food-at-home check is a task of its own.
                if (ui.isCurrent && ui.toCheck.isNotEmpty() && ui.meals.any { !it.shopping.pending }) {
                    item(key = "home-check") { CheckHomeRow(ui.toCheck.size, actions.onHomeCheck) }
                }
            }
            BottomBar(ui, actions, onAddMeal = { choosing = true })
        }
    }
    if (choosing) {
        AddMealSheet(
            ui,
            actions.copy(onNewRecipe = {
                choosing = false
                actions.onNewRecipe()
            }),
            onDismiss = {
                choosing = false
                actions.onSearch("")
            },
        )
    }
}

/** No meals yet for the week shown: what this screen is for, and (with no recipes) where they come from. */
@Composable
private fun EmptyWeek(ui: MealsUi) {
    Column(
        Modifier.padding(horizontal = 24.dp, vertical = 24.dp).testTag("meals-empty"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (ui.isPast) "Nothing was planned for this week" else "No meals for ${ui.weekName.ifEmpty { "this week" }.lowercase()} yet",
            style = MaterialTheme.typography.titleMedium,
        )
        if (!ui.isPast) {
            Text(
                if (ui.hasLibrary) {
                    "Add the meals you want to cook, then make one shopping list for them."
                } else {
                    "Save a recipe first: share a recipe link to HyperShop, or tap Add meal › New recipe."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "Check food at home": one row, opening the focused check for everything this week's meals need. */
@Composable
private fun CheckHomeRow(
    n: Int,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag("meals-home-check"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Check food at home", style = MaterialTheme.typography.titleSmall)
            Text(
                "Optional · ${plural(n, "ingredient")} your meals need",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

/**
 * Add meal: the saved recipes, searchable, each with Add (or Add another, when the week has it already). The sheet
 * stays open, so several meals are chosen in one go; Done closes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddMealSheet(
    ui: MealsUi,
    actions: MealsActions,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).imePadding().testTag("add-meal-sheet")) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Add meal to ${ui.weekName.ifEmpty { "this week" }.lowercase()}",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("add-meal-done")) { Text("Done") }
            }
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).fieldLift().testTag("meals-search"),
            )
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().testTag("add-meal-list"),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (ui.loaded && !ui.hasLibrary) {
                    item(key = "none") {
                        Text(
                            "No saved recipes yet. Add one below; it is saved in Recipes for next time.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                } else if (ui.picks.isEmpty() && ui.query.isNotBlank()) {
                    item(key = "no-match") {
                        Text("No matches", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp))
                    }
                }
                items(ui.picks, key = { "pick-${it.key}" }) { p -> PickRow(p, onAdd = { actions.onPlan(p) }) }
                item(key = "new-recipe") {
                    OutlinedButton(
                        onClick = actions.onNewRecipe,
                        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 48.dp).testTag("new-recipe"),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("New recipe: link, typed or photo")
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekHeader(
    ui: MealsUi,
    actions: MealsActions,
) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
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
        com.robert4lyf.hypershop.ui.common.SettingsButton()
    }
}

/** "3 meals · 5 items still needed": what am I cooking, what is left to buy. */
private fun summary(ui: MealsUi): String? =
    when {
        // The next step is the Add meal button; the summary only says where the week stands.
        ui.meals.isEmpty() -> if (ui.isPast) "Nothing was planned" else "Nothing planned yet"
        ui.pending > 0 -> "${plural(ui.meals.size, "meal")} · ${ui.pending} not on your list yet"
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
                Text("Add the same ${plural(ui.repeatTitles.size, "meal")}")
            }
        }
    }
}

private fun mealState(m: Meal): String =
    when (m.shopping) {
        MealShopping.NOT_ON_LIST -> "Not on your list yet"
        MealShopping.TO_BUY -> m.progress!!.label()
        MealShopping.BOUGHT -> "All bought"
        MealShopping.NOTHING_TO_BUY -> "Nothing to buy"
        MealShopping.TAKEN_OFF -> "Taken off your list"
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MealCard(
    m: Meal,
    past: Boolean,
    onServings: (Int) -> Unit,
    onRemove: () -> Unit,
    onCooked: () -> Unit = {},
) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).cardLift(MaterialTheme.shapes.large).testTag("meal-${m.id}"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.card,
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            RecipeArt(m.title, m.photo, Modifier.size(MEAL_ART).clip(RoundedCornerShape(16.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                // The title and its state read as one TalkBack node; the servings controls stay their own.
                Column(Modifier.semantics(mergeDescendants = true) {}) {
                    Text(m.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val bits = listOfNotNull("Cooked".takeIf { m.cooked }, mealState(m), m.source, "No longer saved".takeIf { !m.saved })
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
                // Your own notes for it, as you wrote them on the recipe: read here, edited there.
                m.notes?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                            Modifier.padding(
                                top = 2.dp,
                            ).semantics { contentDescription = "Your notes: $it" }.testTag("meal-notes-${m.id}"),
                    )
                }
                // Servings and "Cooked" on one line under the title, so the title and its state keep the row's width.
                // A flow: on a narrow row or with large text, Cooked goes onto its own line rather than squeezing.
                FlowRow(modifier = Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    m.servings?.takeIf { !past }?.let { s ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp).semantics(mergeDescendants = true) { contentDescription = "Serves $s" },
                        ) {
                            IconButton(onClick = { onServings(-1) }, enabled = s > 1, modifier = Modifier.testTag("meal-fewer-${m.id}")) {
                                Icon(Icons.Rounded.Remove, contentDescription = "Fewer servings")
                            }
                            Text(
                                "Serves $s",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.testTag("meal-serves-${m.id}"),
                            )
                            IconButton(
                                onClick = { onServings(1) },
                                enabled = s < MealsViewModel.MAX_SERVINGS,
                                modifier = Modifier.testTag("meal-more-${m.id}"),
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = "More servings")
                            }
                        }
                    }
                    // "Cooked it": counts for "your usual" and asks for stars; any week's meal, since last week's got cooked too.
                    if (m.saved) {
                        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                            // Step Q: a labelled toggle, so it reads as "record that I cooked it", not "open the recipe".
                            FilterChip(
                                selected = m.cooked,
                                onClick = onCooked,
                                label = { Text("Cooked", maxLines = 1, softWrap = false) },
                                leadingIcon =
                                    if (m.cooked) {
                                        (
                                            {
                                                Icon(
                                                    Icons.Rounded.Check,
                                                    contentDescription = null,
                                                    Modifier.size(18.dp),
                                                )
                                            }
                                        )
                                    } else {
                                        null
                                    },
                                modifier =
                                    Modifier.testTag("meal-cooked-${m.id}").semantics {
                                        contentDescription = if (m.cooked) "${m.title} cooked; tap to undo" else "Mark ${m.title} cooked"
                                    },
                            )
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
internal fun BuyAgainSection(
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
            Text(
                "Hold one to pin it or hide it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
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
                    onClickLabel = if (r.onList) null else "Add to the shopping list",
                    onLongClickLabel = "Pin or hide",
                    onLongClick = { menu = true },
                    onClick = { if (!r.onList) actions.onRegular(r) },
                )
                .semantics { stateDescription = if (r.onList) "On the shopping list" else "Not on the shopping list" }
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
internal fun HomeHeader(
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
            "Check what I already have for this shop. Have it takes unbought recipe amounts off the list.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Y.21: Miscellaneous, for what no recipe needs (toilet roll, bin bags): typed here while the week is planned, so
 * the whole shop is in one place, and on the shopping list under Miscellaneous. One item per line also works.
 */
@Composable
internal fun MiscSection(
    items: List<String>,
    onAdd: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Miscellaneous")
        Text(
            if (items.isEmpty()) {
                "Household things and anything else no recipe needs."
            } else {
                "On the shopping list: ${items.joinToString(", ")}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("misc-items"),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("e.g. toilet roll, 2 bin bags") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions =
                    KeyboardActions(onDone = {
                        if (text.isNotBlank()) onAdd(text)
                        text = ""
                    }),
                modifier = Modifier.weight(1f).testTag("misc-add"),
            )
            FilledTonalButton(
                enabled = text.isNotBlank(),
                onClick = {
                    onAdd(text)
                    text = ""
                },
                modifier = Modifier.testTag("misc-add-go"),
            ) { Text("Add") }
        }
    }
}

/** Y.19: how much of [r] is in the cupboard, against what the week's recipes need together. */
@Composable
internal fun HaveSomeDialog(
    r: HomeRow,
    onSet: (String, (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(r.mergeKey) {
        mutableStateOf(
            r.exactOnHand?.let(com.robert4lyf.hypershop.ui.list.ItemEdit::text) ?: r.onHand.orEmpty(),
        )
    }
    var invalid by rememberSaveable(r.mergeKey) { mutableStateOf(false) }
    var failure by rememberSaveable(r.mergeKey) { mutableStateOf<String?>(null) }
    var submitting by remember(r.mergeKey) { mutableStateOf(false) }
    val error = failure ?: "Enter a positive amount using the requirement's measure, e.g. 300 g.".takeIf { invalid }
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag("home-some-dialog"),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("How much ${r.name} is at home?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                r.needed?.let { Text("The recipes need $it together. Only the rest goes on the shopping list.") }
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        invalid = false
                        failure = null
                    },
                    enabled = !submitting,
                    label = { Text("Amount at home") },
                    placeholder = { Text("e.g. 300 g or 1/2 kg") },
                    isError = error != null,
                    supportingText = error?.let { message -> ({ Text(message) }) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("home-some-amount"),
                )
                // What it will mean, before Set: "300 g at home → buy 200 g". Only for one measure, worked exactly.
                if (error == null) {
                    buyingPreview(text, r.needed)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("home-some-preview"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && !submitting, onClick = {
                val entered = com.robert4lyf.hypershop.core.parse.QuantityInput.parse(text)
                val required = r.needed?.let(com.robert4lyf.hypershop.core.parse.QuantityInput::parse)
                invalid = entered == null || (required != null && entered.keys.any { it !in required.keys })
                if (!invalid) {
                    submitting = true
                    failure = null
                    onSet(text) { message ->
                        submitting = false
                        if (message == null) onDismiss() else failure = message
                    }
                }
            }, modifier = Modifier.testTag("home-some-set")) { Text(if (submitting) "Saving…" else "Set") }
        },
        dismissButton = { TextButton(enabled = !submitting, onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * "300 g at home → buy 200 g": what typing [typed] against [needed] leaves to buy, when both are one amount in the same
 * measure. Null otherwise (nothing typed, an unreadable amount, or measures that do not compare), and no guess is made.
 */
internal fun buyingPreview(
    typed: String,
    needed: String?,
): String? {
    if (typed.isBlank() || needed == null) return null
    val have = com.robert4lyf.hypershop.core.parse.QuantityInput.parse(typed) ?: return null
    val need = com.robert4lyf.hypershop.core.parse.QuantityInput.parse(needed) ?: return null
    val (key, n) = need.parts.entries.singleOrNull() ?: return null
    val h = have.parts.entries.singleOrNull()?.takeIf { it.key == key }?.value ?: return null
    val left = n - h
    return if (left <= com.robert4lyf.hypershop.core.model.Rational.ZERO) {
        "${typed.trim()} at home → nothing to buy"
    } else {
        "${typed.trim()} at home → buy ${com.robert4lyf.hypershop.ui.list.ItemEdit.text(
            com.robert4lyf.hypershop.core.model.QtyBase(mapOf(key to left)),
        ).ifEmpty { left.toString() }}"
    }
}

/** One ingredient, one decision: "Have" or "Need", however many recipes want it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeRowItem(
    r: HomeRow,
    actions: MealsActions,
) {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.card)
            .heightIn(min = 56.dp)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .testTag("home-${r.mergeKey}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // One TalkBack node for the name and what it is for; the button stays its own.
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(r.name.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall)
            // Step Q: the week's combined need, once per ingredient, then what that means for the list.
            val forWhat = if (r.from.size == 1) r.from.single() else "${r.from.size} meals"
            Text(
                listOfNotNull(r.needed?.let { "Need $it" } ?: "Needed", "for $forWhat").joinToString(" "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val outcome =
                when {
                    r.have -> "At home · not on your list"
                    r.onHand != null -> listOfNotNull("${r.onHand} at home", r.remaining?.let { "buy $it" }).joinToString(" → ")
                    r.usual -> "Usually at home"
                    else -> null
                }
            outcome?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("home-outcome-${r.mergeKey}"),
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (r.have) {
                TextButton(onClick = { actions.onNeed(r) }, modifier = Modifier.testTag("home-need-${r.mergeKey}")) { Text("Need it") }
            } else {
                var asking by rememberSaveable(r.mergeKey) { mutableStateOf(false) }
                if (r.needed != null) {
                    TextButton(onClick = {
                        focus.clearFocus()
                        asking = true
                    }, modifier = Modifier.testTag("home-some-${r.mergeKey}")) {
                        Text(if (r.onHand != null) "Change amount" else "Have some")
                    }
                }
                if (r.onHand != null) {
                    TextButton(
                        onClick = { actions.onClearSome(r) },
                        modifier = Modifier.testTag("home-clear-${r.mergeKey}"),
                    ) { Text("Need it") }
                }
                OutlinedButton(onClick = { actions.onHave(listOf(r)) }, modifier = Modifier.testTag("home-have-${r.mergeKey}")) {
                    Text("Have enough")
                }
                if (asking) {
                    HaveSomeDialog(
                        r,
                        onSet = { text, done -> actions.onHaveSome(r, text, done) },
                        onDismiss = { asking = false },
                    )
                }
            }
        }
    }
}

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
            .clickable(onClickLabel = if (p.planned > 0) "Add another" else "Add meal", onClick = onAdd)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            .testTag("pick-${p.key}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RecipeArt(p.title, p.photo, Modifier.size(PICK_ART).clip(RoundedCornerShape(14.dp)))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    p.rating?.let { "★ $it" },
                    plural(p.lines, "ingredient"),
                    p.source,
                    when (p.planned) {
                        0 -> null
                        1 -> "Added"
                        else -> "Added ×${p.planned}"
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (p.planned > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalButton(onClick = onAdd, modifier = Modifier.testTag("pick-add-${p.key}")) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(4.dp))
            Text(if (p.planned > 0) "Add another" else "Add")
        }
    }
}

/**
 * One primary action, chosen by where the week stands (step Q): Add meal with none, Make shopping list while any
 * wait, then View shopping list. A past week has none.
 */
@Composable
private fun BottomBar(
    ui: MealsUi,
    actions: MealsActions,
    onAddMeal: () -> Unit,
) {
    if (!ui.loaded || ui.isPast) return
    val p = ui.preview
    when {
        ui.meals.isEmpty() ->
            BottomActionBar(navigationBarInset = false) {
                Button(onClick = onAddMeal, modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("meals-add-meal")) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Add meal")
                }
            }
        ui.pending > 0 ->
            if (p != null) {
                BottomActionBar(navigationBarInset = false) {
                    Button(
                        onClick = actions.onAddToList,
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("meals-add-to-list"),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Make shopping list")
                            // A later week's meals go on the one list there is: said, never done unseen.
                            Text(
                                previewLine(p) + if (ui.isCurrent) "" else " · adds to your current list",
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        ui.listHasItems ->
            BottomActionBar(navigationBarInset = false) {
                Button(onClick = actions.onList, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("meals-shop")) {
                    Icon(Icons.Rounded.ShoppingCart, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("View shopping list")
                        if (ui.listLeft > 0) Text("${plural(ui.listLeft, "item")} to buy", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        else -> Unit
    }
}

/** "12 new items · 3 join the list · 2 usually at home": exactly what the button will do. */
private fun previewLine(p: com.robert4lyf.hypershop.data.WeekPreview): String =
    listOfNotNull(
        plural(p.newItems, "new item"),
        p.joined.takeIf { it > 0 }?.let { "$it already on it" },
        p.atHome.takeIf { it > 0 }?.let { "$it usually at home" },
    ).joinToString(" · ")

private fun plural(
    n: Int,
    word: String,
) = if (n == 1) "1 $word" else "$n ${word}s"

/** A planned meal's picture: big enough to know the dish by. */
private val MEAL_ART = 72.dp

/** A recipe to pick's picture. */
private val PICK_ART = 56.dp
