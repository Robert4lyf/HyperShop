package com.robert4lyf.hypershop.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robert4lyf.hypershop.core.catalog.DisplayPolicy
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.ui.common.AisleBadge
import com.robert4lyf.hypershop.ui.common.PillShape
import com.robert4lyf.hypershop.ui.common.SectionLabel
import com.robert4lyf.hypershop.ui.common.basilChipColors
import com.robert4lyf.hypershop.ui.common.basilSegmentedColors
import com.robert4lyf.hypershop.ui.common.cardLift
import com.robert4lyf.hypershop.ui.common.fieldLift
import com.robert4lyf.hypershop.ui.common.pillFieldColors
import com.robert4lyf.hypershop.ui.list.show
import com.robert4lyf.hypershop.ui.theme.card
import com.robert4lyf.hypershop.ui.theme.icon
import com.robert4lyf.hypershop.ui.util.factoryFor
import kotlinx.coroutines.launch

@Composable
fun LibraryRoute(
    container: AppContainer,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
) {
    val vm: LibraryViewModel = viewModel(factory = factoryFor { LibraryViewModel(container) })
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.events.collect { show(container, snackbar, it) } }
    // Which ingredient's sheet is open: an id, "" for a new one, null for none.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var form by remember { mutableStateOf<EntryForm?>(null) }
    LaunchedEffect(editing) { form = editing?.let { vm.form(it.ifEmpty { null }) } }
    LibraryScreen(
        ui,
        vm.query,
        onBack = onBack,
        onSearch = vm::search,
        onOpen = { editing = it },
        onAdd = { editing = "" },
        aisle = vm.aisle,
        onAisle = vm::filterAisle,
    )
    val f = form
    if (editing != null && f != null) {
        EntrySheet(
            f,
            // Saved in the view model's scope: closing the sheet mid-save can't leave the list un-rekeyed.
            onSave = { next -> vm.saveFromSheet(next).await().also { if (it == null) editing = null } },
            onReset = {
                vm.reset(f.id)
                editing = null
            },
            onDismiss = { editing = null },
            suggest = vm::suggest,
        )
    }
}

