package net.robog.clarity.app.ui.components

import net.robog.clarity.app.ui.theme.Space

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import net.robog.clarity.app.ui.theme.clarityText

/** The one horizontal margin of content screens (the call screens keep their own, roomier layout). */
val ScreenGutter = Space.Medium

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
    // An address, a link or an id keeps its case ("alex@example.com", "secure.chase.com", "api_key").
    if (first.trimEnd('.', ',', ':', ';', '!', '?').any { it in "@./_:" }) return text
    if (first.drop(1).any { it.isUpperCase() } || first.any { it.isDigit() }) return text
    return text.replaceFirstChar { it.titlecase() }
}

/** `.k`: the small caption line. Pass [heading] when it labels a section, so screen readers can jump to it. */
@Composable
fun Kicker(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    heading: Boolean = false,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
) {
    Text(
        sentenceCase(text),
        if (heading) modifier.semantics { heading() } else modifier,
        color = color,
        textAlign = textAlign,
        style = MaterialTheme.clarityText.kicker,
    )
}

/**
 * A Sage surface. Text and icons inside follow [ColorScheme.onSecondaryContainer] (6.12:1); use
 * [onSecondaryContainerMuted] (4.75:1) for a quieter line.
 */
@Composable
fun SageSurface(modifier: Modifier = Modifier, shape: Shape = MaterialTheme.shapes.large, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.background(colors.secondaryContainer, shape)) {
        CompositionLocalProvider(LocalContentColor provides colors.onSecondaryContainer, content = content)
    }
}

/** The Material 3 Expressive loading indicator, centred in the width it is given. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoadingSpinner(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = Space.Medium), contentAlignment = Alignment.Center) {
        LoadingIndicator()
    }
}

/** `.h`: the screen heading. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, textAlign: androidx.compose.ui.text.style.TextAlign? = null) {
    Text(text, modifier.semantics { heading() }, textAlign = textAlign, style = MaterialTheme.typography.headlineMedium)
}

/** `.sm`: the quiet explanatory paragraph under a heading. */
@Composable
fun Hint(
    text: String,
    modifier: Modifier = Modifier,
    italic: Boolean = false,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
) {
    Text(
        text,
        modifier,
        color = color,
        textAlign = textAlign,
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

/**
 * Filled pill button (`.btn.pri`). Pressing morphs the corners (Material 3 Expressive). While [busy]
 * the label gives way to a loading indicator, the button keeps its size and further taps are ignored,
 * so slow work shows progress instead of a dead, greyed-out button.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false) {
    Button(
        onClick = { if (!busy) onClick() },
        modifier = modifier.heightIn(min = 48.dp).busySemantics(busy),
        enabled = enabled || busy,
        shapes = ButtonDefaults.shapes(),
        contentPadding = ButtonDefaults.ContentPadding,
    ) { BusyLabel(text, busy) }
}

/** Outlined pill button (`.btn`); presses and [busy] behave like [PrimaryButton]. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false) {
    val active = enabled || busy
    OutlinedButton(
        onClick = { if (!busy) onClick() },
        modifier = modifier.heightIn(min = 48.dp).busySemantics(busy),
        enabled = active,
        shapes = ButtonDefaults.shapes(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (active) 1f else 0.38f)),
    ) { BusyLabel(text, busy) }
}

/** The label and a loading indicator stacked, cross-fading, so the button never changes size. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BusyLabel(text: String, busy: Boolean) {
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val labelAlpha by animateFloatAsState(if (busy) 0f else 1f, effects, label = "label")
    val spinnerAlpha by animateFloatAsState(if (busy) 1f else 0f, effects, label = "spinner")
    Box(contentAlignment = Alignment.Center) {
        Text(sentenceCase(text), Modifier.graphicsLayer { alpha = labelAlpha }, style = MaterialTheme.typography.labelLarge)
        if (busy || spinnerAlpha > 0f) {
            LoadingIndicator(Modifier.size(28.dp).graphicsLayer { alpha = spinnerAlpha }, color = LocalContentColor.current)
        }
    }
}

private fun Modifier.busySemantics(busy: Boolean): Modifier =
    if (busy) semantics { stateDescription = "Working" } else this

/**
 * For a bottom-up chat list (`reverseLayout`, newest item first): when a new message arrives while
 * the person has scrolled up, glide back down to it. The first load needs nothing, since a reversed
 * list already starts at the bottom.
 */
@Composable
fun FollowNewest(listState: LazyListState, count: Int) {
    var seen by remember { mutableIntStateOf(count) }
    LaunchedEffect(count) {
        if (count > seen && seen > 0 && listState.firstVisibleItemIndex > 0) listState.animateScrollToItem(0)
        seen = count
    }
}

/** A small loading indicator beside a line of status text ("Checking…"), for work that has no button to live in. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WorkingLine(text: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Space.Medium), verticalAlignment = Alignment.CenterVertically) {
        LoadingIndicator(Modifier.size(32.dp))
        Hint(text)
    }
}

/** `.back`: the button that goes to the previous step. [text] names where it goes ("Settings"). */
@Composable
fun BackLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // A top-bar style back row: the arrow icon lines up with the content edge, the label names where it goes.
    Row(
        modifier
            .offset(x = (-12).dp)
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = "Go back to ${sentenceCase(text)}", onClick = onClick)
            .padding(end = Space.Medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
        }
        Text(sentenceCase(text.trim()), style = MaterialTheme.typography.titleMedium)
    }
}

