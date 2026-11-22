package com.robert4lyf.hypershop.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.robert4lyf.hypershop.core.model.AisleCode

/**
 * Step AC: an aisle's heading as one slim row: its small icon, its name and a count in words ("3 to buy", "2 to add").
 * Step AD.3: shared by the list and the review, so both read the same. [trailing] is the list's collapse arrow.
 */
@Composable
fun AisleHeading(
    aisle: AisleCode,
    count: String,
    modifier: Modifier = Modifier,
    countColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    /** 48 dp where the row is tapped (the list's collapses); a heading that does nothing can be slimmer. */
    minHeight: Dp = 48.dp,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AisleBadge(aisle, size = 24.dp)
        Text(aisle.displayName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(count, style = MaterialTheme.typography.labelLarge, color = countColor)
        trailing()
    }
}
