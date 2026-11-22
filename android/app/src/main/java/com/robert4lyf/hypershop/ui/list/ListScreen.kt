package com.robert4lyf.hypershop.ui.list

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
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
import com.robert4lyf.hypershop.settings.ListView
import com.robert4lyf.hypershop.ui.common.AisleHeading
import com.robert4lyf.hypershop.ui.common.BottomActionBar
import com.robert4lyf.hypershop.ui.common.CountPill
import com.robert4lyf.hypershop.ui.common.ScreenHeader
import com.robert4lyf.hypershop.ui.common.TickMark
import com.robert4lyf.hypershop.ui.common.sectionItem
import com.robert4lyf.hypershop.ui.common.sectionPosition
import com.robert4lyf.hypershop.ui.recipes.RecipeArt
import com.robert4lyf.hypershop.ui.review.TitleDialog
import com.robert4lyf.hypershop.ui.theme.Brand
import com.robert4lyf.hypershop.ui.theme.LocalDarkTheme
import com.robert4lyf.hypershop.ui.theme.card
import com.robert4lyf.hypershop.ui.theme.style
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class ListActions(
    val onToggle: (ListRow) -> Unit,
    val onFinish: () -> Unit,
    val onClearAll: () -> Unit,
    val onOpenItem: (ListRow) -> Unit = {},
    /** Step AD.10: swiped away, or TalkBack's "Remove": its unbought share comes off. */
    val onRemove: (ListRow) -> Unit = {},
    val onShare: () -> Unit = {},
    /** The menu's choice of layout: store order or by recipe. */
    val onView: (ListView) -> Unit = {},
    /** Step Z: "Change store" in the menu, with two or more stores to choose from. */
    val onChangeStore: () -> Unit = {},
    val onShareText: () -> Unit = {},
    /** X.9: "Arrange items" and its "Done"; a row or a group moved one place (-1 up, +1 down), or to an end (true = top). */
    val onArrange: (Boolean) -> Unit = {},
    val onMoveItem: (ListRow, Int) -> Unit = { _, _ -> },
    val onMoveItemTo: (ListRow, Boolean) -> Unit = { _, _ -> },
    val onMoveGroup: (AisleCode, String?, Int) -> Unit = { _, _, _ -> },
    /** The recipe view's card menu (cut 1 of the review): its ingredients, Edit, Rename, Remove from list. */
    val onUseOpen: (RecipeSection) -> Unit = {},
    val onUseEdit: (RecipeSection) -> Unit = {},
    val onUseRename: (RecipeSection, String) -> Unit = { _, _ -> },
    val onUseRemove: (RecipeSection) -> Unit = {},
    /** Step Z: "Servings" on a recipe's card. */
    val onUseServings: (RecipeSection, Int) -> Unit = { _, _ -> },
)

/**
 * The list (PLAN §3.5), stateless: the count and progress, then [header] (the
 * "Add item" field and cards, Step 5.6b), then one card per aisle, and once
 * something is ticked a "Finish shopping" bar within thumb's reach. Step Z: the
 * one list screen, for planning and in the shop alike (there is no shopping mode).
 */
