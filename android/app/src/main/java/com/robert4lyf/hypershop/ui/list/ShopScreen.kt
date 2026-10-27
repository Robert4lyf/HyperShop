package com.robert4lyf.hypershop.ui.list

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.RemoveShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.settings.ListMode
import com.robert4lyf.hypershop.ui.common.AisleBadge
import com.robert4lyf.hypershop.ui.common.BottomActionBar
import com.robert4lyf.hypershop.ui.common.CountPill
import com.robert4lyf.hypershop.ui.common.TickMark
import com.robert4lyf.hypershop.ui.common.sectionItem
import com.robert4lyf.hypershop.ui.common.sectionPosition
import com.robert4lyf.hypershop.ui.theme.AisleStyle
import com.robert4lyf.hypershop.ui.theme.card
import com.robert4lyf.hypershop.ui.theme.style
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The shop screen: the list as it is walked. The same [ListUi] and
 * [ListActions] as the planning screen (the same rows, ticks and repository),
 * laid out for one hand in a supermarket: a slim pinned bar with what is left
 * and the progress, compact rows with a large tick, bought rows hidden under
 * one "N bought" row per aisle, "Next aisle" in thumb's reach, and nothing to
 * add, review or edit (that is what "Edit list" goes back to).
 */
@Composable
fun ShopScreen(
    ui: ListUi,
    actions: ListActions,
) {
    // A row just ticked stays where it is for a moment, tick filled, so a mis-tap is undone by tapping it again.
    // Each tick stamps its row; only the latest tick's timer lets the row fold, so a tick, untick, tick
    // within the moment keeps the full moment from the last tap.
    var stamps by remember { mutableStateOf(emptyMap<Long, Int>()) }
    val lingering = stamps.keys
    val groups = remember(ui.aisles, lingering) { groups(ui, lingering) }
    // Aisles whose bought rows are shown: kept across tab switches and rotation.
    var shownBought by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val cells = remember(groups, shownBought) { cells(groups, shownBought.toSet()) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val shopActions =
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
    // Both read the aisle at the top of the screen, so "Now" and "Next" never disagree; recomputed only
    // when that aisle changes, not on every scrolled pixel.
    val nowState = remember(cells) { derivedStateOf { nowAisle(cells, list.firstVisibleItemIndex) } }
    val nextState = remember(cells) { derivedStateOf { nextAisle(cells, list.firstVisibleItemIndex) } }
    val current = nowState.value
    Surface(Modifier.fillMaxSize().testTag("shop"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            ShopTop(ui, current, onPlan = { actions.onMode(ListMode.PLAN) })
            val card = MaterialTheme.colorScheme.card
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().testTag("list"),
                state = list,
                contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
            ) {
                items(cells, key = { it.key }, contentType = { it.javaClass.simpleName }) { cell ->
                    val tile = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null).sectionItem(cell.position, card)
                    when (cell) {
                        is ShopCell.Header -> GroupHeader(cell, now = cell.group.key == current?.key, tile)
                        is ShopCell.Item -> ShopRow(cell.row, shopActions, tile)
                        is ShopCell.Bought ->
                            BoughtRow(cell, tile) {
                                shownBought = if (cell.shown) shownBought - cell.group.key else shownBought + cell.group.key
                            }
                    }
                }
            }
            BottomActionBar(navigationBarInset = false) {
                // The next aisle with something left, after the one at the top of the screen (round the list when none).
                val next = nextState.value
                if (next != null) {
                    FilledTonalButton(
                        onClick = { scope.launch { list.animateScrollToItem(next.index) } },
                        modifier =
                            Modifier.weight(1f).heightIn(min = 52.dp).testTag("shop-next").semantics {
                                contentDescription = "Next aisle: ${next.cell.group.title}"
                            },
                    ) {
                        // The label gives way to a long aisle name, never the arrow.
                        Text(
                            "Next: ${next.cell.group.title}",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.size(6.dp))
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
                // Always there, so the bar doesn't jump on the first tick; live once something is bought.
                Button(
                    onClick = actions.onFinish,
                    enabled = ui.doneCount > 0,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("finish"),
                ) {
                    Icon(Icons.Rounded.DoneAll, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(if (ui.left == 0) "Finish" else "Finish shopping", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** What is left, the progress, and the way back to planning: one slim pinned bar, no hero. */
@Composable
private fun ShopTop(
    ui: ListUi,
    now: ShopGroup?,
    onPlan: () -> Unit,
) {
    val total = ui.left + ui.doneCount + ui.unavailable
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (ui.left == 0 && ui.unavailable > 0) {
                            "All found"
                        } else if (ui.left == 0) {
                            "All bought"
                        } else if (ui.left == 1) {
                            "1 left"
                        } else {
                            "${ui.left} left"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        // A polite live region: a bought row leaves the list, so this is what TalkBack says about a tick.
                        modifier =
                            Modifier
                                .semantics {
                                    heading()
                                    liveRegion = LiveRegionMode.Polite
                                }.testTag("shop-left"),
                    )
                    Text(
                        listOfNotNull(
                            "${ui.doneCount} of $total bought",
                            ui.unavailable.takeIf { it > 0 }?.let { "$it not available" },
                            now?.let { "Now: ${it.title}" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Not "Plan": that is the week screen. This goes back to the list you can add to and edit.
                TextButton(onClick = onPlan, modifier = Modifier.testTag("shop-plan")) {
                    Icon(Icons.Rounded.EditNote, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Edit list")
                }
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else ui.doneCount / total.toFloat() },
                // The text above already says "N of M bought"; the bar is not read out as well.
                modifier = Modifier.fillMaxWidth().padding(end = 12.dp).height(6.dp).clearAndSetSemantics {},
                strokeCap = StrokeCap.Round,
                gapSize = 4.dp,
                drawStopIndicator = {},
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primaryContainer,
            )
        }
    }
}

/**
 * An aisle as the shop screen walks it. Always the store's walking order, even
 * when planning groups the list by recipe: the shop is walked aisle by aisle,
 * and grouping it by recipe would send you back to the same shelf twice.
 */
internal data class ShopGroup(
    val key: String,
    val title: String,
    val aisle: AisleCode,
    val rows: List<ListRow>,
    /** Rows ticked a moment ago, still shown in place. */
    val lingering: Set<Long> = emptySet(),
    /** X.8: the "Not available today" group at the end: nothing to walk to, so never "Now" or "Next". */
    val setAside: Boolean = false,
) {
    val open: List<ListRow> get() = rows.filter { it.state != TickState.DONE || it.id in lingering }

    /** Still to find, by state: a row lingering in place after its tick is bought, as the top bar counts it (review B.19). */
    val left: Int get() = rows.count { it.state != TickState.DONE }
    val bought: List<ListRow> get() = rows.filter { it.state == TickState.DONE && it.id !in lingering }

    /** Something here still to pick up. */
    val toFind: Boolean get() = !setAside && open.isNotEmpty()
}

internal fun groups(
    ui: ListUi,
    lingering: Set<Long> = emptySet(),
): List<ShopGroup> {
    val aisles =
        ui.aisles.mapNotNull { (a, rows) ->
            rows.filter { !it.unavailable }.takeIf { it.isNotEmpty() }?.let {
                ShopGroup(
                    "aisle-${a.name}",
                    a.displayName,
                    a,
                    it,
                    lingering,
                )
            }
        }
    val setAside = ui.aisles.flatMap { it.second }.filter { it.unavailable }
    return if (setAside.isEmpty()) {
        aisles
    } else {
        aisles + ShopGroup("not-available", "Not available today", AisleCode.OTHER, setAside, setAside = true)
    }
}

/** How long a ticked row stays in place before it folds into "N bought". */
internal const val LINGER_MS = 2_500L

/** One lazy item of the shop screen. The bought rows come after the open ones, under a toggle row. */
internal sealed interface ShopCell {
    val key: Any
    val position: com.robert4lyf.hypershop.ui.common.SectionPosition

    data class Header(
        val group: ShopGroup,
        override val position: com.robert4lyf.hypershop.ui.common.SectionPosition,
    ) : ShopCell {
        override val key: Any get() = group.key
    }

    data class Item(
        val row: ListRow,
        val groupKey: String,
        override val position: com.robert4lyf.hypershop.ui.common.SectionPosition,
    ) : ShopCell {
        override val key: Any get() = "$groupKey-${row.id}"
    }

    /** "3 bought · Show" / "Hide bought". */
    data class Bought(
        val group: ShopGroup,
        val shown: Boolean,
        override val position: com.robert4lyf.hypershop.ui.common.SectionPosition,
    ) : ShopCell {
        override val key: Any get() = "${group.key}-bought"
    }
}

internal fun cells(
    groups: List<ShopGroup>,
    shownBought: Set<String>,
): List<ShopCell> =
    buildList {
        for (g in groups) {
            val open = g.open
            val bought = g.bought
            val shown = g.key in shownBought && bought.isNotEmpty()
            val count = 1 + open.size + (if (bought.isEmpty()) 0 else 1 + (if (shown) bought.size else 0))
            var i = 0
            add(ShopCell.Header(g, sectionPosition(i++, count)))
            for (r in open) add(ShopCell.Item(r, g.key, sectionPosition(i++, count)))
            if (bought.isNotEmpty()) {
                if (shown) for (r in bought) add(ShopCell.Item(r, g.key, sectionPosition(i++, count)))
                add(ShopCell.Bought(g, shown, sectionPosition(i++, count)))
            }
        }
    }

internal data class NextAisle(
    val index: Int,
    val cell: ShopCell.Header,
)

/**
 * The aisle you are in: the one at the top of the screen, while it has
 * something left. Null once it is all bought: then "Next" says where to go.
 */
internal fun nowAisle(
    cells: List<ShopCell>,
    firstVisible: Int,
): ShopGroup? {
    val top = cells.take(firstVisible + 1).lastOrNull { it is ShopCell.Header } as ShopCell.Header?
    return top?.group?.takeIf { it.toFind }
}

/**
 * The header to jump to from "Next aisle": the first aisle with something left
 * after the aisle the top of the screen is in; failing that, the first with
 * something left from the top of the list. Null when nothing is left anywhere
 * else: the aisle you are in is never "next".
 */
internal fun nextAisle(
    cells: List<ShopCell>,
    firstVisible: Int,
): NextAisle? {
    val headers = cells.withIndex().filter { it.value is ShopCell.Header }.map { NextAisle(it.index, it.value as ShopCell.Header) }
    val open = headers.filter { it.cell.group.toFind }
    if (open.isEmpty()) return null
    val top = headers.lastOrNull { it.index <= firstVisible } ?: return open.first()
    return open.firstOrNull { it.index > top.index } ?: open.firstOrNull { it.index < top.index }
}

@Composable
private fun GroupHeader(
    cell: ShopCell.Header,
    now: Boolean,
    tile: Modifier,
) {
    val g = cell.group
    val left = g.left
    val (container, content) = g.aisle.style.let { it.container to it.content }
    Row(
        tile
            .background(container)
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            .semantics(mergeDescendants = true) { heading() }
            .testTag("shop-${g.key}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AisleBadge(g.aisle, size = 28.dp)
        Text(
            g.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (now) Text("Now", style = MaterialTheme.typography.labelMedium, color = content)
        when {
            g.setAside -> CountPill("$left for next time", content)
            left == 0 -> Text("Done ✓", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            else -> CountPill(if (left == 1) "1 left" else "$left left", content)
        }
    }
}

/** "3 bought · Show": the aisle's bought rows, out of the way until asked for (and the undo stays one tap away). */
@Composable
private fun BoughtRow(
    cell: ShopCell.Bought,
    tile: Modifier,
    onToggle: () -> Unit,
) {
    val n = cell.group.bought.size
    Row(
        tile
            .background(MaterialTheme.colorScheme.card)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = if (cell.shown) "Hide bought" else "Show bought", role = Role.Button, onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { stateDescription = if (cell.shown) "Expanded" else "Collapsed" }
            .testTag("shop-bought-${cell.group.key}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (n == 1) "1 bought" else "$n bought",
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

/**
 * A compact row: the round tick (a 52 dp target) ticks; the rest opens the item
 * card. Only what the basket needs: the main line, and the pack note or "2
 * already bought" under it. TalkBack reads the tick as a checkbox named after
 * the item, then the row.
 */
@Composable
private fun ShopRow(
    row: ListRow,
    actions: ListActions,
    tile: Modifier,
) {
    val done = row.state == TickState.DONE
    val state =
        when (row.state) {
            TickState.DONE -> "Bought"
            TickState.PARTIAL -> "Partly bought"
            TickState.OPEN -> if (row.unavailable) "Not available today" else "Still needed"
        }
    val tint: AisleStyle = row.aisle.style
    Row(
        tile.background(MaterialTheme.colorScheme.card).fillMaxWidth().heightIn(min = 52.dp).padding(start = 2.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
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
                size = 30.dp,
                color = tint.content,
                onColor = tint.container,
                softColor = tint.container,
            )
        }
        Column(
            Modifier
                .weight(1f)
                .heightIn(min = 52.dp)
                .clickable(onClickLabel = "Details") { actions.onOpenItem(row) }
                .semantics(mergeDescendants = true) {
                    testTag = "item-${row.id}"
                    stateDescription = state
                }.padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                displayMain(row.main, row.name),
                style = MaterialTheme.typography.titleMedium,
                color = if (done || row.unavailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (done) TextDecoration.LineThrough else null,
            )
            // The pack note or "2 already bought"; the recipe's name is planning, not shopping.
            row.sub?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // X.8: not on the shelf today. Set aside, it stays needed and comes back on the next trip.
        when {
            row.unavailable ->
                TextButton(onClick = { actions.onAvailable(row) }, modifier = Modifier.testTag("available-${row.id}")) {
                    Text("Found it")
                }
            !done ->
                IconButton(
                    onClick = { actions.onUnavailable(row) },
                    modifier = Modifier.size(48.dp).testTag("unavailable-${row.id}"),
                ) {
                    Icon(
                        Icons.Rounded.RemoveShoppingCart,
                        contentDescription = "Not available today: ${row.name}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }
    }
}
