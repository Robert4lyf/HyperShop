package com.robert4lyf.hypershop.ui.recipes

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Inventory
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robert4lyf.hypershop.data.UseSummary
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.ui.common.EmptyHero
import com.robert4lyf.hypershop.ui.common.ScreenHeader
import com.robert4lyf.hypershop.ui.common.rememberOpenLink
import com.robert4lyf.hypershop.ui.common.siteName
import com.robert4lyf.hypershop.ui.common.webLink
import com.robert4lyf.hypershop.ui.list.show
import com.robert4lyf.hypershop.ui.theme.Brand
import com.robert4lyf.hypershop.ui.theme.LocalDarkTheme
import com.robert4lyf.hypershop.ui.theme.card
import com.robert4lyf.hypershop.ui.theme.heroGlow
import com.robert4lyf.hypershop.ui.util.factoryFor

@Composable
fun RecipesRoute(
    container: AppContainer,
    snackbar: SnackbarHostState,
    onReview: (Long) -> Unit,
) {
    val vm: RecipesViewModel = viewModel(factory = factoryFor { RecipesViewModel(container) })
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.events.collect { show(container, snackbar, it) } }
    LaunchedEffect(vm) { vm.openReview.collect(onReview) }
    val chips by vm.searchChips.collectAsStateWithLifecycle()
    RecipesScreen(
        ui,
        onRemove = vm::remove,
        onAddInStock = vm::addInStockNow,
        onEdit = vm::edit,
        library = LibraryActions(onAdd = vm::addFromLibrary, onRemove = vm::removeFromLibrary, onOpen = vm::openDetail),
        search =
            SearchBox(
                text = vm.searchText,
                chips = chips,
                onText = vm::onSearchText,
                onCommit = vm::commitSearch,
                onRemove = vm::removeChip,
                onClear = vm::clearSearch,
            ),
        add = AddRecipeActions(onLink = vm::addLink, onManual = { n, s, l -> vm.addManual(n, s, l) }, onPhoto = { vm.addPhoto(it) }),
    )
    val detail by vm.detail.collectAsStateWithLifecycle()
    detail?.let { d ->
        RecipeDetailSheet(
            d,
            onAdd = {
                vm.closeDetail()
                vm.addFromLibrary(d.row)
            },
            onDismiss = vm::closeDetail,
        )
    }
}

