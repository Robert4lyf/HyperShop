package com.robert4lyf.hypershop.ui.meals

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.ui.common.BottomActionBar
import com.robert4lyf.hypershop.ui.list.show
import com.robert4lyf.hypershop.ui.util.factoryFor

/**
 * Step Q: "Check food at home", a task of its own, reached after Make shopping list and from the list. It asks once per
 * ingredient this week's meals need together ("Need 500 g for 2 meals"), with Need it, Have some and Have enough.
 * It changes only unbought recipe shares (`HomeCheck`): never what was bought, never a hand-added extra. Leaving
 * it changes nothing.
 */
@Composable
fun HomeCheckRoute(
    container: AppContainer,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
) {
    // The light view model (this week's home check and the list only), so the screen opens at once.
    val vm: PrepViewModel = viewModel(key = "home-check", factory = factoryFor { PrepViewModel(container) })
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.events.collect { show(container, snackbar, it) } }
    HomeCheckScreen(
        ui,
        MealsActions(
            onHave = vm.actions::have,
            onNeed = vm.actions::need,
            onHaveSome = { r, t, done -> vm.actions.haveSome(r, t, done) },
            onClearSome = vm.actions::clearSome,
        ),
        onBack = onBack,
    )
}

@Composable
fun HomeCheckScreen(
    ui: MealsUi,
    actions: MealsActions,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    // Cupboard things first; fresh, chilled and frozen food behind a switch that says what it adds.
    var allFood by rememberSaveable { mutableStateOf(false) }
    val shown = ui.home.filter { allFood || it.likelyCupboard }
    val hidden = ui.home.size - ui.home.count { it.likelyCupboard }
    Surface(Modifier.fillMaxSize().testTag("home-check-screen"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("home-check-back")) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
                Text(
                    "Food at home",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                // Everything shown is at home: one tap for a well-stocked cupboard (Undo on the message).
                val toHave = shown.filter { !it.have }
                if (toHave.size > 1) {
                    TextButton(onClick = { actions.onHave(toHave) }, modifier = Modifier.testTag("home-have-all")) {
                        Text("Have all ${toHave.size}")
                    }
                }
            }
            Text(
                "Optional. Say what you already have for this week's meals; only the rest stays on your list. " +
                    "Bought items and things you added yourself are not changed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (hidden > 0) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Include fresh, chilled and frozen food ($hidden)",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = allFood, onCheckedChange = { allFood = it }, modifier = Modifier.testTag("home-all-food"))
                }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().testTag("home-check-list"),
                contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (ui.loaded && ui.home.isEmpty()) {
                    item(key = "none") {
                        Text(
                            "Nothing to check: no meal of this week is on your list with something still to buy.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        )
                    }
                }
                items(shown, key = { "home-${it.mergeKey}" }) { r -> HomeRowItem(r, actions) }
            }
            BottomActionBar(navigationBarInset = false) {
                Button(onClick = onBack, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("home-check-done")) {
                    Text(
                        when {
                            ui.listLeft > 0 -> "Done · ${if (ui.listLeft == 1) "1 thing" else "${ui.listLeft} things"} to buy"
                            else -> "Done"
                        },
                    )
                }
            }
        }
    }
}
