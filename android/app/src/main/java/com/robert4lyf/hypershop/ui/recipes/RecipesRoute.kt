package com.robert4lyf.hypershop.ui.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Inventory
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robert4lyf.hypershop.data.UseSummary
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.ui.common.EmptyHero
import com.robert4lyf.hypershop.ui.common.ScreenHeader
import com.robert4lyf.hypershop.ui.common.rememberOpenLink
import com.robert4lyf.hypershop.ui.common.siteName
import com.robert4lyf.hypershop.ui.common.webLink
import com.robert4lyf.hypershop.ui.list.show
import com.robert4lyf.hypershop.ui.theme.Brand
import com.robert4lyf.hypershop.ui.theme.LocalDarkTheme
import com.robert4lyf.hypershop.ui.theme.card
import com.robert4lyf.hypershop.ui.theme.heroGlow
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
    RecipesScreen(
        ui,
        onRemove = vm::remove,
        onAddInStock = vm::addInStockNow,
        onEdit = vm::edit,
        add = AddRecipeActions(onLink = vm::addLink, onManual = { n, s, l -> vm.addManual(n, s, l) }, onPhoto = { vm.addPhoto(it) }),
    )
}

/** The Recipes tab, stateless. */
@Composable
fun RecipesScreen(
    ui: RecipesUi,
    onRemove: (UseSummary) -> Unit,
    onAddInStock: (UseSummary) -> Unit,
    onEdit: (UseSummary) -> Unit = {},
    add: AddRecipeActions = AddRecipeActions(),
) {
    var confirm by remember { mutableStateOf<UseSummary?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().testTag("recipes"),
                // Room under the last card for the + button.
                contentPadding = PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "top") {
                    val n = ui.uses.count { it.recipeId != null }
                    ScreenHeader(
                        title = "Recipes",
                        subtitle =
                            when {
                                !ui.loaded || n == 0 -> null
                                n == 1 -> "1 recipe on your list"
                                else -> "$n recipes on your list"
                            },
                    )
                }
                if (ui.loaded && ui.uses.isEmpty()) {
                    item(key = "empty") {
                        EmptyHero(
                            Icons.AutoMirrored.Rounded.MenuBook,
                            "No recipes yet",
                            body = "No recipes on the list. Tap + to paste a link or type one in, or share a page from your browser.",
                        )
                    }
                }
                items(ui.uses, key = { it.useId }) { use ->
                    UseCard(use, onRemove = { confirm = use }, onAddInStock = { onAddInStock(use) }, onEdit = { onEdit(use) })
                }
            }
            FloatingActionButton(
                onClick = { adding = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).testTag("add-recipe"),
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Icon(Icons.Rounded.Add, contentDescription = "Add a recipe", modifier = Modifier.size(28.dp)) }
        }
    }
    if (adding) AddRecipeSheet(add, onDismiss = { adding = false })
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UseCard(
    use: UseSummary,
    onRemove: () -> Unit,
    onAddInStock: () -> Unit,
    onEdit: () -> Unit,
) {
    val url = webLink(use.url)
    val open = rememberOpenLink()
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("use-${use.useId}"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.card,
    ) {
        val dark = LocalDarkTheme.current
        val hue = Brand.recipeHue(use.title)
        Column {
            // The recipe's own colour band: the same hue as its review screen.
            Box(Modifier.fillMaxWidth().background(Brand.recipeBrush(use.title)).heroGlow()) {
                Icon(
                    Icons.AutoMirrored.Rounded.MenuBook,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.16f),
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).size(72.dp),
                )
                Column(Modifier.padding(20.dp).padding(end = 56.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(use.title, style = MaterialTheme.typography.titleLarge, color = Brand.OnHero)
                    val site = url?.let { siteName(it) }
                    when {
                        site != null -> Meta(Icons.Rounded.Link, site)
                        use.recipeId == null -> Meta(Icons.Rounded.EditNote, "Typed into the list")
                    }
                }
            }
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // The facts as one sentence for TalkBack, chips on screen.
                val bits =
                    listOfNotNull(
                        use.servings?.let { "Serves $it" },
                        if (use.itemCount == 1) "1 item on the list" else "${use.itemCount} items on the list",
                        use.inStockCount.takeIf { it > 0 }?.let { "$it in stock" },
                    )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.clearAndSetSemantics { contentDescription = bits.joinToString(" · ") },
                ) {
                    val bg = Brand.tint(hue, dark)
                    val fg = Brand.strong(hue, dark)
                    use.servings?.let { Fact(Icons.Rounded.People, "Serves $it", bg, fg) }
                    Fact(Icons.Rounded.ShoppingCart, if (use.itemCount == 1) "1 item" else "${use.itemCount} items", bg, fg)
                    if (use.inStockCount > 0) Fact(Icons.Rounded.Inventory, "${use.inStockCount} in stock", bg, fg)
                }
                // Wraps on narrow phones and large text instead of squeezing the second button.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (url != null) {
                        FilledTonalButton(onClick = { open(url) }, modifier = Modifier.testTag("open-${use.useId}")) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("Open recipe")
                        }
                    }
                    // Only recipes can be edited ("Added by hand" items are edited on the list).
                    if (use.recipeId != null) {
                        OutlinedButton(onClick = onEdit, modifier = Modifier.testTag("edit-${use.useId}")) {
                            Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("Edit")
                        }
                    }
                    OutlinedButton(onClick = onRemove) { Text("Remove from list") }
                }
                if (use.inStockCount > 0) {
                    TextButton(
                        onClick = onAddInStock,
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) { Text("Add the in-stock items now") }
                }
            }
        }
    }
}

@Composable
private fun Meta(
    icon: ImageVector,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
    Surface(shape = RoundedCornerShape(50), color = bg) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}
