package com.robert4lyf.hypershop.ui.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** One line of a kept method: a section heading ("For the sauce"), or a step with its number among the steps. */
internal data class MethodLine(
    val text: String,
    val number: Int?,
)

/** The kept lines as shown: headings end in a colon as stored; steps are numbered through, headings are not. */
internal fun methodLines(method: List<String>): List<MethodLine> {
    var n = 0
    return method.map { line ->
        if (line.endsWith(":") && line.length <= 80) MethodLine(line.trimEnd(':'), null) else MethodLine(line, ++n)
    }
}

/**
 * A saved recipe's method, as its page gave it when it was imported: the page's own words, shown as text and nothing
 * more. Nothing is drawn when none was kept (a typed or photographed recipe, or a page without one).
 */
internal fun LazyListScope.methodSteps(
    method: List<String>,
    /** The cooking view's larger title; the recipe's page uses its own small headings. */
    large: Boolean = false,
) {
    if (method.isEmpty()) return
    item(key = "method-title") {
        val style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall
        Text("Method", style = style, modifier = Modifier.padding(top = 12.dp).semantics { heading() }.testTag("method"))
    }
    val lines = methodLines(method)
    items(lines.size, key = { "method-$it" }) { i ->
        val line = lines[i]
        val stepStyle = MaterialTheme.typography.bodyLarge
        if (line.number == null) {
            Text(line.text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
        } else {
            // One thing to hear: "Step 2. Fry the onions…", not a number and then a sentence.
            Row(
                Modifier.clearAndSetSemantics { contentDescription = "Step ${line.number}. ${line.text}" },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("${line.number}.", style = stepStyle, color = MaterialTheme.colorScheme.primary)
                Text(line.text, style = stepStyle)
            }
        }
    }
}
