package com.robert4lyf.hypershop.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.robert4lyf.hypershop.importing.ImportError
import com.robert4lyf.hypershop.ui.common.BottomActionBar
import com.robert4lyf.hypershop.ui.common.InfoBanner
import com.robert4lyf.hypershop.ui.common.Tone
import com.robert4lyf.hypershop.ui.common.sectionItem
import com.robert4lyf.hypershop.ui.common.sectionPosition
import com.robert4lyf.hypershop.ui.theme.card

/**
 * An import that found no ingredients: what went wrong, then "Select lines
 * manually" (pick from the page's lines, or paste them) or back to the list.
 */
@Composable
fun ManualLinesView(
    error: ImportError,
    candidates: List<String>,
    onUse: (List<String>) -> Unit,
    onBack: () -> Unit,
) {
    val picked = remember(candidates) { mutableStateListOf<Int>() }
    var pasted by remember { mutableStateOf("") }
    val count = picked.size + pasted.lines().count { it.isNotBlank() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.weight(1f).testTag("manual"),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                item {
                    Column(
                        Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        InfoBanner(errorText(error), Icons.Rounded.ErrorOutline, Modifier.testTag("error"), Tone.WARNING)
                        Column(Modifier.padding(horizontal = 4.dp)) {
                            Text("Select lines manually", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                if (candidates.isNotEmpty()) {
                                    "Tick the ingredient lines from the page:"
                                } else {
                                    "Paste the recipe's ingredients below."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                itemsIndexed(candidates) { i, line ->
                    val on = i in picked
                    Row(
                        Modifier
                            .sectionItem(sectionPosition(i, candidates.size))
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
                item {
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        label = { Text(if (candidates.isEmpty()) "Paste the ingredient lines" else "Or paste lines") },
                        minLines = 4,
                        shape = RoundedCornerShape(20.dp),
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = MaterialTheme.colorScheme.card,
                                focusedContainerColor = MaterialTheme.colorScheme.card,
                            ),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp).testTag("paste-lines"),
                    )
                }
            }
            BottomActionBar {
                TextButton(onClick = onBack) { Text("Back to the list") }
                Button(
                    onClick = { onUse(picked.sorted().map { candidates[it] } + pasted) },
                    enabled = count > 0,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                ) { Text(if (count == 1) "Use 1 line" else "Use $count lines", style = MaterialTheme.typography.titleSmall) }
            }
        }
    }
}