/** The Recipes tab, stateless. */
@Composable
fun RecipesScreen(
    ui: RecipesUi,
    onRemove: (UseSummary) -> Unit,
    onAddInStock: (UseSummary) -> Unit,
    onEdit: (UseSummary) -> Unit = {},
    add: AddRecipeActions = AddRecipeActions(),
    library: LibraryActions = LibraryActions(),
    search: SearchBox = SearchBox(),
) {
    var confirm by remember { mutableStateOf<UseSummary?>(null) }
    var forget by remember { mutableStateOf<LibraryRow?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().testTag("recipes"),
                // Room under the last card for the + button.
                contentPadding = PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "top") {
                    val n = ui.uses.count { it.recipeId != null }
                    ScreenHeader(
                        title = "Recipes",
                        subtitle =
                            when {
                                !ui.loaded || n == 0 -> null
                                n == 1 -> "1 recipe on your list"
                                else -> "$n recipes on your list"
                            },
                    )
                }
                if (ui.loaded && ui.uses.isEmpty() && !ui.hasLibrary) {
                    item(key = "empty") {
                        EmptyHero(
                            Icons.AutoMirrored.Rounded.MenuBook,
                            "No recipes yet",
                            body = "No recipes on the list. Tap + to paste a link or type one in, or share a page from your browser.",
                        )
                    }
                }
                items(ui.uses, key = { it.useId }) { use ->
                    UseCard(use, onRemove = { confirm = use }, onAddInStock = { onAddInStock(use) }, onEdit = { onEdit(use) })
                }
                if (ui.hasLibrary) {
                    item(key = "library-title") {
                        Column(
                            Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column {
                                Text("Your recipes", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    when {
                                        ui.terms.isEmpty() -> "Every recipe you've added. The longest since you last made it is at the top."
                                        ui.library.isEmpty() -> "Nothing uses ${ui.terms.joinToString(" or ")}."
                                        else -> "Using the most of ${ui.terms.joinToString(", ")} first."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            RecipeSearchBox(search)
                        }
                    }
                    items(ui.library, key = { "lib-${it.key}" }) { row ->
                        LibraryCard(row, onAdd = { library.onAdd(row) }, onRemove = { forget = row }, onOpen = { library.onOpen(row) })
                    }
                }
            }
            FloatingActionButton(
                onClick = { adding = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).testTag("add-recipe"),
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Icon(Icons.Rounded.Add, contentDescription = "Add a recipe", modifier = Modifier.size(28.dp)) }
        }
    }
    if (adding) AddRecipeSheet(add, onDismiss = { adding = false })
    forget?.let { row ->
        AlertDialog(
            onDismissRequest = { forget = null },
            title = { Text("Remove ${row.title} from your recipes?") },
            text = { Text("It won't be offered here again. Anything from it on the list stays.") },
            confirmButton = {
                TextButton(onClick = {
                    forget = null
                    library.onRemove(row)
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { forget = null }) { Text("Keep") } },
        )
    }
    confirm?.let { use ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Remove ${use.title}?") },
            text = { Text("Its items come off the list, unless another recipe needs them too.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    onRemove(use)
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Keep") } },
        )
    }
}

private const val DAY = DateUtils.DAY_IN_MILLIS

/** "Search your recipes": what's typed, the terms already chips, and what changes them. */
data class SearchBox(
    val text: String = "",
    val chips: List<String> = emptyList(),
    val onText: (String) -> Unit = {},
    val onCommit: () -> Unit = {},
    val onRemove: (String) -> Unit = {},
    val onClear: () -> Unit = {},
)

/**
 * One box for both uses: what you have in ("mushrooms", Enter, "spinach") or a
 * dish you fancy ("curry"). Each Enter makes a chip, so several can be combined.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipeSearchBox(s: SearchBox) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = s.text,
            onValueChange = s.onText,
            placeholder = { Text(if (s.chips.isEmpty()) "Search: an ingredient you have, or a dish" else "And…") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (s.text.isNotEmpty() || s.chips.isNotEmpty()) {
                    IconButton(onClick = s.onClear) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(50),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { s.onCommit() }),
            modifier = Modifier.fillMaxWidth().testTag("recipe-search"),
        )
        if (s.chips.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in s.chips) {
                    InputChip(
                        selected = true,
                        onClick = { s.onRemove(c) },
                        label = { Text(c) },
                        trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = "Remove $c", modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.testTag("chip-$c"),
                    )
                }
            }
        }
    }
}

/** What "Your recipes" can do. */
data class LibraryActions(
    val onAdd: (LibraryRow) -> Unit = {},
    val onRemove: (LibraryRow) -> Unit = {},
    /** Tap: see its ingredients. */
    val onOpen: (LibraryRow) -> Unit = {},
)

/** One saved recipe: its name, where it's from, how big, when last added; Add to list. */
@Composable
private fun LibraryCard(
    row: LibraryRow,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
) {
    val dark = LocalDarkTheme.current
    val hue = Brand.recipeHue(row.title)
    val now = remember { System.currentTimeMillis() }
    Surface(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClickLabel = "Show ingredients", onClick = onOpen)
            .testTag("lib-${row.key}"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.card,
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Brand.tint(hue, dark)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.MenuBook,
                    contentDescription = null,
                    tint = Brand.strong(hue, dark),
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(row.title, style = MaterialTheme.typography.titleMedium)
                val bits =
                    listOfNotNull(
                        webLink(row.url)?.let { siteName(it) },
                        if (row.lines == 1) "1 ingredient" else "${row.lines} ingredients",
                        "added " + DateUtils.getRelativeTimeSpanString(row.lastAddedAt, now, DAY).toString().lowercase(),
                    )
                Text(
                    bits.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                row.uses?.let {
                    Text("Uses $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                }
                if (row.onList) {
                    Text("On your list", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            FilledTonalButton(onClick = onAdd, modifier = Modifier.testTag("lib-add-${row.key}")) { Text("Add") }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Remove from your recipes") }, onClick = {
                        menu = false
                        onRemove()
                    })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UseCard(
    use: UseSummary,
    onRemove: () -> Unit,
    onAddInStock: () -> Unit,
    onEdit: () -> Unit,
) {
    val url = webLink(use.url)
    val open = rememberOpenLink()
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("use-${use.useId}"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.card,
    ) {
        val dark = LocalDarkTheme.current
        val hue = Brand.recipeHue(use.title)
        Column {
            // The recipe's own colour band: the same hue as its review screen.
            Box(Modifier.fillMaxWidth().background(Brand.recipeBrush(use.title)).heroGlow()) {
                Icon(
                    Icons.AutoMirrored.Rounded.MenuBook,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.16f),
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).size(72.dp),
                )
                Column(Modifier.padding(20.dp).padding(end = 56.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(use.title, style = MaterialTheme.typography.titleLarge, color = Brand.OnHero)
                    val site = url?.let { siteName(it) }
                    when {
                        site != null -> Meta(Icons.Rounded.Link, site)
                        use.recipeId == null -> Meta(Icons.Rounded.EditNote, "Typed into the list")
                    }
                }
            }
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // The facts as one sentence for TalkBack, chips on screen.
                val bits =
                    listOfNotNull(
                        use.servings?.let { "Serves $it" },
                        if (use.itemCount == 1) "1 item on the list" else "${use.itemCount} items on the list",
                        use.inStockCount.takeIf { it > 0 }?.let { "$it in stock" },
                    )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.clearAndSetSemantics { contentDescription = bits.joinToString(" · ") },
                ) {
                    val bg = Brand.tint(hue, dark)
                    val fg = Brand.strong(hue, dark)
                    use.servings?.let { Fact(Icons.Rounded.People, "Serves $it", bg, fg) }
                    Fact(Icons.Rounded.ShoppingCart, if (use.itemCount == 1) "1 item" else "${use.itemCount} items", bg, fg)
                    if (use.inStockCount > 0) Fact(Icons.Rounded.Inventory, "${use.inStockCount} in stock", bg, fg)
                }
                // Wraps on narrow phones and large text instead of squeezing the second button.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (url != null) {
                        FilledTonalButton(onClick = { open(url) }, modifier = Modifier.testTag("open-${use.useId}")) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("Open recipe")
                        }
                    }
                    // Only recipes can be edited ("Added by hand" items are edited on the list).
                    if (use.recipeId != null) {
                        OutlinedButton(onClick = onEdit, modifier = Modifier.testTag("edit-${use.useId}")) {
                            Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("Edit")
                        }
                    }
                    OutlinedButton(onClick = onRemove) { Text("Remove from list") }
                }
                if (use.inStockCount > 0) {
                    TextButton(
                        onClick = onAddInStock,
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) { Text("Add the in-stock items now") }
                }
            }
        }
    }
}

@Composable
private fun Meta(
    icon: ImageVector,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = Brand.OnHeroSoft, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Brand.OnHeroSoft)
    }
}

