package com.robert4lyf.hypershop.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.ui.theme.Brand
import com.robert4lyf.hypershop.ui.theme.card
import com.robert4lyf.hypershop.ui.theme.heroBrush
import com.robert4lyf.hypershop.ui.theme.heroGlow
import com.robert4lyf.hypershop.ui.theme.style

/** The aisle's icon on its colour: the same mark on every screen. */
@Composable
fun AisleBadge(
    aisle: AisleCode,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    val s = aisle.style
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(s.content),
        contentAlignment = Alignment.Center,
    ) {
        Icon(s.icon, contentDescription = null, tint = s.container, modifier = Modifier.size(size * 0.56f))
    }
}

/** Where a lazy-list item sits in its card, so separate items draw as one rounded block. */
enum class SectionPosition { FIRST, MIDDLE, LAST, ONLY }

fun sectionPosition(
    index: Int,
    count: Int,
): SectionPosition =
    when {
        count == 1 -> SectionPosition.ONLY
        index == 0 -> SectionPosition.FIRST
        index == count - 1 -> SectionPosition.LAST
        else -> SectionPosition.MIDDLE
    }

private val CARD = 24.dp
private val JOIN = 6.dp

fun SectionPosition.shape(): Shape =
    when (this) {
        SectionPosition.FIRST -> RoundedCornerShape(topStart = CARD, topEnd = CARD, bottomStart = JOIN, bottomEnd = JOIN)
        SectionPosition.MIDDLE -> RoundedCornerShape(JOIN)
        SectionPosition.LAST -> RoundedCornerShape(topStart = JOIN, topEnd = JOIN, bottomStart = CARD, bottomEnd = CARD)
        SectionPosition.ONLY -> RoundedCornerShape(CARD)
    }

/**
 * One piece of a card made of several lazy items: the gutter, the shape for
 * [position] and the card colour. Pieces sit 2 dp apart, like a stack of tiles.
 */
fun Modifier.sectionItem(
    position: SectionPosition,
    color: Color,
): Modifier =
    this
        .padding(horizontal = 16.dp)
        .padding(top = if (position == SectionPosition.FIRST || position == SectionPosition.ONLY) 0.dp else 2.dp)
        .clip(position.shape())
        .background(color)

/** A whole card for non-lazy content. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.card,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(CARD), color = color) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** A card's or a group's small heading, with an optional leading icon. */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        icon?.let { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A screen's hero: the large title on the basil gradient, with an overline and
 * subtitle and room for actions and more below. Content inside is white
 * ([LocalContentColor]), so icon buttons in [actions] need no colours.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    overline: String? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    below: @Composable ColumnScope.() -> Unit = {},
) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(heroBrush)
            .heroGlow(),
    ) {
        CompositionLocalProvider(LocalContentColor provides Brand.OnHero) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 18.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f).padding(top = 4.dp)) {
                        overline?.let {
                            Text(it.uppercase(), style = MaterialTheme.typography.labelMedium, color = Brand.Citrus)
                        }
                        Text(title, style = MaterialTheme.typography.headlineLarge, color = Brand.OnHero)
                        subtitle?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = Brand.OnHeroSoft)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, content = actions)
                }
                Column(Modifier.padding(end = 12.dp), content = below)
            }
        }
    }
}

/** A white pill button for a hero, basil text on it. */
@Composable
fun HeroButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF0B6B3E)),
        contentPadding = PaddingValues(horizontal = 14.dp),
        content = content,
    )
}

/** The round tick: open (ring), partly ticked (half-filled with a dash), done (filled with a check). */
@Composable
fun TickMark(
    done: Boolean,
    partial: Boolean = false,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    onColor: Color = MaterialTheme.colorScheme.onPrimary,
    softColor: Color = MaterialTheme.colorScheme.primaryContainer,
) {
    val fill by animateColorAsState(
        when {
            done -> color
            partial -> softColor
            else -> Color.Transparent
        },
        label = "tick",
    )
    val ring = if (done || partial) color else MaterialTheme.colorScheme.outline
    Box(
        modifier.size(size).clip(CircleShape).background(fill).border(2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            done -> Icon(Icons.Rounded.Check, null, tint = onColor, modifier = Modifier.size(size * 0.7f))
            partial -> Icon(Icons.Rounded.Remove, null, tint = color, modifier = Modifier.size(size * 0.7f))
        }
    }
}

enum class Tone { NEUTRAL, PRIMARY, WARNING, ERROR }

/** An icon and text on a tinted container: notices, privacy, errors. */
@Composable
fun InfoBanner(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.NEUTRAL,
) {
    val (bg, fg) =
        when (tone) {
            Tone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurface
            Tone.PRIMARY -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
            Tone.WARNING -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
            Tone.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = bg, contentColor = fg) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

/** A large icon in a soft circle, a headline and whatever explains the next step. */
@Composable
fun EmptyHero(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(104.dp).clip(CircleShape).background(heroBrush).heroGlow(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Brand.OnHero, modifier = Modifier.size(52.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineSmall)
        body?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

/**
 * A raised bar pinned to the bottom of a screen, for its main action. It draws
 * down to the screen edge and pads its content for the navigation bar, so the
 * screen using it must not be padded for that inset as well.
 */
@Composable
fun BottomActionBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp, shadowElevation = 8.dp) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(
                Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

/** A sheet's header: the aisle badge, the item's name and its aisle. */
@Composable
fun SheetHeader(
    name: String,
    aisle: AisleCode,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        AisleBadge(aisle, size = 48.dp)
        Column {
            Text(name.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.headlineSmall)
            Text(aisle.displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * An amount field and its button ("Set" on the item sheet). The label stays visible (and spoken) once something is typed;
 * [onSubmit] returns false when the text has no amount.
 */
@Composable
fun AmountField(
    heading: String,
    label: String,
    button: String,
    tag: String,
    onSubmit: (String) -> Boolean,
) {
    var amount by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(heading)
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = amount,
                onValueChange = {
                    amount = it
                    bad = false
                },
                label = { Text(label) },
                isError = bad,
                supportingText = if (bad) ({ Text("Enter an amount, like 2 or 200 ml") }) else null,
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f).testTag(tag),
            )
            FilledTonalButton(
                onClick = { bad = !onSubmit(amount) },
                enabled = amount.isNotBlank(),
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(button) }
        }
    }
}