@Composable
fun ListScreen(
    ui: ListUi,
    actions: ListActions,
    header: LazyListScope.() -> Unit = {},
    empty: @Composable () -> Unit = { Text("Nothing on the list", Modifier.padding(24.dp)) },
    /** Above zero while an Add field request (widget, launcher shortcut) is waiting: the list goes to its top. */
    focusAdd: Int = 0,
    /** After the items: the optional extras (Buy again, Check food at home), never above what is to buy. */
    footer: LazyListScope.() -> Unit = {},
    /** Step AA: the Add item field, pinned under the top row (not while arranging). */
    addField: @Composable () -> Unit = {},
    /** Step AB: rows a hand add just put on or changed, lit for a moment where they are. */
    flash: Set<Long> = emptySet(),
) {
    // Missing 1 of the review: a list that locks every 30 s is the first thing a shopper notices. Kept awake once
    // something is ticked, which is when a shop is under way.
    val view = LocalView.current
    val shopping = ui.doneCount > 0
    DisposableEffect(view, shopping) {
        view.keepScreenOn = shopping
        onDispose { view.keepScreenOn = false }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            // Pinned: the count and the menu stay in reach on a long list.
            ListTop(ui, actions)
            if (ui.loaded && !ui.arranging) addField()
            Box(Modifier.weight(1f)) {
                if (ui.loaded) ListBody(ui, actions, header, empty, focusAdd, footer, flash) else Loading()
            }
            if (ui.arranging) {
                BottomActionBar(navigationBarInset = false) {
                    Button(
                        onClick = { actions.onArrange(false) },
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("arrange-done"),
                    ) { Text("Done arranging") }
                }
            } else if (ui.doneCount > 0) {
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
    focusAdd: Int = 0,
    footer: LazyListScope.() -> Unit = {},
    flash: Set<Long> = emptySet(),
) {
    val card = MaterialTheme.colorScheme.card
    // A row just ticked stays where it is for a moment, tick filled, so a mis-tap is undone by tapping it again; then
    // it folds into its aisle's "N bought" row. Only the latest tick's timer lets the row fold.
    var stamps by remember { mutableStateOf(emptyMap<Long, Int>()) }
    val lingering = stamps.keys
    val scope = rememberCoroutineScope()
    val actions =
        remember(actions) {
            actions.copy(onToggle = { row ->
                if (row.state != TickState.DONE) {
                    val stamp = (stamps[row.id] ?: 0) + 1
                    stamps = stamps + (row.id to stamp)
                    scope.launch {
                        delay(LINGER_MS)
                        if (stamps[row.id] == stamp) stamps = stamps - row.id
                    }
                }
                actions.onToggle(row)
            })
        }
    // Aisles whose bought rows are shown: kept across tab switches and rotation.
    var shownBought by rememberSaveable { mutableStateOf(emptyList<String>()) }
    // A request for the Add field brings it on screen, so it is focused now rather than whenever it next scrolls in.
    val listState = rememberLazyListState()
    LaunchedEffect(focusAdd) { if (focusAdd > 0) listState.scrollToItem(0) }
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
        state = listState,
        modifier =
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { below = (it.findRootCoordinates().size.height - it.boundsInRoot().bottom).roundToInt() }
                .testTag("list"),
        // Room at the end for a snackbar (List ready, Undo): the extras below the items scroll clear of it.
        contentPadding = PaddingValues(bottom = 96.dp + keyboard),
    ) {
        if (!ui.arranging) header()
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
                        actions = actions,
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
                        ListRowItem(r, actions, tile, lit = r.id in flash, showFrom = true)
                    }
                }
            }
        }
        for ((aisle, rows) in if (byRecipe) emptyList() else ui.aisles) {
            val open = aisle.name !in collapsed
            // Step Z: bought rows fold under one "N bought" row per aisle (all of them shown while arranging).
            val toBuy = if (ui.arranging) rows else rows.filter { it.state != TickState.DONE || it.id in lingering }
            val bought = rows - toBuy.toSet()
            val showBought = aisle.name in shownBought
            // Categorised: a label above each group, when the aisle has more than one.
            val groups = rows.map { it.group }.distinct()
            // Group labels whenever an aisle has more than one group (cut 5 of the review: the "Group by type" view went).
            // Step AC: labels only for a long aisle (six or more items), and always while arranging (they move the groups);
            // the groups keep their order either way.
            val labelled = groups.size > 1 && (ui.arranging || rows.size >= GROUP_LABELS_FROM)
            val cells =
                (if (labelled) withLabels(toBuy) else toBuy.map { Cell.Item(it) }) +
                    (if (showBought) bought.map { Cell.Item(it) } else emptyList()) +
                    (if (bought.isEmpty()) emptyList() else listOf(Cell.Bought(bought.size, showBought)))
            val count = if (open) cells.size + 1 else 1
            item(key = "aisle-${aisle.name}", contentType = "aisle") {
                AisleTitle(
                    aisle,
                    rows,
                    open,
                    onToggle = { collapsed = if (open) collapsed + aisle.name else collapsed - aisle.name },
                    // Step AC: one slim row in the card's own colour, not a tall coloured block.
                    modifier = Modifier.padding(top = 8.dp).sectionItem(sectionPosition(0, count), card),
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
                        is Cell.Item ->
                            if (ui.arranging) {
                                val inGroup = rows.filter { it.group == c.row.group }
                                ArrangeRow(c.row, inGroup.first() != c.row, inGroup.last() != c.row, actions, tile)
                            } else {
                                ListRowItem(c.row, actions, tile, lit = c.row.id in flash)
                            }
                        is Cell.Label ->
                            if (ui.arranging) {
                                ArrangeGroup(aisle, c, groups.first() != c.group, groups.last() != c.group, actions, tile)
                            } else {
                                GroupLabel(c.name, tile)
                            }
                        is Cell.Bought ->
                            BoughtRow(aisle, c, tile) {
                                shownBought = if (c.shown) shownBought - aisle.name else shownBought + aisle.name
                            }
                    }
                }
            }
        }
        if (!ui.arranging) footer()
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
        val group: String?,
    ) : Cell {
        override fun key(aisle: AisleCode): Any = "group-${aisle.name}-$name"
    }

    /** "3 bought": the aisle's bought rows, out of the way until asked for (and the untick one tap away). */
    data class Bought(
        val count: Int,
        val shown: Boolean,
    ) : Cell {
        override fun key(aisle: AisleCode): Any = "bought-${aisle.name}"
    }
}

