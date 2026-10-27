package com.robert4lyf.hypershop.ui.recipes

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Inventory
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robert4lyf.hypershop.core.merge.UseState
import com.robert4lyf.hypershop.data.UseSummary
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.ui.common.EmptyHero
import com.robert4lyf.hypershop.ui.common.InfoBanner
import com.robert4lyf.hypershop.ui.common.PillShape
import com.robert4lyf.hypershop.ui.common.ScreenHeader
import com.robert4lyf.hypershop.ui.common.basilSegmentedColors
import com.robert4lyf.hypershop.ui.common.cardLift
import com.robert4lyf.hypershop.ui.common.chip
import com.robert4lyf.hypershop.ui.common.fieldLift
import com.robert4lyf.hypershop.ui.common.label
import com.robert4lyf.hypershop.ui.common.pillFieldColors
import com.robert4lyf.hypershop.ui.common.rememberOpenLink
import com.robert4lyf.hypershop.ui.common.siteName
import com.robert4lyf.hypershop.ui.common.webLink
import com.robert4lyf.hypershop.ui.list.show
import com.robert4lyf.hypershop.ui.review.TitleDialog
import com.robert4lyf.hypershop.ui.theme.Brand
import com.robert4lyf.hypershop.ui.theme.LocalDarkTheme
import com.robert4lyf.hypershop.ui.theme.card
import com.robert4lyf.hypershop.ui.util.factoryFor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipesRoute(
    container: AppContainer,
    snackbar: SnackbarHostState,
    onReview: (Long) -> Unit,
    onMeals: () -> Unit = {},
) {
    val vm: RecipesViewModel = viewModel(factory = factoryFor { RecipesViewModel(container) })
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.events.collect { show(container, snackbar, it) } }
    LaunchedEffect(vm) { vm.openReview.collect(onReview) }
    val chips by vm.searchChips.collectAsStateWithLifecycle()
    val hasKey by container.apiKeyStore.hasKey.collectAsStateWithLifecycle()
    RecipesScreen(
        ui,
        onRemove = vm::remove,
        onEdit = vm::edit,
        onRename = { use, title -> vm.rename(use, title) },
        onOpenUse = vm::openUse,
        library =
            LibraryActions(
                onAdd = vm::addFromLibrary,
                onRemove = vm::removeFromLibrary,
                onOpen = vm::openDetail,
                onRename = { row, title -> vm.renameSaved(row, title) },
                onEdit = vm::editSaved,
                onSort = vm::sortBy,
                onHighlyRated = vm::showHighlyRated,
            ),
        search =
            SearchBox(
                text = vm.searchText,
                chips = chips,
                onText = vm::onSearchText,
                onCommit = vm::commitSearch,
                onRemove = vm::removeChip,
                onClear = vm::clearSearch,
            ),
        add =
            AddRecipeActions(
                onLink = vm::addLink,
                onManual = { n, s, l -> vm.addManual(n, s, l) },
                onPhoto = { uri, book, page -> vm.addPhoto(uri, book, page) },
            ),
        hasKey = hasKey,
        onMeals = onMeals,
    )
    val useDetail by vm.useDetail.collectAsStateWithLifecycle()
    useDetail?.let { d ->
        ModalBottomSheet(onDismissRequest = vm::closeUse) {
            UseDetailContent(
                d,
                photo = d.use.libraryKey?.let { ui.photos[it] },
                notes = d.use.libraryKey?.let { ui.notes[it] },
                onEdit = {
                    vm.closeUse()
                    vm.edit(d.use)
                },
                onRemove = {
                    vm.closeUse()
                    vm.remove(d.use)
                },
            )
        }
    }
    val detail by vm.detail.collectAsStateWithLifecycle()
    // The photo picker: the system's, so no permission; the recipe it is for is kept across the trip there.
    var photoFor by rememberSaveable { mutableStateOf<String?>(null) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val row = ui.libraryRow(photoFor)
            if (uri != null && row != null) vm.choosePhoto(row, uri)
            photoFor = null
        }
    detail?.let { snap ->
        // The sheet follows the library as it changes (stars, notes, a photo arriving), not the moment it opened.
        val d = ui.libraryRow(snap.row.key)?.let { snap.copy(row = it) } ?: snap
        RecipeDetailSheet(
            d,
            onAdd = {
                vm.closeDetail()
                // On the list already: the review asks "Already on your shopping list" itself, once (review B.13).
                vm.addFromLibrary(d.row)
            },
            onRemove = {
                vm.closeDetail()
                vm.removeFromLibrary(d.row)
            },
            onDismiss = vm::closeDetail,
            notes =
                RecipeNoteActions(
                    onRate = vm::rate,
                    onNotes = vm::saveNotes,
                    onChoosePhoto = { row ->
                        photoFor = row.key
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onPagePhoto = vm::pagePhoto,
                    onRemovePhoto = vm::removePhoto,
                ),
        )
    }
}