/** The 4-segment progress bar at the top of the onboarding screens. */
@Composable
fun StepBar(activeSegments: Int, modifier: Modifier = Modifier, segments: Int = 4) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.Small)) {
        repeat(segments) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .background(
                        if (i < activeSegments) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        MaterialTheme.shapes.extraSmall,
                    ),
            )
        }
    }
}

/** `.bx`: a tinted box. Only a box that needs attention passes a [border]. */
@Composable
fun InfoBox(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: Color = MaterialTheme.colorScheme.onSurface,
    border: Color? = null,
    shape: Shape = MaterialTheme.shapes.medium,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(container, shape)
            .let { if (border != null) it.border(1.dp, border, shape) else it }
            .padding(Space.Medium),
        verticalArrangement = Arrangement.spacedBy(Space.Small),
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
        // Polite live region: a failure that shows up after a tap is read out.
        modifier
            .semantics { liveRegion = LiveRegionMode.Polite }
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.medium)
            .padding(horizontal = Space.Medium, vertical = Space.Medium),
        verticalArrangement = Arrangement.spacedBy(Space.Small),
    ) {
        Text(title, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelLarge)
        if (detail != null) Hint(detail, color = MaterialTheme.colorScheme.onErrorContainer)
        // The server's own words, small, for whoever has to debug it.
        if (raw != null) {
            Text(
                raw,
                Modifier.padding(top = Space.Small),
                color = MaterialTheme.colorScheme.onErrorContainer,
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
    /** Show a right-pointing chevron when [status] is empty (a row that only opens something). */
    chevron: Boolean = false,
    /** Spoken state for a row in trouble, e.g. "Needs attention". */
    stateDescription: String? = null,
    /** Its place in a [CardGroup]; alone, a card with big corners all round. */
    shape: Shape = groupShape(0, 1),
) {
    SegmentCard(shape, modifier.semantics { if (stateDescription != null) this.stateDescription = stateDescription }, onClick = onClick, minHeight = 64.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.Medium), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
                Text(sentenceCase(title), style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal))
                if (caption.isNotEmpty()) Kicker(caption)
            }
            if (status.isNotEmpty()) Text(sentenceCase(status), color = statusColor.copy(alpha = statusAlpha), style = MaterialTheme.typography.labelLarge)
            if (chevron) androidx.compose.material3.Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** `.tg`: a labelled switch row, a card of its own (or a segment of a [CardGroup] through [shape]). */
@Composable
fun ToggleRow(
    title: String,
    caption: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = groupShape(0, 1),
) {
    SegmentCard(
        shape, modifier, minHeight = 64.dp,
        interaction = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.Medium), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
                Text(sentenceCase(title), style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal))
                if (caption.isNotEmpty()) Kicker(caption)
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
        border = androidx.compose.material3.FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outline,
        ),
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
