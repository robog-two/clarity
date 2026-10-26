package net.robog.clarity.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import net.robog.clarity.app.ui.theme.clarityText

/** The one horizontal margin of content screens (the call screens keep their own, roomier layout). */
val ScreenGutter = 20.dp

/** Material's ListItem pads its content by 16dp; this widens it so its text lines up with the content edge. */
fun Modifier.alignListItemToContent(): Modifier = layout { measurable, constraints ->
    val inset = 16.dp.roundToPx()
    val wide = if (constraints.hasBoundedWidth) constraints.maxWidth + inset * 2 else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = wide, maxWidth = wide))
    layout(constraints.constrainWidth(placeable.width - inset * 2), placeable.height) { placeable.place(-inset, 0) }
}

/**
 * Pure: sentence case for UI labels ("not now" → "Not now"); the rest of the text is kept. A first
 * word that is already mixed case or carries a digit is a name or a code ("iCloud", "v3") and stays as it is.
 */
fun sentenceCase(text: String): String {
    val first = text.substringBefore(' ')
    if (first.drop(1).any { it.isUpperCase() } || first.any { it.isDigit() }) return text
    return text.replaceFirstChar { it.titlecase() }
}

/** `.k`: the small caption line. */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(sentenceCase(text), modifier, color = color, style = MaterialTheme.clarityText.kicker)
}

/** `.h`: the screen heading. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.headlineMedium)
}

/** `.sm`: the quiet explanatory paragraph under a heading. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier, italic: Boolean = false, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        modifier,
        color = color,
        style = MaterialTheme.clarityText.readingSmall.let {
            if (italic) it.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic) else it
        },
    )
}

/** `.rd`: reading text. */
@Composable
fun ReadingText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(text, modifier, color = color, style = MaterialTheme.clarityText.reading)
}

/** Filled pill button (`.btn.pri`). */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = MaterialTheme.shapes.extraLarge,
        contentPadding = ButtonDefaults.ContentPadding,
    ) { Text(sentenceCase(text), style = MaterialTheme.typography.labelLarge) }
}

/** Outlined pill button (`.btn`). */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = MaterialTheme.shapes.extraLarge,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 1f else 0.38f)),
    ) { Text(sentenceCase(text), style = MaterialTheme.typography.labelLarge) }
}

/** `.back`: the "← previous step" text button. */
@Composable
fun BackLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // A top-bar style back row: the arrow icon lines up with the content edge, the label names where it goes.
    Row(
        modifier.offset(x = (-12).dp).heightIn(min = 48.dp).clickable(onClick = onClick).padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = null,
            modifier = Modifier.padding(12.dp),
        )
        Text(sentenceCase(text.removePrefix("←").trim()), style = MaterialTheme.typography.titleMedium)
    }
}

/** The 4-segment progress bar at the top of the onboarding screens. */
@Composable
fun StepBar(activeSegments: Int, modifier: Modifier = Modifier, segments: Int = 4) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(segments) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .background(
                        if (i < activeSegments) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.shapes.extraSmall,
                    ),
            )
        }
    }
}

/** `.bx`: a bordered, tinted box. */
@Composable
fun InfoBox(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: Color = MaterialTheme.colorScheme.onSurface,
    border: Color? = MaterialTheme.colorScheme.outlineVariant,
    shape: Shape = MaterialTheme.shapes.medium,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(container, shape)
            .let { if (border != null) it.border(1.dp, border, shape) else it }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides content,
        ) { body() }
    }
}

/** `.err`: the red message box. */
@Composable
fun ErrorBox(title: String, detail: String? = null, modifier: Modifier = Modifier, raw: String? = null) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.medium)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelLarge)
        if (detail != null) Hint(detail, color = MaterialTheme.colorScheme.onErrorContainer)
        // The server's own words, small, for whoever has to debug it.
        if (raw != null) {
            Text(
                raw,
                Modifier.padding(top = 2.dp),
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** `.row`: a tappable list row with a title, a caption and a status on the right. */
@Composable
fun SelectRow(
    title: String,
    caption: String,
    status: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    statusAlpha: Float = 1f,
    statusColor: Color = MaterialTheme.colorScheme.primary,
) {
    Column(modifier) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(sentenceCase(title), style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal))
                Kicker(caption)
            }
            Text(sentenceCase(status), color = statusColor.copy(alpha = statusAlpha), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** `.tg`: a labelled switch row. */
@Composable
fun ToggleRow(title: String, caption: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable { onCheckedChange(!checked) },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(sentenceCase(title), style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal))
                Kicker(caption)
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

/** `.pill`: one choice of a row (time, weekday). */
@Composable
fun RowScope.PillChoice(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, description: String = label) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                label,
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelLarge,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        },
        shape = MaterialTheme.shapes.small,
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .androidxSemantics(description, selected),
    )
}

private fun Modifier.androidxSemantics(description: String, selected: Boolean): Modifier =
    this.then(
        androidx.compose.ui.Modifier.semantics {
            contentDescription = description
            this.selected = selected
        },
    )

/** The prototype's masked key display: the first [visible] characters stay readable, the rest become bullets. */
class MaskedKeyTransformation(private val visible: Int = 13) : VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.input.TransformedText =
        androidx.compose.ui.text.input.TransformedText(
            androidx.compose.ui.text.AnnotatedString(maskKey(text.text, visible)),
            androidx.compose.ui.text.input.OffsetMapping.Identity,
        )
}

/** Pure: `sk-ant-api03-••••••••`. */
fun maskKey(value: String, visible: Int = 13): String =
    if (value.length <= visible) value else value.take(visible) + "•".repeat(value.length - visible)

/** Outlined text field in the app's style. */
@Composable
fun ClarityField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: String? = null,
    supporting: String? = null,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = true,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
        shape = MaterialTheme.shapes.extraSmall,
    )
}

/** Dropdown over a few fixed choices, styled like [ClarityField]. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ClarityDropdown(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    error: String? = null,
) {
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.material3.ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second.orEmpty(),
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            label = { Text(label) },
            trailingIcon = { androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            isError = error != null,
            supportingText = (error ?: supporting)?.let { { Text(it) } },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
            shape = MaterialTheme.shapes.extraSmall,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, title) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(title) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}
