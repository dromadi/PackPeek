package com.packpeek.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.packpeek.app.R

/** Rounded container that groups related rows, separated by thin dividers. */
@Composable
fun RowGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier) {
        if (title != null) SectionTitle(title)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
    )
}

@Composable
fun GroupDivider() {
    HorizontalDivider(
        Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    )
}

/** Label/value row for use inside a [RowGroup]. */
@Composable
fun InfoLine(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        trailing?.invoke()
    }
}

/** Settings-style row: optional icon, title, supporting text and trailing slot. */
@Composable
fun ActionRow(
    title: String,
    supporting: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null)
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    supporting: String? = null,
    icon: ImageVector? = null,
    onChange: (Boolean) -> Unit,
) {
    ActionRow(title, supporting, icon, onClick = { onChange(!checked) }) {
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Row that opens a radio-button dialog; the current choice is shown under the title. */
@Composable
fun ChoiceRow(
    title: String,
    value: String,
    options: List<Pair<String, Int>>,
    icon: ImageVector? = null,
    supporting: String? = null,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == value } ?: options.first()
    ActionRow(
        title,
        listOfNotNull(stringResource(current.second), supporting).joinToString("\n"),
        icon,
        onClick = { open = true },
    )
    if (open)
        ChoiceDialog(title, current.first, options, { open = false }) {
            onSelect(it)
            open = false
        }
}

@Composable
fun ChoiceDialog(
    title: String,
    selected: String,
    options: List<Pair<String, Int>>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (key, label) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(
                                selected = key == selected,
                                role = Role.RadioButton,
                                onClick = { onSelect(key) },
                            )
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = key == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/**
 * Filter chip with a dropdown. Shows the category name while on its default value and the
 * chosen value (highlighted) otherwise.
 */
@Composable
fun FilterMenuChip(
    label: String,
    value: String,
    default: String,
    options: List<Pair<String, Int>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val active = value != default
    val current = options.firstOrNull { it.first == value }
    Box {
        FilterChip(
            selected = active,
            onClick = { expanded = true },
            label = {
                Text(
                    if (active && current != null) stringResource(current.second) else label,
                    maxLines = 1,
                )
            },
            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, title) ->
                DropdownMenuItem(
                    text = { Text(stringResource(title)) },
                    leadingIcon = { RadioButton(selected = key == value, onClick = null) },
                    onClick = {
                        onSelect(key)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String? = null, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(72.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    null,
                    Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

/** Small rounded label, e.g. "Split" or "System". */
@Composable
fun Tag(text: String, color: Color = MaterialTheme.colorScheme.secondaryContainer) {
    Surface(color = color, shape = MaterialTheme.shapes.extraSmall) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** Severity tone for the heuristic score: green, amber or red. */
@Composable
fun scoreColors(score: Int): Pair<Color, Color> {
    val dark = MaterialTheme.colorScheme.surface.luminanceBelowHalf()
    return when {
        score >= 50 ->
            if (dark) Color(0xFF93000A) to Color(0xFFFFDAD6) else Color(0xFFFFDAD6) to Color(0xFF410002)
        score >= 25 ->
            if (dark) Color(0xFF5C4300) to Color(0xFFFFDEA6) else Color(0xFFFFDEA6) to Color(0xFF261900)
        else ->
            if (dark) Color(0xFF00522B) to Color(0xFF9DF6B8) else Color(0xFFB7F1C9) to Color(0xFF00210E)
    }
}

private fun Color.luminanceBelowHalf() = (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f