/** How long a ticked row stays in place before it folds into "N bought". */
internal const val LINGER_MS = 2_500L

/** Step AC: an aisle with this many items or more on the list shows its group labels. */
internal const val GROUP_LABELS_FROM = 6

@Composable
private fun BoughtRow(
    aisle: AisleCode,
    cell: Cell.Bought,
    tile: Modifier,
    onToggle: () -> Unit,
) {
    Row(
        tile
            .background(MaterialTheme.colorScheme.card)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = if (cell.shown) "Hide bought" else "Show bought", role = Role.Button, onClick = onToggle)
            .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
            .semantics(mergeDescendants = true) { stateDescription = if (cell.shown) "Expanded" else "Collapsed" }
            .testTag("bought-${aisle.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${cell.count} bought",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (cell.shown) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The rows with a label before each group's first ("Other" for rows without one). */
private fun withLabels(rows: List<ListRow>): List<Cell> =
    buildList {
        var last: String? = null
        rows.forEachIndexed { i, r ->
            val name = r.group ?: "Other"
            if (i == 0 || name != last) add(Cell.Label(name, r.group))
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

/** X.9: a group's label while arranging, with arrows that move the whole group. */
@Composable
private fun ArrangeGroup(
    aisle: AisleCode,
    label: Cell.Label,
    canUp: Boolean,
    canDown: Boolean,
    actions: ListActions,
    tile: Modifier,
) {
    Row(
        tile.fillMaxWidth().background(MaterialTheme.colorScheme.card).padding(start = 20.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        MoveArrows("${label.name} group", "group-${aisle.name}-${label.name}", canUp, canDown) {
            actions.onMoveGroup(aisle, label.group, it)
        }
    }
}

/** X.9: a row while arranging: what it is, and arrows instead of the tick. */
@Composable
private fun ArrangeRow(
    row: ListRow,
    canUp: Boolean,
    canDown: Boolean,
    actions: ListActions,
    tile: Modifier,
) {
    Row(
        tile.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            displayMain(row.main, row.name),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        MoveArrows(row.name, "${row.id}", canUp, canDown) { actions.onMoveItem(row, it) }
        // UX review 17: across a long aisle, one tap instead of eleven.
        var menu by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("more-${row.id}")) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "More for ${row.name}")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Move to top") }, enabled = canUp, onClick = {
                    menu = false
                    actions.onMoveItemTo(row, true)
                }, modifier = Modifier.testTag("top-${row.id}"))
                DropdownMenuItem(text = { Text("Move to bottom") }, enabled = canDown, onClick = {
                    menu = false
                    actions.onMoveItemTo(row, false)
                }, modifier = Modifier.testTag("bottom-${row.id}"))
            }
        }
    }
}

