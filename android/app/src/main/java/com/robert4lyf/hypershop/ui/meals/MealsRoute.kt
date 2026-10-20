package com.robert4lyf.hypershop.ui.meals

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.ui.common.BottomActionBar
import com.robert4lyf.hypershop.ui.common.EmptyHero
import com.robert4lyf.hypershop.ui.common.PillShape
import com.robert4lyf.hypershop.ui.common.SectionLabel
import com.robert4lyf.hypershop.ui.common.cardLift
import com.robert4lyf.hypershop.ui.common.fieldLift
import com.robert4lyf.hypershop.ui.common.pillFieldColors
import com.robert4lyf.hypershop.ui.list.show
import com.robert4lyf.hypershop.ui.theme.card
import com.robert4lyf.hypershop.ui.theme.lowPoly
import com.robert4lyf.hypershop.ui.util.factoryFor

data class MealsActions(
    val onAdd: (Pick) -> Unit = {},
    val onServings: (Meal, Int) -> Unit = { _, _ -> },
    val onRemove: (Meal) -> Unit = {},
    val onSearch: (String) -> Unit = {},
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
            onAdd = vm::add,
            onServings = { m, n -> vm.setServings(m, n) },
            onRemove = vm::remove,
            onSearch = vm::search,
            onBack = onBack,
            onList = onList,
        ),
    )
}

/**
 * "Meals this week", stateless: the meals already on the list (servings, what
 * is left to buy, Remove), then the saved recipes to pick from. One tap adds a
 * recipe; one already on the list asks before a second helping.
 */
@Composable
fun MealsScreen(
    ui: MealsUi,
    actions: MealsActions,
) {
    var addAgain by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by rememberSaveable { mutableStateOf<Long?>(null) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Padded for the keyboard, so the recipes under the search field scroll above it.
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Column(Modifier.weight(1f)) {
                    Text("Meals this week", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    if (ui.loaded) {
                        Text(
                            when {
                                ui.meals.isEmpty() -> "Pick what you'll cook; the ingredients go on the list"
                                else -> "${plural(ui.meals.size, "meal")} · ${plural(ui.itemsLeft, "item")} to buy"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().testTag("meals"),
                contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (ui.meals.isNotEmpty()) {
                    item(key = "planned") { SectionLabel("Cooking this week", Modifier.padding(horizontal = 20.dp)) }
                    items(ui.meals, key = { "meal-${it.useId}" }) { m ->
                        MealCard(m, onServings = { actions.onServings(m, it) }, onRemove = { removing = m.useId })
                    }
                }
                if (ui.loaded && !ui.hasLibrary) {
                    item(key = "none") {
                        EmptyHero(
                            "No saved recipes yet",
                            body = "Every recipe you add is saved. Share a link to HyperShop, or tap + on the Recipes tab.",
                        )
                    }
                }
                if (ui.hasLibrary) {
                    item(key = "pick") {
                        Column(
                            Modifier.padding(horizontal = 20.dp).padding(top = if (ui.meals.isEmpty()) 0.dp else 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SectionLabel("Add a meal")
                            OutlinedTextField(
                                value = ui.query,
                                onValueChange = actions.onSearch,
                                placeholder = { Text("Search your recipes") },
                                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                trailingIcon = {
                                    if (ui.query.isNotEmpty()) {
                                        IconButton(
                                            onClick = { actions.onSearch("") },
                                        ) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") }
                                    }
                                },
                                singleLine = true,
                                shape = PillShape,
                                colors = pillFieldColors(),
                                modifier = Modifier.fillMaxWidth().fieldLift().testTag("meals-search"),
                            )
                            if (ui.picks.isEmpty()) {
                                Text(
                                    "No matches",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(ui.picks, key = { "pick-${it.key}" }) { p ->
                        PickRow(p, onAdd = { if (p.onList) addAgain = p.key else actions.onAdd(p) })
                    }
                }
            }
            if (ui.meals.isNotEmpty()) {
                BottomActionBar(navigationBarInset = true) {
                    Button(onClick = actions.onList, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("meals-list")) {
                        Icon(Icons.Rounded.ShoppingCart, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Go to the list")
                    }
                }
            }
        }
    }
    addAgain?.let { key -> ui.picks.firstOrNull { it.key == key } }?.let { p ->
        AlertDialog(
            onDismissRequest = { addAgain = null },
            title = { Text("Add ${p.title} again?") },
            text = { Text("It is already one of this week's meals. Adding it again doubles its ingredients.") },
            confirmButton = {
                TextButton(onClick = {
                    addAgain = null
                    actions.onAdd(p)
                }, modifier = Modifier.testTag("meals-add-again")) { Text("Add again") }
            },
            dismissButton = { TextButton(onClick = { addAgain = null }) { Text("Cancel") } },
        )
    }
    removing?.let { id -> ui.meals.firstOrNull { it.useId == id } }?.let { m ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${m.title}?") },
            text = { Text("Its ingredients come off the list. The recipe stays saved.") },
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
private fun MealCard(
    m: Meal,
    onServings: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).cardLift(MaterialTheme.shapes.large).testTag("meal-${m.useId}"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.card,
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).lowPoly(m.title))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(m.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val bits =
                    listOfNotNull(
                        if (m.itemsLeft == 0) "All bought" else plural(m.itemsLeft, "item"),
                        m.atHome.takeIf { it > 0 }?.let { "$it at home" },
                        m.source,
                    )
                Text(
                    bits.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                m.servings?.let { s ->
                    // One TalkBack node: "Serves 4", with the two buttons as actions.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp).semantics(mergeDescendants = true) { contentDescription = "Serves $s" },
                    ) {
                        IconButton(onClick = { onServings(s - 1) }, enabled = s > 1, modifier = Modifier.testTag("meal-fewer-${m.useId}")) {
                            Icon(Icons.Rounded.Remove, contentDescription = "Fewer servings")
                        }
                        Text(
                            "Serves $s",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.testTag("meal-serves-${m.useId}"),
                        )
                        IconButton(onClick = { onServings(s + 1) }, enabled = s < 99, modifier = Modifier.testTag("meal-more-${m.useId}")) {
                            Icon(Icons.Rounded.Add, contentDescription = "More servings")
                        }
                    }
                }
            }
            IconButton(onClick = onRemove, modifier = Modifier.testTag("meal-remove-${m.useId}")) {
                Icon(Icons.Rounded.Close, contentDescription = "Remove ${m.title}")
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
            .clickable(onClickLabel = if (p.onList) "Add again" else "Add to this week", onClick = onAdd)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            .testTag("pick-${p.key}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).lowPoly(p.title))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(plural(p.lines, "ingredient"), p.source).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (p.onList) {
            Text(
                "On the list",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp),
            )
        } else {
            FilledTonalButton(onClick = onAdd, modifier = Modifier.testTag("pick-add-${p.key}")) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text("Add")
            }
        }
    }
}

private fun plural(
    n: Int,
    word: String,
) = if (n == 1) "1 $word" else "$n ${word}s"
