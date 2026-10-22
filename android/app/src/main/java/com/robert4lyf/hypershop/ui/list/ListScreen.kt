package com.robert4lyf.hypershop.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.settings.ListMode
import com.robert4lyf.hypershop.settings.ListView
import com.robert4lyf.hypershop.ui.common.AisleBadge
import com.robert4lyf.hypershop.ui.common.BottomActionBar
import com.robert4lyf.hypershop.ui.common.CountPill
import com.robert4lyf.hypershop.ui.common.HeroButton
import com.robert4lyf.hypershop.ui.common.ScreenHeader
import com.robert4lyf.hypershop.ui.common.TickMark
import com.robert4lyf.hypershop.ui.common.sectionItem
import com.robert4lyf.hypershop.ui.common.sectionPosition
import com.robert4lyf.hypershop.ui.theme.Brand
import com.robert4lyf.hypershop.ui.theme.LocalDarkTheme
import com.robert4lyf.hypershop.ui.theme.card
import com.robert4lyf.hypershop.ui.theme.style
import kotlin.math.roundToInt

data class ListActions(
    val onToggle: (ListRow) -> Unit,
    val onFinish: () -> Unit,
    val onClearAll: () -> Unit,
    val onOpenItem: (ListRow) -> Unit = {},
    val onShare: () -> Unit = {},
    /** "Need it" on an in-stock ingredient. */
    val onNeed: (InStockRow) -> Unit = {},
    /** The menu's choice of layout: store order, by type, or by recipe. */
    val onView: (ListView) -> Unit = {},
    /** "Shop" on the planning screen, "Plan" on the shop screen. */
    val onMode: (ListMode) -> Unit = {},
)

/**
 * The list (PLAN §3.5), stateless: the count and progress, then [header] (the
 * "Add item" field and cards, Step 5.6b), then one card per aisle, and once
 * something is ticked a "Finish shopping" bar within thumb's reach. This is
 * the planning screen; in shopping mode the same state draws as [ShopScreen].
 */
@Composable
fun ListScreen(
    ui: ListUi,
    actions: ListActions,
    header: LazyListScope.() -> Unit = {},
    empty: @Composable () -> Unit = { Text("Nothing on the list", Modifier.padding(24.dp)) },
) {
    if (ui.shopping) {
        ShopScreen(ui, actions)
        return
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            // Pinned: the count and the menu stay in reach on a long list.
            ListTop(ui, actions)
            Box(Modifier.weight(1f)) {
                if (ui.loaded) ListBody(ui, actions, header, empty) else Loading()
            }
            if (ui.doneCount > 0) {
                // The screen sits above the tab bar, so the bar needs no navigation-bar inset of its own.
                BottomActionBar(navigationBarInset = false) {
                    Button(onClick = actions.onFinish, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("finish")) {
                        Icon(Icons.Rounded.DoneAll, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Finish shopping")
                    }
                }
            }
        }
    }
}

/** Before the first read: a spinner, so the hero doesn't flip from one title to another. */
@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.semantics { contentDescription = "Loading" }.testTag("loading"))
    }
}

