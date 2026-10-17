package com.robert4lyf.hypershop.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
        LazyColumn(Modifier.fillMaxSize().testTag("manual"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(errorText(error), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("error"))
                    Text("Select lines manually", style = MaterialTheme.typography.titleLarge)
                    if (candidates.isNotEmpty()) Text("Tick the ingredient lines from the page:")
                }
            }
            itemsIndexed(candidates) { i, line ->
                val on = i in picked
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(on, role = Role.Checkbox) { if (it) picked += i else picked -= i }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = on, onCheckedChange = null)
                    Text(line, Modifier.padding(start = 12.dp))
                }
            }
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        label = { Text(if (candidates.isEmpty()) "Paste the ingredient lines" else "Or paste lines") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth().testTag("paste-lines"),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onUse(picked.sorted().map { candidates[it] } + pasted) },
                            enabled = count > 0,
                        ) { Text(if (count == 1) "Use 1 line" else "Use $count lines") }
                        TextButton(onClick = onBack) { Text("Back to the list") }
                    }
                }
            }
        }
    }
}