/** The library, stateless. */
@Composable
fun LibraryScreen(
    ui: LibraryUi,
    query: String,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    /** The aisle chip chosen, or null for all. */
    aisle: AisleCode? = null,
    onAisle: (AisleCode?) -> Unit = {},
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Ingredient library",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.semantics { heading() },
                        )
                        if (ui.loaded) {
                            Text(
                                "${ui.total} ingredients",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = onSearch,
                    placeholder = { Text("Search, e.g. coriander") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(
                                onClick = { onSearch("") },
                            ) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") }
                        }
                    },
                    singleLine = true,
                    shape = PillShape,
                    colors = pillFieldColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).fieldLift().testTag("library-search"),
                )
                AisleChips(aisle, onAisle)
                val card = MaterialTheme.colorScheme.card
                val shape = MaterialTheme.shapes.large
                LazyColumn(Modifier.fillMaxSize().testTag("library"), contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)) {
                    // Only once the catalogue is in: an empty list before that is just loading.
                    if (ui.loaded && ui.rows.isEmpty() && (query.isNotEmpty() || aisle != null)) {
                        item { Text("Nothing matches", Modifier.padding(24.dp)) }
                    }
                    items(ui.rows, key = { it.id }) { r ->
                        // Each ingredient is its own lifted card, 8 dp from the next.
                        Row(
                            Modifier
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .cardLift(shape)
                                .clip(shape)
                                .background(card)
                                .fillMaxWidth()
                                .heightIn(min = 64.dp)
                                .clickable(onClickLabel = "Edit", role = Role.Button) { onOpen(r.id) }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                .testTag("entry-${r.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            AisleBadge(r.aisle, size = 36.dp)
                            Column(Modifier.weight(1f)) {
                                Text(r.name, style = MaterialTheme.typography.titleMedium)
                                r.detail?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (r.custom || r.edited) {
                                Text(
                                    if (r.custom) "Yours" else "Edited",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("Add ingredient") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).testTag("add-ingredient"),
            )
        }
    }
}

/** "All", then one chip per aisle, to narrow the list to one aisle. */
@Composable
private fun AisleChips(
    aisle: AisleCode?,
    onAisle: (AisleCode?) -> Unit,
) {
    LazyRow(
        Modifier.fillMaxWidth().testTag("aisle-chips"),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            FilterChip(
                selected = aisle == null,
                onClick = { onAisle(null) },
                label = { Text("All") },
                colors = basilChipColors(),
                modifier = Modifier.testTag("aisle-all"),
            )
        }
        items(AisleCode.entries, key = { it.name }) { a ->
            FilterChip(
                selected = aisle == a,
                onClick = { onAisle(if (aisle == a) null else a) },
                label = { Text(a.displayName) },
                leadingIcon = { Icon(a.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                colors = basilChipColors(),
                modifier = Modifier.testTag("aisle-${a.name}"),
            )
        }
    }
}

private val POLICIES =
    listOf(
        DisplayPolicy.COUNT to "Count",
        DisplayPolicy.MASS to "Weight",
        DisplayPolicy.VOLUME to "Volume",
        DisplayPolicy.PRESENCE to "None",
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntrySheet(
    start: EntryForm,
    onSave: suspend (EntryForm) -> String?,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    suggest: (String) -> List<String>,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        EntryEditor(start, onSave, onReset, onCancel = onDismiss, suggest = suggest)
    }
}

/** The form across a rotation: its fields in order, as plain values. */
private val EntryFormSaver: Saver<EntryForm, Any> =
    listSaver(
        save = { f ->
            listOf(
                f.id,
                f.name,
                f.plural,
                f.synonyms,
                f.aisle.name,
                f.policy.name,
                f.packNote,
                f.gramsPerCup,
                f.alwaysInStock,
                f.swaps,
                f.replaceWith,
                f.group,
                f.edited,
                f.custom,
            )
        },
        restore = { v ->
            EntryForm(
                id = v[0] as String,
                name = v[1] as String,
                plural = v[2] as String,
                synonyms = v[3] as String,
                aisle = AisleCode.valueOf(v[4] as String),
                policy = DisplayPolicy.valueOf(v[5] as String),
                packNote = v[6] as String,
                gramsPerCup = v[7] as String,
                alwaysInStock = v[8] as Boolean,
                swaps = v[9] as String,
                replaceWith = v[10] as String,
                group = v[11] as String,
                edited = v[12] as Boolean,
                custom = v[13] as Boolean,
            )
        },
    )

/** The fields under "Advanced" have something in them, so it opens expanded. */
private val EntryForm.hasAdvanced: Boolean
    get() =
        policy != DisplayPolicy.COUNT ||
            packNote.isNotBlank() ||
            gramsPerCup.isNotBlank() ||
            group.isNotBlank() ||
            swaps.isNotBlank() ||
            replaceWith.isNotBlank()

/** The edit form, stateless apart from what is being typed (tests and screenshots show it directly). */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EntryEditor(
    start: EntryForm,
    onSave: suspend (EntryForm) -> String?,
    onReset: () -> Unit,
    onCancel: () -> Unit = {},
    /** Ingredient names for what is being typed in Swaps and "Always replace with". */
    suggest: (String) -> List<String> = { emptyList() },
) {
    var f by rememberSaveable(start, stateSaver = EntryFormSaver) { mutableStateOf(start) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var advanced by rememberSaveable(start) { mutableStateOf(start.hasAdvanced) }
    // Only awaits [onSave]'s answer and closes the sheet; the save itself runs in the view model.
    val scope = rememberCoroutineScope()
    // Any edit clears the last save's complaint: it was about the form as it was.
    val edit = { next: EntryForm ->
        f = next
        error = null
    }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(if (f.isNew) "Add ingredient" else "Edit ingredient", style = MaterialTheme.typography.headlineSmall)
        Field("Name", f.name, "entry-name") { edit(f.copy(name = it)) }
        Field("Plural (optional)", f.plural, "entry-plural") { edit(f.copy(plural = it)) }
        Field("Also called", f.synonyms, "entry-synonyms", lines = 2) { edit(f.copy(synonyms = it)) }
        SectionLabel("Aisle")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (a in AisleCode.entries) {
                FilterChip(
                    selected = f.aisle == a,
                    onClick = { edit(f.copy(aisle = a)) },
                    label = { Text(a.displayName) },
                    leadingIcon = { Icon(a.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    colors = basilChipColors(),
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = f.alwaysInStock, role = Role.Switch) { edit(f.copy(alwaysInStock = it)) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Always at home", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = f.alwaysInStock, onCheckedChange = null)
        }
        // A tonal button that reads as a switch: TalkBack says whether Advanced is open.
        FilledTonalButton(
            onClick = { advanced = !advanced },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { stateDescription = if (advanced) "Expanded" else "Collapsed" }
                    .testTag("entry-advanced"),
        ) {
            Text("Advanced", Modifier.weight(1f))
            Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
        }
        AnimatedVisibility(advanced) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionLabel("Amounts shown as")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    POLICIES.forEachIndexed { i, (p, label) ->
                        SegmentedButton(
                            selected = f.policy == p,
                            // No checkmark: it would push the label sideways on every switch; the fill says which is on.
                            icon = {},
                            onClick = { edit(f.copy(policy = p)) },
                            shape = SegmentedButtonDefaults.itemShape(i, POLICIES.size),
                            colors = basilSegmentedColors(),
                        ) { Text(label, maxLines = 1) }
                    }
                }
                Field("Usually sold as", f.packNote, "entry-pack") { edit(f.copy(packNote = it)) }
                Field("Grams per cup", f.gramsPerCup, "entry-grams", number = true) {
                    edit(f.copy(gramsPerCup = it))
                }
                Field("Group (optional)", f.group, "entry-group") { edit(f.copy(group = it)) }
                Field("Swaps", f.swaps, "entry-swaps", lines = 2) { edit(f.copy(swaps = it)) }
                // Suggestions for the term after the last comma; picking one completes that term.
                Suggestions(suggestFor(f.swaps.substringAfterLast(','), suggest)) { name ->
                    val kept = f.swaps.substringBeforeLast(',', missingDelimiterValue = "").trim()
                    edit(f.copy(swaps = if (kept.isEmpty()) name else "$kept, $name"))
                }
                Field("Always replace with (optional)", f.replaceWith, "entry-replace") { edit(f.copy(replaceWith = it)) }
                Suggestions(suggestFor(f.replaceWith, suggest)) { edit(f.copy(replaceWith = it)) }
            }
        }
        error?.let {
            // Spoken when it appears: Save keeps the focus, so TalkBack would otherwise say nothing.
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("entry-error"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 52.dp).testTag("entry-cancel")) { Text("Cancel") }
            Button(
                onClick = {
                    saving = true
                    scope.launch {
                        error = onSave(f)
                        saving = false
                    }
                },
                enabled = f.name.isNotBlank() && !saving,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("entry-save"),
            ) { Text("Save") }
        }
        if (start.edited || start.custom) {
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("entry-reset"),
            ) { Text(if (start.custom) "Delete" else "Reset to default") }
        }
    }
}

/** Nothing until something is typed, whatever [suggest] does with a blank. */
private fun suggestFor(
    typed: String,
    suggest: (String) -> List<String>,
): List<String> = if (typed.isBlank()) emptyList() else suggest(typed)

/** Up to five ingredient names under a field, each a row that fills it in. */
@Composable
private fun Suggestions(
    names: List<String>,
    onPick: (String) -> Unit,
) {
    if (names.isEmpty()) return
    Surface(Modifier.cardLift(MaterialTheme.shapes.medium), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.card) {
        Column(Modifier.fillMaxWidth()) {
            for (n in names) {
                Text(
                    n,
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onPick(n) }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag("suggest-$n"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    tag: String,
    lines: Int = 1,
    number: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = lines == 1,
        minLines = lines,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text,
            ),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}