@Composable
private fun ListBody(
    ui: ListUi,
    actions: ListActions,
    header: LazyListScope.() -> Unit,
    empty: @Composable () -> Unit,
) {
    val card = MaterialTheme.colorScheme.card
    // Collapsed aisles, by name: kept across tab switches and rotation.
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    // An aisle that gains something to buy opens again, so new items are never hidden.
    // Compared after composition, against a plain (non-state) memo: a tick doesn't
    // cost a second composition, only an aisle that has to open again does.
    val byRecipe = ui.view == ListView.RECIPE
    val leftByAisle =
        remember(ui.aisles, ui.recipes, byRecipe) {
            if (byRecipe) {
                ui.recipes.associate { s -> s.key to s.rows.count { it.state != TickState.DONE } }
            } else {
                ui.aisles.associate { (a, rows) -> a.name to rows.count { it.state != TickState.DONE } }
            }
        }
    val lastLeft = remember { LeftMemo(leftByAisle) }
    LaunchedEffect(leftByAisle) {
        val before = lastLeft.counts
        lastLeft.counts = leftByAisle
        val grew = leftByAisle.filter { (a, n) -> n > (before[a] ?: 0) }.keys
        if (grew.any { it in collapsed }) collapsed = collapsed - grew
    }
    // Rows slide when they move, so a ticked one glides to the bottom of its aisle. Not when
    // an aisle opens or closes or the view switches: then everything below moves at once,
    // and the rows slid the height of the aisle while the aisle titles jumped. For the frames
    // of that change the rows go straight to their new place.
    val layout = collapsed to ui.view
    var settled by remember { mutableStateOf(layout) }
    val still = settled != layout
    LaunchedEffect(layout) {
        withFrameNanos {}
        withFrameNanos {}
        settled = layout
    }
    // Room for the keyboard, so the last aisles scroll above it while the Add field keeps focus.
    // Only the part of the keyboard that overlaps the list counts: the tab bar below it is
    // already clear of the keyboard, and a plain imePadding() would leave that much blank.
    var below by remember { mutableIntStateOf(0) }
    val ime = WindowInsets.ime.getBottom(LocalDensity.current)
    val keyboard = with(LocalDensity.current) { (ime - below).coerceAtLeast(0).toDp() }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { below = (it.findRootCoordinates().size.height - it.boundsInRoot().bottom).roundToInt() }
            .testTag("list"),
        contentPadding = PaddingValues(bottom = 24.dp + keyboard),
    ) {
        header()
        if (ui.isEmpty) item { empty() }
        if (byRecipe) {
            for (section in ui.recipes) {
                val open = section.key !in collapsed
                val count = if (open) section.rows.size + 1 else 1
                item(key = "recipe-${section.key}", contentType = "aisle") {
                    RecipeTitle(
                        section,
                        open,
                        onToggle = { collapsed = if (open) collapsed + section.key else collapsed - section.key },
                        modifier = Modifier.padding(top = 12.dp),
                        count = count,
                    )
                }
                if (open) {
                    itemsIndexed(section.rows, key = { _, r -> "${section.key}-${r.id}" }, contentType = { _, _ -> "Item" }) { i, r ->
                        val tile =
                            if (still) {
                                Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null)
                            } else {
                                Modifier.animateItem()
                            }.sectionItem(sectionPosition(i + 1, count), card)
                        ListRowItem(r, actions, tile)
                    }
                }
            }
        }
        for ((aisle, rows) in if (byRecipe) emptyList() else ui.aisles) {
            val open = aisle.name !in collapsed
            // Categorised: a label above each group, when the aisle has more than one.
            val labelled = ui.categorised && rows.map { it.group }.distinct().size > 1
            val cells = if (labelled) withLabels(rows) else rows.map { Cell.Item(it) }
            val count = if (open) cells.size + 1 else 1
            item(key = "aisle-${aisle.name}", contentType = "aisle") {
                AisleTitle(
                    aisle,
                    rows,
                    open,
                    onToggle = { collapsed = if (open) collapsed + aisle.name else collapsed - aisle.name },
                    modifier = Modifier.padding(top = 12.dp).sectionItem(sectionPosition(0, count), aisle.style.container),
                )
            }
            if (open) {
                itemsIndexed(cells, key = { _, c -> c.key(aisle) }, contentType = { _, c -> c.javaClass.simpleName }) { i, c ->
                    val tile =
                        if (still) {
                            Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null)
                        } else {
                            Modifier.animateItem()
                        }.sectionItem(sectionPosition(i + 1, count), card)
                    when (c) {
                        is Cell.Item -> ListRowItem(c.row, actions, tile)
                        is Cell.Label -> GroupLabel(c.name, tile)
                    }
                }
            }
        }
        if (ui.inStock.isNotEmpty()) inStockSection(ui.inStock, card, actions.onNeed)
    }
}

/**
 * "At home": what you said you already had when reviewing the recipes on the
 * list, to check before you go. Open until closed; "Need it" puts one on the list.
 */
