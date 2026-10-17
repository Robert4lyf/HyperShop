package com.robert4lyf.hypershop.ui.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robert4lyf.hypershop.data.UseSummary
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.ui.common.rememberOpenLink
import com.robert4lyf.hypershop.ui.common.webLink
import com.robert4lyf.hypershop.ui.list.show
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
    RecipesScreen(ui, onRemove = vm::remove, onAddInStock = vm::addInStockNow)
}

/** The Recipes tab, stateless. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipesScreen(
    ui: RecipesUi,
    onRemove: (UseSummary) -> Unit,
    onAddInStock: (UseSummary) -> Unit,
) {
    var confirm by remember { mutableStateOf<UseSummary?>(null) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(title = { Text("Recipes") })
            if (ui.loaded && ui.uses.isEmpty()) {
                Text("No recipes on the list. Share one from your browser.", Modifier.padding(24.dp))
            }
            LazyColumn(Modifier.fillMaxSize().testTag("recipes")) {
                items(ui.uses, key = { it.useId }) { use ->
                    UseCard(use, onRemove = { confirm = use }, onAddInStock = { onAddInStock(use) })
                }
            }
        }
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

@Composable
private fun UseCard(
    use: UseSummary,
    onRemove: () -> Unit,
    onAddInStock: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("use-${use.useId}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(use.title, style = MaterialTheme.typography.titleMedium)
            val bits =
                listOfNotNull(
                    use.servings?.let { "Serves $it" },
                    if (use.itemCount == 1) "1 item on the list" else "${use.itemCount} items on the list",
                    use.inStockCount.takeIf { it > 0 }?.let { "$it in stock" },
                )
            Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRemove) { Text("Remove from list") }
                webLink(use.url)?.let { url ->
                    val open = rememberOpenLink()
                    TextButton(onClick = { open(url) }, modifier = Modifier.testTag("open-${use.useId}")) { Text("Open recipe") }
                }
            }
            if (use.inStockCount > 0) TextButton(onClick = onAddInStock) { Text("Add the in-stock items now") }
        }
    }
}
