package com.robert4lyf.hypershop.ui.review

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.robert4lyf.hypershop.importing.ImportError
import com.robert4lyf.hypershop.ui.common.BottomActionBar
import com.robert4lyf.hypershop.ui.common.InfoBanner
import com.robert4lyf.hypershop.ui.common.SectionLabel
import com.robert4lyf.hypershop.ui.common.Tone
import com.robert4lyf.hypershop.ui.common.cardLift
import com.robert4lyf.hypershop.ui.common.sectionItem
import com.robert4lyf.hypershop.ui.common.sectionPosition
import com.robert4lyf.hypershop.ui.theme.card

/** Errors a second fetch may well fix: the page can be tried again. */
val ImportError.transient: Boolean
    get() = this == ImportError.OFFLINE || this == ImportError.HTTP || this == ImportError.TOO_LARGE

/**
 * An import that found no ingredients: what went wrong, then "Select lines
 * manually" (paste them, or pick from the page's lines), "Try again" for a
 * fetch that may work next time, or back to the list.
 */
@Composable
fun ManualLinesView(
    error: ImportError,
    candidates: List<String>,
    onUse: (List<String>) -> Unit,
    onBack: () -> Unit,
    onRetry: (() -> Unit)? = null,
) {
    // Both survive rotation: a long paste, or a dozen ticks, is not lost to it.
    val picked = rememberSaveable(candidates, saver = PICKED) { mutableStateListOf<Int>() }
    var pasted by rememberSaveable { mutableStateOf("") }
    val count = picked.size + pasted.lines().count { it.isNotBlank() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().imePadding()) {
            val card = MaterialTheme.colorScheme.card
            LazyColumn(
                Modifier.weight(1f).testTag("manual"),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                item(key = "error") {
                    Column(
                        Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        InfoBanner(errorText(error), Icons.Rounded.ErrorOutline, Modifier.testTag("error"), Tone.WARNING)
                        if (onRetry != null) {
                            FilledTonalButton(onClick = onRetry, modifier = Modifier.testTag("retry-fetch")) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text("Try again")
                            }
                        }
                    }
                }
                item(key = "paste") {
                    Surface(
                        Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp).fillMaxWidth().cardLift(),
                        shape = RoundedCornerShape(22.dp),
                        color = card,
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Select lines manually", style = MaterialTheme.typography.titleLarge)
                            OutlinedTextField(
                                value = pasted,
                                onValueChange = { pasted = it },
                                label = { Text("Paste the ingredient lines") },
                                minLines = 4,
                                shape = MaterialTheme.shapes.medium,
                                colors =
                                    OutlinedTextFieldDefaults.colors(
                                        unfocusedContainerColor = card,
                                        focusedContainerColor = card,
                                    ),
                                modifier = Modifier.fillMaxWidth().testTag("paste-lines"),
                            )
                        }
                    }
                }
                if (candidates.isNotEmpty()) {
                    item(key = "from-page") {
                        SectionLabel("Or tick lines from the page", Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp))
                    }
                }
                itemsIndexed(candidates) { i, line ->
                    val on = i in picked
                    Row(
                        Modifier
                            .sectionItem(sectionPosition(i, candidates.size), card)
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .toggleable(on, role = Role.Checkbox) { if (it) picked += i else picked -= i }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Text(line, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            BottomActionBar {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onBack) { Text("Back to the list") }
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (count == 1) "1 selected" else "$count selected",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("selected-count"),
                        )
                        if (picked.isNotEmpty()) {
                            TextButton(onClick = { picked.clear() }, modifier = Modifier.testTag("clear-selection")) {
                                Text("Clear selection")
                            }
                        }
                    }
                    Button(
                        onClick = { onUse(picked.sorted().map { candidates[it] } + pasted) },
                        enabled = count > 0,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) { Text(if (count == 1) "Use 1 line" else "Use $count lines", style = MaterialTheme.typography.titleSmall) }
                }
            }
        }
    }
}

private val PICKED = listSaver<SnapshotStateList<Int>, Int>({ it.toList() }, { it.toMutableStateList() })