@Composable
private fun MoveArrows(
    what: String,
    tag: String,
    canUp: Boolean,
    canDown: Boolean,
    onMove: (Int) -> Unit,
) {
    IconButton(onClick = { onMove(-1) }, enabled = canUp, modifier = Modifier.testTag("up-$tag")) {
        Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move $what up")
    }
    IconButton(onClick = { onMove(+1) }, enabled = canDown, modifier = Modifier.testTag("down-$tag")) {
        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Move $what down")
    }
}

/** The left-to-buy count per aisle as last seen; deliberately not snapshot state. */
private class LeftMemo(
    var counts: Map<String, Int>,
)

/**
 * The top of the list. Step AA: once the list has items, one compact row (the count, the progress bar, the gear and the
 * menu) so the aisles start high on a small phone; the mosaic banner only on the empty list (and while loading).
 */
@Composable
private fun ListTop(
    ui: ListUi,
    actions: ListActions,
) {
    val shopping = ui.loaded && !ui.isEmpty
    val total = ui.left + ui.doneCount + ui.unavailable
    // While loading, the compact row too: most opens have a list, so the top does not jump from banner to row.
    if (ui.loaded && !shopping) {
        ScreenHeader(
            title = "Shopping list",
            actions = {
                com.robert4lyf.hypershop.ui.common.SettingsButton()
                ListMenu(ui, actions)
            },
        )
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).testTag("list-count")) {
            val title = if (shopping) listCount(ui.left, ui.doneCount) else "Shopping list"
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            // Said only when there is something to say: set aside on this trip, and whether the watch has the list.
            listOfNotNull(ui.unavailable.takeIf { it > 0 }?.let { "$it set aside" }, ui.watch)
                .takeIf { it.isNotEmpty() }
                ?.let {
                    Text(
                        it.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            if (shopping) Spacer(Modifier.height(6.dp))
            if (shopping) {
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else ui.doneCount / total.toFloat() },
                    // The count already says it; the bar is not read out as well.
                    modifier = Modifier.fillMaxWidth().height(6.dp).clearAndSetSemantics {},
                    strokeCap = StrokeCap.Round,
                    gapSize = 4.dp,
                    drawStopIndicator = {},
                )
            }
        }
        com.robert4lyf.hypershop.ui.common.SettingsButton()
        ListMenu(ui, actions)
    }
}

/** "5 to buy · 2 bought", "5 to buy" before anything is ticked, "All bought" at the end. */
internal fun listCount(
    left: Int,
    bought: Int,
): String =
    when {
        left == 0 -> "All bought"
        bought == 0 -> "$left to buy"
        else -> "$left to buy · $bought bought"
    }

/** The list's ⋮ menu. */
@Composable
private fun ListMenu(
    ui: ListUi,
    actions: ListActions,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("list-more")) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "More")
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Share shopping list") }, onClick = {
                menu = false
                actions.onShareText()
            })
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
            // Missing 4 of the review: which store's walking order; only offered when there is a choice.
            if (ui.secondStore) {
                DropdownMenuItem(text = { Text("Change store (${ui.storeName})") }, onClick = {
                    menu = false
                    actions.onChangeStore()
                }, modifier = Modifier.testTag("change-store"))
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Arrange items") },
                enabled = !ui.isEmpty && ui.view != ListView.RECIPE,
                onClick = {
                    menu = false
                    actions.onArrange(true)
                },
                modifier = Modifier.testTag("arrange"),
            )
            DropdownMenuItem(text = { Text("Share list as text") }, enabled = !ui.isEmpty, onClick = {
                menu = false
                actions.onShare()
            })
            // No "are you sure?": the snackbar's Undo puts the list back.
            DropdownMenuItem(text = { Text("Clear all") }, enabled = !ui.isEmpty, onClick = {
                menu = false
                actions.onClearAll()
            })
        }
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
    AisleHeading(
        aisle,
        if (left == 0) "Bought ✓" else "$left to buy",
        modifier
            .clickable(onClickLabel = if (open) "Collapse" else "Expand", role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) {
                heading()
                stateDescription = if (open) "Expanded" else "Collapsed"
            }.testTag("aisle-${aisle.name}"),
        countColor = if (left == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Icon(
            if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = tint.content,
        )
    }
}