/** The saved recipe a dialog is about, by its key; null for no dialog (or one whose row has gone). */
private fun RecipesUi.libraryRow(key: String?): LibraryRow? = key?.let { k -> library.firstOrNull { it.key == k } }

/** The Recipes tab, stateless. */
@Composable
fun RecipesScreen(
    ui: RecipesUi,
    onRemove: (UseSummary) -> Unit,
    onEdit: (UseSummary) -> Unit = {},
    onRename: (UseSummary, String) -> Unit = { _, _ -> },
    /** A card tapped: its ingredients. */
    onOpenUse: (UseSummary) -> Unit = {},
    add: AddRecipeActions = AddRecipeActions(),
    library: LibraryActions = LibraryActions(),
    search: SearchBox = SearchBox(),
    /** A Claude key is saved: the photo option needs one. */
    hasKey: Boolean = true,
    /** "Meals this week": pick saved recipes for the list. */
    onMeals: () -> Unit = {},
) {
    // Which use or saved recipe a dialog is about, by id or key, so it survives a rotation.
    var renaming by rememberSaveable { mutableStateOf<Long?>(null) }
    var renamingSaved by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    // Null until a toggle is tapped: your saved recipes (X.12: this tab is the recipe book; the week is its own tab).
    var picked by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val showLibrary = ui.hasLibrary && (picked ?: true)
    // A recipe landing on the shopping list (from the library, a link or a photo) is shown there.
    var seen by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(ui.loaded, ui.uses.size) {
        if (!ui.loaded) return@LaunchedEffect
        val before = seen
        if (before != null && ui.uses.size > before) picked = false
        seen = ui.uses.size
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().testTag("recipes"),
                // Room under the last card's buttons for the + button.
                contentPadding = PaddingValues(bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "top") {
                    val n = ui.uses.count { it.recipeId != null }
                    ScreenHeader(
                        title = "Recipes",
                        subtitle =
                            when {
                                !ui.loaded || n == 0 -> null
                                n == 1 -> "1 recipe in this shop"
                                else -> "$n recipes in this shop"
                            },
                    )
                }
                if (ui.loaded && ui.uses.isEmpty() && !ui.hasLibrary) {
                    item(key = "empty") {
                        EmptyHero(
                            "No recipes yet",
                            body = "Share a recipe link to HyperShop, or tap + to paste a link, type one in or photograph a cookbook page.",
                        )
                    }
                }
                if (ui.hasLibrary) {
                    item(key = "view") {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            SegmentedButton(
                                selected = showLibrary,
                                // No checkmark: it would push the label sideways on every switch; the fill says which is on.
                                icon = {},
                                onClick = { picked = true },
                                shape = SegmentedButtonDefaults.itemShape(0, 2),
                                colors = basilSegmentedColors(),
                                modifier = Modifier.testTag("view-library"),
                            ) { Text("Saved recipes") }
                            SegmentedButton(
                                selected = !showLibrary,
                                icon = {},
                                onClick = { picked = false },
                                shape = SegmentedButtonDefaults.itemShape(1, 2),
                                colors = basilSegmentedColors(),
                                modifier = Modifier.testTag("view-list"),
                            ) { Text("In this shop") }
                        }
                    }
                }
                if (ui.loaded && ui.uses.isEmpty() && ui.hasLibrary && !showLibrary) {
                    item(key = "nothing-yet") {
                        InfoBanner(
                            "No recipes in this shop. Plan meals on This week, or tap + for a new recipe.",
                            Icons.AutoMirrored.Rounded.MenuBook,
                            Modifier.padding(horizontal = 16.dp).testTag("nothing-yet"),
                        )
                    }
                }
                if (!showLibrary) {
                    items(ui.uses, key = { it.useId }) { use ->
                        UseCard(
                            use,
                            photo = use.libraryKey?.let { ui.photos[it] },
                            onRemove = { onRemove(use) },
                            onEdit = { onEdit(use) },
                            onRename = { renaming = use.useId },
                            onOpen = { onOpenUse(use) },
                        )
                    }
                }
                if (showLibrary) {
                    item(key = "library-search") {
                        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            RecipeSearchBox(search)
                            LibraryView(ui, library)
                            if (ui.library.isEmpty() && (ui.terms.isNotEmpty() || ui.highlyRated)) {
                                Text(
                                    if (ui.terms.isEmpty()) "No recipes rated 4★ or 5★ yet" else "No matches",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(ui.library, key = { "lib-${it.key}" }) { row ->
                        LibraryCard(
                            row,
                            showPlanned = ui.sort == LibrarySort.PLANNED,
                            onAdd = { library.onAdd(row) },
                            onRemove = { library.onRemove(row) },
                            onOpen = { library.onOpen(row) },
                            onRename = { renamingSaved = row.key },
                            onEdit = { library.onEdit(row) },
                        )
                    }
                }
            }
            FloatingActionButton(
                onClick = { adding = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).testTag("add-recipe"),
                shape = MaterialTheme.shapes.large,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Icon(Icons.Rounded.Add, contentDescription = "Add a recipe", modifier = Modifier.size(28.dp)) }
        }
    }
    if (adding) AddRecipeSheet(add, onDismiss = { adding = false }, hasKey = hasKey, lastBook = ui.lastBook)
    renaming?.let { id -> ui.uses.firstOrNull { it.useId == id } }?.let { use ->
        val byHand = use.recipeId == null
        TitleDialog(
            use.title,
            onDismiss = { renaming = null },
            heading = if (byHand) "Rename" else "Rename recipe",
            label = if (byHand) "Name, e.g. Household" else "Recipe name",
        ) { onRename(use, it) }
    }
    ui.libraryRow(renamingSaved)?.let { row ->
        TitleDialog(row.title, onDismiss = { renamingSaved = null }) { library.onRename(row, it) }
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
            placeholder = { Text(if (s.chips.isEmpty()) "Search" else "And…") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (s.text.isNotEmpty() || s.chips.isNotEmpty()) {
                    IconButton(onClick = s.onClear) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") }
                }
            },
            singleLine = true,
            shape = PillShape,
            colors = pillFieldColors(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { s.onCommit() }),
            modifier = Modifier.fillMaxWidth().fieldLift().testTag("recipe-search"),
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

/** How "Saved" is ordered, and whether only the highly rated show: two small chips under the search, nothing more. */
@Composable
private fun LibraryView(
    ui: RecipesUi,
    library: LibraryActions,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        var open by remember { mutableStateOf(false) }
        Box {
            AssistChip(
                onClick = { open = true },
                label = { Text(if (ui.terms.isEmpty()) ui.sort.label else "Best match") },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
                enabled = ui.terms.isEmpty(),
                modifier = Modifier.semantics { contentDescription = "Sort: ${ui.sort.label}" }.testTag("library-sort"),
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (s in LibrarySort.entries) {
                    DropdownMenuItem(
                        text = { Text(s.label) },
                        leadingIcon = { if (s == ui.sort) Icon(Icons.Rounded.Check, contentDescription = null) },
                        onClick = {
                            open = false
                            library.onSort(s)
                        },
                        modifier = Modifier.testTag("sort-${s.name}"),
                    )
                }
            }
        }
        FilterChip(
            selected = ui.highlyRated,
            onClick = { library.onHighlyRated(!ui.highlyRated) },
            label = { Text("4★ & up") },
            leadingIcon = { if (ui.highlyRated) Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp)) },
            modifier = Modifier.testTag("filter-highly-rated"),
        )
    }
}

