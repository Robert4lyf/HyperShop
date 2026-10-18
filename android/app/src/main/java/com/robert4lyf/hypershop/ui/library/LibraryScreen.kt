package com.robert4lyf.hypershop.ui.library

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robert4lyf.hypershop.core.catalog.DisplayPolicy
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.ui.common.AisleBadge
import com.robert4lyf.hypershop.ui.common.sectionItem
import com.robert4lyf.hypershop.ui.common.sectionPosition
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
    LibraryScreen(ui, onBack = onBack, onSearch = vm::search, onOpen = { editing = it }, onAdd = { editing = "" })
    val f = form
    if (editing != null && f != null) {
        EntrySheet(
            f,
            onSave = { next -> vm.save(next).also { if (it == null) editing = null } },
            onReset = {
                vm.reset(f.id)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/** The library, stateless. */
@Composable
fun LibraryScreen(
    ui: LibraryUi,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
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
                        Text(
                            "${ui.total} ingredients · names, aisles and units",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedTextField(
                    value = ui.query,
                    onValueChange = onSearch,
                    placeholder = { Text("Search, e.g. coriander") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (ui.query.isNotEmpty()) {
                            IconButton(
                                onClick = { onSearch("") },
                            ) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("library-search"),
                )
                val card = MaterialTheme.colorScheme.card
                LazyColumn(Modifier.fillMaxSize().testTag("library"), contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp)) {
                    if (ui.rows.isEmpty()) {
                        item { Text("Nothing matches. Add it with the button below.", Modifier.padding(24.dp)) }
                    }
                    itemsIndexed(ui.rows, key = { _, r -> r.id }) { i, r ->
                        Row(
                            Modifier
                                .sectionItem(sectionPosition(i, ui.rows.size), card)
                                .fillMaxWidth()
                                .heightIn(min = 60.dp)
                                .clickable(onClickLabel = "Edit", role = Role.Button) { onOpen(r.id) }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                .testTag("entry-${r.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            AisleBadge(r.aisle, size = 32.dp)
                            Column(Modifier.weight(1f)) {
                                Text(r.name, style = MaterialTheme.typography.bodyLarge)
                                r.detail?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
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
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        EntryEditor(start, onSave, onReset)
    }
}

/** The edit form, stateless apart from what is being typed (tests and screenshots show it directly). */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EntryEditor(
    start: EntryForm,
    onSave: suspend (EntryForm) -> String?,
    onReset: () -> Unit,
) {
    var f by remember(start) { mutableStateOf(start) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(if (f.isNew) "Add ingredient" else "Edit ingredient", style = MaterialTheme.typography.headlineSmall)
        Field("Name", f.name, "entry-name") { f = f.copy(name = it) }
        Field("Plural (optional)", f.plural, "entry-plural") { f = f.copy(plural = it) }
        Field("Also called, separated by commas", f.synonyms, "entry-synonyms", lines = 2) { f = f.copy(synonyms = it) }
        Text("Aisle", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (a in AisleCode.entries) {
                FilterChip(
                    selected = f.aisle == a,
                    onClick = { f = f.copy(aisle = a) },
                    label = { Text(a.displayName) },
                    leadingIcon = { Icon(a.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
        Text("Amounts shown as", style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            POLICIES.forEachIndexed { i, (p, label) ->
                SegmentedButton(
                    selected = f.policy == p,
                    onClick = { f = f.copy(policy = p) },
                    shape = SegmentedButtonDefaults.itemShape(i, POLICIES.size),
                ) { Text(label, maxLines = 1) }
            }
        }
        if (f.policy == DisplayPolicy.PRESENCE) {
            Text(
                "None: the list shows just the name, as for salt or herbs you buy in a pack.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Field("Swaps, separated by commas", f.swaps, "entry-swaps", lines = 2) { f = f.copy(swaps = it) }
        Text(
            "Offered on the list as \"Swap for\", e.g. maple syrup for agave syrup.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Field("Always use instead (optional)", f.replaceWith, "entry-replace") { f = f.copy(replaceWith = it) }
        Field("Usually sold as, e.g. 1 kg bag", f.packNote, "entry-pack") { f = f.copy(packNote = it) }
        Field("Grams per cup (turns cups into grams)", f.gramsPerCup, "entry-grams", number = true) { f = f.copy(gramsPerCup = it) }
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Switch) { f = f.copy(alwaysInStock = !f.alwaysInStock) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Always in stock", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = f.alwaysInStock, onCheckedChange = null)
        }
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("entry-error"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
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
            if (start.edited || start.custom) {
                OutlinedButton(onClick = onReset, modifier = Modifier.heightIn(min = 52.dp).testTag("entry-reset")) {
                    Text(if (start.custom) "Delete" else "Reset to default")
                }
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