@Composable
private fun Fact(
    icon: ImageVector,
    text: String,
    bg: Color,
    fg: Color,
) {
    Surface(shape = RoundedCornerShape(50), color = bg) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipeDetailSheet(
    detail: RecipeDetail,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) { RecipeDetailContent(detail, onAdd) }
}

/** A saved recipe's ingredients, as written, with "Add to list" and its page. */
@Composable
fun RecipeDetailContent(
    detail: RecipeDetail,
    onAdd: () -> Unit,
) {
    val open = rememberOpenLink()
    val url = webLink(detail.row.url)
    LazyColumn(
        Modifier.fillMaxWidth().testTag("recipe-detail"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "title") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(detail.row.title, style = MaterialTheme.typography.headlineSmall)
                val bits =
                    listOfNotNull(
                        url?.let { siteName(it) },
                        detail.servings?.let { "Serves $it" },
                        if (detail.row.lines == 1) "1 ingredient" else "${detail.row.lines} ingredients",
                    )
                Text(
                    bits.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DetailButtons(onAdd, url?.let { { open(it) } })
            }
        }
        detail.sections.forEachIndexed { i, (heading, lines) ->
            heading?.let { h ->
                item(key = "h-$i") {
                    Text(h, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                }
            }
            items(lines.size, key = { j -> "l-$i-$j" }) { j ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("•", color = MaterialTheme.colorScheme.primary)
                    Text(lines[j], style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailButtons(
    onAdd: () -> Unit,
    onOpen: (() -> Unit)?,
) {
    FlowRow(
        Modifier.padding(top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = onAdd, modifier = Modifier.testTag("detail-add")) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Add to list")
        }
        onOpen?.let {
            OutlinedButton(onClick = it) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Open recipe")
            }
        }
    }
}