/**
 * The main line as shown: a capital at the start of the row and nowhere else ("2 tins chopped tomatoes",
 * "700 ml milk", "Coriander"): a capital mid-line read as a typo (UX review 9). [name] is the item's name, kept
 * for callers; it no longer moves the capital.
 */
@Suppress("UNUSED_PARAMETER")
internal fun displayMain(
    main: String,
    name: String,
): String = main.replaceFirstChar { it.uppercaseChar() }

/**
 * The row's sub-lines: "2 already bought" or the pack note. Step AD.6: the recipe an item came from is shown only when
 * the list is grouped by recipe ([showFrom]; the shared card names its recipes); in store order it is in the row's
 * TalkBack description and on the item's sheet. With both, it joins the bought count on one line ("2 already bought ·
 * Weeknight chilli"), but a pack note gets a line of its own.
 */
internal fun subLines(
    row: ListRow,
    showFrom: Boolean = false,
): List<String> {
    val sub = row.sub
    val from = row.from?.takeIf { showFrom }
    return when {
        sub == null -> listOfNotNull(from)
        from == null -> listOf(sub)
        row.state == TickState.PARTIAL -> listOf("$sub · $from")
        else -> listOf(sub, from)
    }
}

/**
 * Step AD.10: a row still to buy can be swiped away, either way: while it moves, a labelled "Remove" shows under it.
 * Only its unbought share comes off ([ListActions.onRemove]); a bought row isn't swiped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListRowItem(
    row: ListRow,
    actions: ListActions,
    tile: Modifier,
    lit: Boolean = false,
    showFrom: Boolean = false,
) {
    if (row.state == TickState.DONE) {
        ListRowBody(row, actions, tile, lit, showFrom)
        return
    }
    // A new state when the row changes, so a row that stays (partly bought) comes back settled.
    key(row.main, row.state) {
        val swipe =
            rememberSwipeToDismissBoxState(confirmValueChange = { v ->
                if (v != SwipeToDismissBoxValue.Settled) actions.onRemove(row)
                true
            })
        SwipeToDismissBox(
            swipe,
            modifier = tile.testTag("swipe-${row.id}"),
            backgroundContent = { RemoveBackground(swipe.dismissDirection) },
        ) { ListRowBody(row, actions, Modifier, lit, showFrom) }
    }
}

/** What shows under a row being swiped: "Remove", on the side it is uncovered from. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoveBackground(direction: SwipeToDismissBoxValue) {
    // Only while the row moves: at rest nothing is under it (and TalkBack doesn't find a "Remove" on every row).
    if (direction == SwipeToDismissBoxValue.Settled) return
    Row(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (direction == SwipeToDismissBoxValue.StartToEnd) Arrangement.Start else Arrangement.End,
    ) {
        Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
        Spacer(Modifier.width(8.dp))
        Text("Remove", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

/**
 * One row, two targets: only the round tick ticks or unticks (so a stray tap on
 * the row can't); the rest of the row opens the item card, where Delete lives.
 * TalkBack reads the tick as a checkbox named after the item, then the row.
 * DONE rows are greyed, struck through and carry a tick (not colour alone).
 */
