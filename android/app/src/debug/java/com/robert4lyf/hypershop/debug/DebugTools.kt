package com.robert4lyf.hypershop.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.sync.WatchMessage
import com.robert4lyf.hypershop.core.sync.WatchOp
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.importing.ImportInput
import com.robert4lyf.hypershop.importing.ImportResult
import com.robert4lyf.hypershop.ui.list.ListViewModel
import kotlinx.coroutines.launch

/** Debug builds only (PLAN Step 5.10). */
object DebugTools {
    val screen: (@Composable (AppContainer) -> Unit)? = { DebugScreen(it) }

    /** "Load sample list": the same lines as the empty state's sample, typed in. */
    suspend fun loadSampleList(c: AppContainer) {
        for (line in ListViewModel.SAMPLE) {
            val p = c.ruleParser.parse(line)
            c.repository.manualAdd(p.name, QtyBase.of(p.quantityMax ?: p.quantity, p.unit, UnitLocale.METRIC), p.unit)
        }
    }

    /** "Load sample recipe": a ready import with no network, straight to review. */
    suspend fun loadSampleRecipe(c: AppContainer): Long {
        val raw = SAMPLE_RECIPE
        val result =
            ImportResult(
                url = null,
                title = "Sample chilli",
                servings = 4,
                locale = UnitLocale.METRIC,
                method = "MANUAL",
                lines = c.ruleParser.parseLines(raw),
                claudePending = true,
                rawLines = raw,
            )
        return c.importJobs.enqueueReady(ImportInput("", "Sample chilli"), result)
    }

    /**
     * "Fake watch": ticks [itemId] as the watch would, through the same
     * `applyWatch` transaction Run 2's link will use.
     */
    suspend fun watchTick(
        c: AppContainer,
        itemId: Long,
        seq: Int,
    ) {
        val epoch = c.repository.listEpoch().toInt()
        val seen = c.repository.snapshot().listRev.toInt()
        val now = c.clock() / 1000
        c.repository.applyWatch(WatchMessage.Ops(epoch, FAKE_INSTALL, listOf(WatchOp(seq, itemId.toInt(), true, seen, now))))
    }

    const val FAKE_INSTALL = 0xFA4E

    private val SAMPLE_RECIPE =
        listOf(
            "2 onions, chopped",
            "3 garlic cloves, crushed",
            "500g beef mince",
            "400g tin chopped tomatoes",
            "1 tsp ground cumin",
            "Salt, to taste",
            "1 tbsp olive oil",
        )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(c: AppContainer) {
    val scope = rememberCoroutineScope()
    var log by remember { mutableStateOf(c.log.snapshot()) }
    var items by remember { mutableStateOf(emptyList<Pair<Long, String>>()) }
    var seq by remember { mutableIntStateOf(1) }
    var status by remember { mutableStateOf("") }
    val refresh = {
        scope.launch {
            val l = c.repository.snapshot()
            items = l.liveItems.filter { !l.isDone(it.id) }.map { it.id to it.displayName }
            log = c.log.snapshot()
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(title = { Text("Debug tools") })
            LazyColumn(Modifier.fillMaxSize().testTag("debug"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                DebugTools.loadSampleList(c)
                                status = "Sample list loaded"
                                refresh()
                            }
                        }) { Text("Load sample list") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                c.shares.value =
                                    com.robert4lyf.hypershop.di.ShareOutcome.Review(DebugTools.loadSampleRecipe(c))
                            }
                        }) { Text("Sample recipe") }
                    }
                }
                if (status.isNotEmpty()) item { Text(status, Modifier.padding(horizontal = 16.dp).testTag("status")) }
                item { Section("Fake watch") }
                item {
                    TextButton(onClick = { refresh() }, Modifier.padding(horizontal = 8.dp)) { Text("Show open items") }
                }
                items(items, key = { "w-${it.first}" }) { (id, name) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Text(name, Modifier.weight(1f).padding(top = 12.dp))
                        TextButton(onClick = {
                            scope.launch {
                                DebugTools.watchTick(c, id, seq++)
                                status = "Watch ticked $name"
                                refresh()
                            }
                        }) { Text("Tick from watch") }
                    }
                }
                item { Section("Payload probe") }
                item {
                    Text(
                        "Runs on the watch: the debug watch build's Settings › Payload probe. The phone answers it; " +
                            "each request shows in the log below as \"watch: probe in … B, reply … B\".",
                        Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item { Section("Log (last ${log.size} of 500 lines, redacted)") }
                item {
                    TextButton(onClick = { refresh() }, Modifier.padding(horizontal = 8.dp)) { Text("Refresh log") }
                }
                items(log) { line ->
                    Text(line, Modifier.padding(horizontal = 16.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Column {
        HorizontalDivider()
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp),
        )
    }
}