/** What "Your recipes" can do. */
data class LibraryActions(
    val onAdd: (LibraryRow) -> Unit = {},
    val onRemove: (LibraryRow) -> Unit = {},
    /** Tap: see its ingredients. */
    val onOpen: (LibraryRow) -> Unit = {},
    val onRename: (LibraryRow, String) -> Unit = { _, _ -> },
    /** The review screen, saving back to the library only. */
    val onEdit: (LibraryRow) -> Unit = {},
    val onSort: (LibrarySort) -> Unit = {},
    val onHighlyRated: (Boolean) -> Unit = {},
)

/** What the open recipe's sheet can change about it: stars, cooking notes, its photo. */
data class RecipeNoteActions(
    val onRate: (LibraryRow, Int) -> Unit = { _, _ -> },
    val onNotes: (LibraryRow, String) -> Unit = { _, _ -> },
    val onChoosePhoto: (LibraryRow) -> Unit = {},
    val onPagePhoto: (LibraryRow) -> Unit = {},
    val onRemovePhoto: (LibraryRow) -> Unit = {},
)

/** One saved recipe: its name, where it's from, how big, when last added; Add to list. */
@Composable
private fun LibraryCard(
    row: LibraryRow,
    showPlanned: Boolean,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onEdit: () -> Unit,
) {
    val now = remember { System.currentTimeMillis() }
    Surface(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .cardLift(MaterialTheme.shapes.large)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClickLabel = "Show ingredients", onClick = onOpen)
            .testTag("lib-${row.key}"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.card,
    ) {
        Column {
            // The recipe's band, as on its card on the list: its photo, else its own mosaic, the same height either way.
            Box(Modifier.fillMaxWidth().heightIn(min = PHOTO_BAND).clipToBounds().testTag("lib-band-${row.key}")) {
                RecipeBackdrop(row.title, row.photo, Modifier.matchParentSize())
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Brand.OnHero,
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)
                            .testTag("hero-text"),
                )
            }
            Row(
                Modifier.padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    val bits =
                        listOfNotNull(
                            webLink(row.url)?.let { siteName(it) } ?: row.bookRef,
                            row.rating?.let { "★ $it/5" },
                            if (row.lines == 1) "1 ingredient" else "${row.lines} ingredients",
                            row.timesPlanned.takeIf { showPlanned && it > 0 }?.let { if (it == 1) "planned once" else "planned $it times" },
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
                        Text(
                            "On the shopping list",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                FilledTonalButton(onClick = onAdd, modifier = Modifier.testTag("lib-add-${row.key}")) {
                    Text(if (row.onList) "Plan again" else "Plan")
                }
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More for ${row.title}") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, modifier = Modifier.testTag("lib-rename-${row.key}"), onClick = {
                            menu = false
                            onRename()
                        })
                        DropdownMenuItem(text = { Text("Edit") }, modifier = Modifier.testTag("lib-edit-${row.key}"), onClick = {
                            menu = false
                            onEdit()
                        })
                        DropdownMenuItem(text = { Text("Remove from your recipes") }, onClick = {
                            menu = false
                            onRemove()
                        })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UseCard(
    use: UseSummary,
    photo: String?,
    onRemove: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onOpen: () -> Unit,
) {
    val url = webLink(use.url)
    val open = rememberOpenLink()
    Surface(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .cardLift(MaterialTheme.shapes.large)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClickLabel = "Show ingredients", onClick = onOpen)
            .testTag("use-${use.useId}"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.card,
    ) {
        val dark = LocalDarkTheme.current
        val hue = Brand.recipeHue(use.title)
        Column {
            // The recipe's band: its photo, else its own mosaic (the same as its review screen's), the same
            // height either way, with the title along the bottom.
            Box(Modifier.fillMaxWidth().heightIn(min = PHOTO_BAND).clipToBounds().testTag("use-band-${use.useId}")) {
                RecipeBackdrop(use.title, photo, Modifier.matchParentSize())
                Column(
                    Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            Text(
                                use.title,
                                style = MaterialTheme.typography.titleLarge,
                                color = Brand.OnHero,
                                modifier = Modifier.testTag("hero-text"),
                            )
                        }
                        // Everything that changes the recipe, in one place; a tap on the card shows it.
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("more-${use.useId}")) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "More for ${use.title}", tint = Brand.OnHero)
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (url != null) {
                                    DropdownMenuItem(text = {
                                        Text(
                                            "Open recipe",
                                        )
                                    }, modifier = Modifier.testTag("open-${use.useId}"), onClick = {
                                        menu = false
                                        open(url)
                                    })
                                }
                                DropdownMenuItem(text = { Text("Rename") }, modifier = Modifier.testTag("rename-${use.useId}"), onClick = {
                                    menu = false
                                    onRename()
                                })
                                DropdownMenuItem(text = { Text("Edit") }, modifier = Modifier.testTag("edit-${use.useId}"), onClick = {
                                    menu = false
                                    onEdit()
                                })
                                DropdownMenuItem(text = {
                                    Text(
                                        "Remove from shopping list",
                                    )
                                }, modifier = Modifier.testTag("remove-${use.useId}"), onClick = {
                                    menu = false
                                    onRemove()
                                })
                            }
                        }
                    }
                    val site = url?.let { siteName(it) }
                    val book = use.bookRef
                    when {
                        site != null -> Meta(Icons.Rounded.Link, site, Modifier)
                        book != null -> Meta(Icons.AutoMirrored.Rounded.MenuBook, book, Modifier)
                    }
                }
            }
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // The facts as one sentence for TalkBack, chips on screen.
                val bits =
                    listOfNotNull(
                        use.servings?.let { "Serves $it" },
                        use.progress.label(),
                        use.inStockCount.takeIf { it > 0 && use.progress.state != UseState.NOTHING_TO_BUY }?.let { "$it at home" },
                    )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.clearAndSetSemantics { contentDescription = bits.joinToString(" · ") },
                ) {
                    val bg = Brand.tint(hue, dark)
                    val fg = Brand.strong(hue, dark)
                    use.servings?.let { Fact(Icons.Rounded.People, "Serves $it", bg, fg) }
                    val p = use.progress
                    Fact(
                        when (p.state) {
                            UseState.STILL_NEEDED -> Icons.Rounded.ShoppingCart
                            UseState.NOTHING_TO_BUY -> Icons.Rounded.Inventory
                            else -> Icons.Rounded.DoneAll
                        },
                        p.chip(),
                        bg,
                        fg,
                    )
                    if (use.inStockCount > 0 && p.state != UseState.NOTHING_TO_BUY) {
                        Fact(Icons.Rounded.Inventory, "${use.inStockCount} at home", bg, fg)
                    }
                }
            }
        }
    }
}