@Composable
private fun ListRowBody(
    row: ListRow,
    actions: ListActions,
    tile: Modifier,
    /** Step AB: just added by hand: lit, then back to the card (seen, not announced). */
    lit: Boolean = false,
    /** Step AD.6: the recipe under the name, in Group by recipe only. */
    showFrom: Boolean = false,
) {
    val background by animateColorAsState(
        if (lit) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.card,
        animationSpec = tween(if (lit) 150 else 900),
        label = "lit",
    )
    val state =
        when (row.state) {
            TickState.DONE -> "Bought"
            TickState.PARTIAL -> "Partly bought"
            TickState.OPEN -> if (row.unavailable) "Set aside for this trip" else "To buy"
        }
    val done = row.state == TickState.DONE
    Row(
        tile
            .fillMaxWidth()
            .background(background)
            .heightIn(min = 56.dp)
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
                .heightIn(min = 56.dp)
                .clickable(onClickLabel = "Details") { actions.onOpenItem(row) }
                .semantics(mergeDescendants = true) {
                    testTag = "item-${row.id}"
                    stateDescription = state
                    // Step AD.10: the swipe, for TalkBack: what is still to buy comes off.
                    if (!done) customActions = listOf(CustomAccessibilityAction("Remove") { actions.onRemove(row).let { true } })
                    // Step AD.6: in store order the recipe is not drawn, but TalkBack still says what it is for.
                    if (!showFrom && row.from != null) {
                        contentDescription =
                            listOfNotNull(displayMain(row.main, row.name), row.sub, "for ${row.from}").joinToString(", ")
                    }
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
                // Can't find it: still to buy, back on the next trip.
                if (row.unavailable) {
                    Text(
                        "Set aside for this trip",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("set-aside-${row.id}"),
                    )
                }
                for (line in subLines(row, showFrom)) {
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

private val VIEWS = listOf(ListView.STORE to "Store order", ListView.RECIPE to "Group by recipe")

/** The two stores a walking order can be kept for (missing 4 of the review). */
internal val STORES = listOf(1L to "My store", 2L to "Second store")

/** A recipe card's header: in the recipe's own colour, or plain for shared and hand-added items. */
@Composable
private fun RecipeTitle(
    section: RecipeSection,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier,
    count: Int,
    actions: ListActions = ListActions({}, {}, {}),
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
        if (section.kind == RecipeSection.Kind.RECIPE) {
            // The recipe's own picture, the same as on its card on the Recipes tab.
            RecipeArt(section.title, section.photo, Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)))
        } else {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(content), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = container, modifier = Modifier.size(20.dp))
            }
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
            Text("Bought ✓", style = MaterialTheme.typography.labelLarge, color = content)
        } else {
            CountPill("$left to buy", content)
        }
        if (section.kind != RecipeSection.Kind.SHARED) {
            // The recipe's own actions, here on its card (cut 1 of the review): the Recipes tab is the recipe book.
            var menu by remember { mutableStateOf(false) }
            var renaming by rememberSaveable { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("recipe-more-${section.key}")) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More for ${section.title}", tint = content)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Show ingredients") }, onClick = {
                        menu = false
                        actions.onUseOpen(section)
                    }, modifier = Modifier.testTag("recipe-open-${section.key}"))
                    DropdownMenuItem(text = { Text("Edit") }, onClick = {
                        menu = false
                        actions.onUseEdit(section)
                    }, modifier = Modifier.testTag("recipe-edit-${section.key}"))
                    DropdownMenuItem(text = { Text("Rename") }, onClick = {
                        menu = false
                        renaming = true
                    })
                    // Step AA: the same "− Serves N +" as on its Recipes card, in place; each press is written at once.
                    section.servings?.let { n ->
                        com.robert4lyf.hypershop.ui.common.ServingsStepper(
                            n,
                            { actions.onUseServings(section, it) },
                            tag = "recipe-servings-${section.key}",
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                    DropdownMenuItem(text = { Text("Remove from list") }, onClick = {
                        menu = false
                        actions.onUseRemove(section)
                    }, modifier = Modifier.testTag("recipe-remove-${section.key}"))
                }
            }
            if (renaming) {
                val byHand = section.kind == RecipeSection.Kind.BY_HAND
                TitleDialog(
                    section.title,
                    onDismiss = { renaming = false },
                    heading = if (byHand) "Rename" else "Rename recipe",
                    label = if (byHand) "Name, e.g. Household" else "Recipe name",
                ) { actions.onUseRename(section, it) }
            }
        }
        Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, tint = content)
    }
}
