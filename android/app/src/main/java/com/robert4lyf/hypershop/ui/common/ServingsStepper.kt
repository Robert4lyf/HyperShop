package com.robert4lyf.hypershop.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/**
 * Step AA: "− Serves N +" for a recipe on the list, wherever it shows. Each press is written at once ([onChange],
 * `Planner.setUseServings`: what was bought stays bought); the number counts from the last press, so quick presses
 * add up before the list has caught up.
 */
@Composable
fun ServingsStepper(
    servings: Int,
    onChange: (Int) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    var n by remember(servings) { mutableIntStateOf(servings) }
    // One node of its own (read as "Serves 4" with its two buttons), not merged into a clickable card around it.
    Row(modifier.semantics(mergeDescendants = true) {}.testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = {
                n -= 1
                onChange(n)
            },
            enabled = n > 1,
            modifier = Modifier.testTag("$tag-fewer"),
        ) { Icon(Icons.Rounded.Remove, contentDescription = "Fewer servings") }
        Text(
            "Serves $n",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        IconButton(
            onClick = {
                n += 1
                onChange(n)
            },
            enabled = n < MAX_SERVINGS,
            modifier = Modifier.testTag("$tag-more"),
        ) { Icon(Icons.Rounded.Add, contentDescription = "More servings") }
    }
}

/** The most a stepper goes to. */
const val MAX_SERVINGS = 99