private fun LazyListScope.inStockSection(
    rows: List<InStockRow>,
    card: Color,
    onNeed: (InStockRow) -> Unit,
) {
    item(key = "in-stock", contentType = "aisle") {
        var open by rememberSaveable { mutableStateOf(true) }
        Column {
            val count = if (open) rows.size + 1 else 1
            // The same card as an aisle: a tinted header with a badge and a count pill, then white rows.
            Row(
                Modifier
                    .padding(top = 24.dp)
                    .sectionItem(sectionPosition(0, count), MaterialTheme.colorScheme.secondaryContainer)
                    .fillMaxWidth()
                    .clickable(onClickLabel = if (open) "Collapse" else "Expand", role = Role.Button) { open = !open }
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
                    .semantics(mergeDescendants = true) {
                        heading()
                        stateDescription = if (open) "Expanded" else "Collapsed"
                    }
                    .testTag("in-stock"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Inventory2,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    "At home",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                CountPill("${rows.size}", MaterialTheme.colorScheme.onSecondaryContainer)
                Icon(
                    if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (open) {
                rows.forEachIndexed { i, r ->
                    Row(
                        Modifier
                            .sectionItem(sectionPosition(i + 1, count), card)
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(r.main.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
                            Text(r.from, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onNeed(r) }, modifier = Modifier.testTag("need-${r.key}")) { Text("Need it") }
                    }
                }
            }
        }
    }
}

/** One cell of an aisle's card: a row, or (categorised) a group's label. */
private sealed interface Cell {
    fun key(aisle: AisleCode): Any

    data class Item(
        val row: ListRow,
    ) : Cell {
        override fun key(aisle: AisleCode): Any = row.id
    }

    data class Label(
        val name: String,
    ) : Cell {
        override fun key(aisle: AisleCode): Any = "group-${aisle.name}-$name"
    }
}

/** The rows with a label before each group's first ("Other" for rows without one). */
private fun withLabels(rows: List<ListRow>): List<Cell> =
    buildList {
        var last: String? = null
        rows.forEachIndexed { i, r ->
            val name = r.group ?: "Other"
            if (i == 0 || name != last) add(Cell.Label(name))
            last = name
            add(Cell.Item(r))
        }
    }

@Composable
private fun GroupLabel(
    name: String,
    tile: Modifier,
) {
    Text(
        name,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            tile
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.card)
                .padding(start = 20.dp, top = 12.dp, bottom = 4.dp)
                .semantics { heading() },
    )
}

/** The left-to-buy count per aisle as last seen; deliberately not snapshot state. */
private class LeftMemo(
    var counts: Map<String, Int>,
)

@Composable
private fun ListTop(
    ui: ListUi,
    actions: ListActions,
) {
    val shopping = ui.loaded && !ui.isEmpty
    val total = ui.left + ui.doneCount
    // A confirmation, since Clear all takes everything in one go; it survives a rotation.
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    ScreenHeader(
        title =
            when {
                !ui.loaded -> ""
                shopping -> "${ui.left} left"
                else -> "HyperShop"
            },
        overline = if (ui.loaded && !shopping) null else "Shopping list",
        subtitle =
            when {
                !ui.loaded -> null
                !shopping -> null
                ui.left == 0 -> "All bought"
                else -> "${ui.doneCount} of $total bought"
            },
        actions = {
            if (shopping) {
                // The way into the shop screen: one tap, in reach of either thumb.
                HeroButton(onClick = { actions.onMode(ListMode.SHOP) }, modifier = Modifier.testTag("start-shopping")) {
                    Icon(Icons.Rounded.ShoppingCart, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Shop")
                }
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for ((view, label) in VIEWS) {
                        val on = ui.view == view
                        DropdownMenuItem(
                            text = { Text(label) },
                            trailingIcon = { if (on) Icon(Icons.Rounded.Check, contentDescription = null) },
                            onClick = {
                                menu = false
                                actions.onView(view)
                            },
                            // Read as a radio button, so TalkBack says which one is chosen.
                            modifier =
                                Modifier
                                    .semantics {
                                        role = Role.RadioButton
                                        selected = on
                                    }
                                    .testTag("view-${view.name}"),
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Share list as text") }, enabled = !ui.isEmpty, onClick = {
                        menu = false
                        actions.onShare()
                    })
                    DropdownMenuItem(text = { Text("Clear all") }, enabled = !ui.isEmpty, onClick = {
                        menu = false
                        confirmClear = true
                    })
                }
            }
        },
        below = {
            if (shopping) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else ui.doneCount / total.toFloat() },
                    // The subtitle already says "N of M bought"; the bar is not read out as well.
                    modifier = Modifier.fillMaxWidth().height(8.dp).clearAndSetSemantics {},
                    strokeCap = StrokeCap.Round,
                    gapSize = 4.dp,
                    drawStopIndicator = {},
                    color = Brand.Citrus,
                    trackColor = Color.White.copy(alpha = 0.22f),
                )
            }
        },
    )
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the whole list?") },
            text = { Text(if (total == 1) "1 item" else "$total items") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    actions.onClearAll()
                }, modifier = Modifier.testTag("clear-confirm")) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AisleTitle(
    aisle: AisleCode,
    rows: List<ListRow>,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier,
) {
    val left = rows.count { it.state != TickState.DONE }
    val tint = aisle.style
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClickLabel = if (open) "Collapse" else "Expand", role = Role.Button, onClick = onToggle)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
            .semantics(mergeDescendants = true) {
                heading()
                stateDescription = if (open) "Expanded" else "Collapsed"
            }
            .testTag("aisle-${aisle.name}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AisleBadge(aisle, size = 36.dp)
        Text(aisle.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (left == 0) {
            Text("Done ✓", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        } else {
            // A white pill on the tint, in the aisle's own colour: the count reads at a glance.
            CountPill("$left left", tint.content)
        }
        Icon(
            if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = tint.content,
        )
    }
}

/**
 * The main line as shown: the item's name gets a capital wherever it starts
 * ("2 Onions", "700 ml Milk", "Coriander"), so rows with and without an
 * amount read alike. A plural the name isn't a prefix of ("leaves") gets the
 * first letter capitalised instead.
 */
internal fun displayMain(
    main: String,
    name: String,
): String {
    val at =
        main.indexOf(name, ignoreCase = true).takeIf { it >= 0 && name.isNotEmpty() }
            ?: main.indexOfFirst { it.isLetter() }.takeIf { it >= 0 }
            ?: return main
    return main.substring(0, at) + main[at].uppercaseChar() + main.substring(at + 1)
}

/**
 * The row's sub-lines: "2 already ticked" or the pack note, and the recipe the
 * item came from. The recipe joins the ticked count on one line ("2 already
 * ticked · Weeknight chilli"), but a pack note gets a line of its own.
 */
internal fun subLines(row: ListRow): List<String> {
    val sub = row.sub
    val from = row.from
    return when {
        sub == null -> listOfNotNull(from)
        from == null -> listOf(sub)
        row.state == TickState.PARTIAL -> listOf("$sub · $from")
        else -> listOf(sub, from)
    }
}

/**
 * One row, two targets: only the round tick ticks or unticks (so a stray tap on
 * the row can't); the rest of the row opens the item card, where Delete lives.
 * TalkBack reads the tick as a checkbox named after the item, then the row.
 * DONE rows are greyed, struck through and carry a tick (not colour alone).
 */
@Composable
private fun ListRowItem(
    row: ListRow,
    actions: ListActions,
    tile: Modifier,
) {
    val state =
        when (row.state) {
            TickState.DONE -> "Bought"
            TickState.PARTIAL -> "Partly bought"
            TickState.OPEN -> "Still needed"
        }
    val done = row.state == TickState.DONE
    Row(
        tile
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.card)
            .heightIn(min = 64.dp)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = row.aisle.style
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                // clickable carries the spoken action ("Mark bought"/"Mark not bought") and the role; the
                // name and state are added below (the tick's icons have no labels of their own).
                .clickable(onClickLabel = if (done) "Mark not bought" else "Mark bought", role = Role.Checkbox) { actions.onToggle(row) }
                .semantics {
                    testTag = "tick-${row.id}"
                    contentDescription = row.main
                    stateDescription = state
                },
            contentAlignment = Alignment.Center,
        ) {
            TickMark(
                done = done,
                partial = row.state == TickState.PARTIAL,
                color = tint.content,
                onColor = tint.container,
                softColor = tint.container,
            )
        }
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 64.dp)
                .clickable(onClickLabel = "Details") { actions.onOpenItem(row) }
                .semantics(mergeDescendants = true) {
                    testTag = "item-${row.id}"
                    stateDescription = state
                }.padding(start = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    displayMain(row.main, row.name),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                )
                for (line in subLines(row)) {
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

private val VIEWS = listOf(ListView.STORE to "Store order", ListView.TYPE to "Group by type", ListView.RECIPE to "Group by recipe")

/** A recipe card's header: in the recipe's own colour, or plain for shared and hand-added items. */
@Composable
private fun RecipeTitle(
    section: RecipeSection,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier,
    count: Int,
) {
    val dark = LocalDarkTheme.current
    val left = section.rows.count { it.state != TickState.DONE }
    val (container, content, icon) =
        when (section.kind) {
            RecipeSection.Kind.RECIPE -> {
                val hue = Brand.recipeHue(section.title)
                Triple(Brand.tint(hue, dark), Brand.strong(hue, dark), Icons.AutoMirrored.Rounded.MenuBook)
            }
            RecipeSection.Kind.SHARED ->
                Triple(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, Icons.Rounded.Layers)
            RecipeSection.Kind.BY_HAND ->
                Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, Icons.Rounded.EditNote)
        }
    Row(
        modifier
            .sectionItem(sectionPosition(0, count), container)
            .fillMaxWidth()
            .clickable(onClickLabel = if (open) "Collapse" else "Expand", role = Role.Button, onClick = onToggle)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
            .semantics(mergeDescendants = true) {
                heading()
                stateDescription = if (open) "Expanded" else "Collapsed"
            }
            .testTag("recipe-${section.key}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(content), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = container, modifier = Modifier.size(20.dp))
        }
        Text(
            section.title,
            style = MaterialTheme.typography.titleMedium,
            color = content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (left == 0) {
            Text("Done ✓", style = MaterialTheme.typography.labelLarge, color = content)
        } else {
            CountPill("$left left", content)
        }
        Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, tint = content)
    }
}