@Composable
private fun Meta(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
    Surface(shape = PillShape, color = bg, contentColor = fg) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipeDetailSheet(
    detail: RecipeDetail,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    notes: RecipeNoteActions,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) { RecipeDetailContent(detail, onAdd, onRemove, notes) }
}

/** A saved recipe's ingredients, as written, with "Add to list", its page and "Remove from your recipes". */
@Composable
fun RecipeDetailContent(
    detail: RecipeDetail,
    onAdd: () -> Unit,
    onRemove: () -> Unit = {},
    notes: RecipeNoteActions = RecipeNoteActions(),
) {
    val open = rememberOpenLink()
    val url = webLink(detail.row.url)
    val row = detail.row
    LazyColumn(
        Modifier.fillMaxWidth().testTag("recipe-detail"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        row.photo?.let { path ->
            item(key = "photo") {
                RecipePhoto(
                    path,
                    contentDescription = "Photo of ${row.title}",
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.large).testTag("recipe-photo"),
                )
            }
        }
        item(key = "title") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(detail.row.title, style = MaterialTheme.typography.headlineSmall)
                val bits =
                    listOfNotNull(
                        url?.let { siteName(it) } ?: detail.row.bookRef,
                        detail.servings?.let { "Serves $it" },
                        if (detail.row.lines == 1) "1 ingredient" else "${detail.row.lines} ingredients",
                    )
                Text(
                    bits.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DetailButtons(onAdd, url?.let { { open(it) } }, onList = detail.row.onList)
                OutlinedButton(onClick = onRemove, modifier = Modifier.testTag("detail-remove")) { Text("Remove from your recipes") }
            }
        }
        item(key = "yours") {
            // What you keep about it: stars, notes for next time, its photo.
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Your rating", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                StarRating(row.rating, onRate = { notes.onRate(row, it) })
                CookingNotes(row.notes, onSave = { notes.onNotes(row, it) }, modifier = Modifier.padding(top = 4.dp))
                PhotoButtons(
                    hasPhoto = row.photo != null,
                    fromPage = url != null,
                    onChoose = { notes.onChoosePhoto(row) },
                    onPage = { notes.onPagePhoto(row) },
                    onRemove = { notes.onRemovePhoto(row) },
                )
                Text(
                    "Ingredients",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp).semantics { heading() },
                )
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
    onList: Boolean,
) {
    FlowRow(
        Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = onAdd, modifier = Modifier.testTag("detail-add")) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(if (onList) "Plan again" else "Plan")
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

/**
 * A recipe on the list: its ingredients as written, the ones left out as in
 * stock marked so, with Edit (the review screen, where it changes), "Open
 * recipe" and "Remove from list". Hand-added items are listed as they are.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UseDetailContent(
    detail: UseDetail,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    photo: String? = null,
    /** Your cooking notes for it: read here, written on the saved recipe's sheet. */
    notes: String? = null,
) {
    val open = rememberOpenLink()
    val use = detail.use
    val url = webLink(use.url)
    LazyColumn(
        Modifier.fillMaxWidth().testTag("use-detail"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Its photo heads it, as it heads the saved recipe's sheet.
        photo?.let { path ->
            item(key = "photo") {
                RecipePhoto(
                    path,
                    contentDescription = "Photo of ${use.title}",
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.large).testTag("use-photo"),
                )
            }
        }
        item(key = "title") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(use.title, style = MaterialTheme.typography.headlineSmall)
                val bits =
                    listOfNotNull(
                        url?.let { siteName(it) } ?: use.bookRef,
                        use.servings?.let { s -> "Serves $s" + (detail.writtenFor?.let { " (written for $it)" } ?: "") },
                        use.progress.label(),
                    )
                Text(
                    bits.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onEdit, modifier = Modifier.testTag("use-detail-edit")) {
                        Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Edit")
                    }
                    url?.let { u ->
                        OutlinedButton(onClick = { open(u) }) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("Open recipe")
                        }
                    }
                    OutlinedButton(onClick = onRemove, modifier = Modifier.testTag("use-detail-remove")) { Text("Remove from list") }
                }
            }
        }
        notes?.let { n ->
            item(key = "notes") {
                Column(Modifier.padding(top = 8.dp).testTag("use-notes"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Your notes", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(n, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        detail.sections.forEachIndexed { i, (heading, lines) ->
            heading?.let { h ->
                item(key = "h-$i") {
                    Text(h, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                }
            }
            items(lines.size, key = { j -> "l-$i-$j" }) { j ->
                val (text, inStock) = lines[j]
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("•", color = if (inStock) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary)
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (inStock) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (inStock) {
                        Text("At home", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
    }
}

/** How tall a card's band is when it shows the recipe's photo. */
private val PHOTO_BAND = 168.dp
