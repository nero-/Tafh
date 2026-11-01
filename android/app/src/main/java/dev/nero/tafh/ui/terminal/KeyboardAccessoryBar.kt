package dev.nero.tafh.ui.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nero.tafh.ui.components.ChuButton
import dev.nero.tafh.ui.components.ChuButtonSurface
import dev.nero.tafh.ui.components.ChuButtonVariant
import dev.nero.tafh.ui.components.ChuText
import dev.nero.tafh.ui.components.minimumInteractiveComponentSize
import dev.nero.tafh.ui.theme.ChuColors
import dev.nero.tafh.ui.theme.ChuSymbolsFontFamily
import dev.nero.tafh.ui.theme.ChuTypography
import kotlinx.coroutines.withTimeoutOrNull

private const val INITIAL_REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 50L

/** Height of the drawn key; every key still gets a 48dp-tall touch target around it. */
private val KEY_HEIGHT: Dp = 30.dp

/** Gap between neighbouring keys. */
private val KEY_GAP: Dp = 4.dp

/** Extra space separating the terminal keys from the app buttons (⌘, files, settings). */
private val BLOCK_GAP: Dp = 10.dp

/**
 * Narrowest key before the bar scrolls instead of shrinking further. Below this even a three-letter
 * label becomes too small to read on a folded phone.
 */
internal val MIN_KEY_WIDTH: Dp = 34.dp

/** Widest key: on an unfolded or landscape screen, wider keys only spread the bar out. */
internal val MAX_KEY_WIDTH: Dp = 72.dp

/** Space kept between a key's label and its border. */
private val KEY_LABEL_PADDING: Dp = 3.dp

/** Average glyph advance of the bundled monospace fonts, in em, with a little slack. */
private const val MONO_ADVANCE_EM = 0.62f

/** Smallest label the bar shrinks to before it starts scrolling instead. */
private const val MIN_LABEL_SP = 9f

private val APP_GLYPH_SIZE = 16.sp

/** Buttons that belong to the app rather than to the terminal; always shown after the keys. */
private enum class AppButton {
    TafhKey,
    Files,
    Settings,
}

/**
 * Splits [items] into at most [rowCount] rows of equal length (the last may be shorter), keeping
 * their order: the first half of the layout is the top row, the rest the bottom row.
 *
 * Every row has the same number of columns no matter how wide the screen is, so folding or rotating
 * the device only resizes the keys; it never moves a key to another row.
 */
internal fun <T> splitIntoRows(items: List<T>, rowCount: Int): List<List<T>> {
    if (items.isEmpty()) return emptyList()
    val rows = rowCount.coerceAtLeast(1)
    val perRow = (items.size + rows - 1) / rows
    return items.chunked(perRow)
}

/**
 * Width of one key when [columns] equal keys and [gaps] of spacing share [available] width, capped
 * at [MAX_KEY_WIDTH]. A result below [MIN_KEY_WIDTH] means the keys do not fit and the bar scrolls.
 */
internal fun accessoryKeyWidth(available: Dp, columns: Int, gaps: Dp): Dp {
    if (columns <= 0) return MAX_KEY_WIDTH
    return ((available - gaps) / columns).coerceAtMost(MAX_KEY_WIDTH)
}

/**
 * Label size that fits a label of [longestLabelLength] characters in a [keyWidth] key. One size is
 * used for the whole bar so neighbouring keys never mix label sizes.
 */
internal fun fittedLabelSize(
    longestLabelLength: Int,
    keyWidth: Dp,
    fontScale: Float,
    maxSp: Float,
): TextUnit {
    val chars = longestLabelLength.coerceAtLeast(1)
    val textWidthDp = keyWidth.value - 2 * KEY_LABEL_PADDING.value
    // Width is in dp; labels are in sp, which the user's font scale enlarges.
    val fitSp = textWidthDp / (chars * MONO_ADVANCE_EM) / fontScale.coerceAtLeast(0.1f)
    return fitSp.coerceIn(MIN_LABEL_SP, maxSp).sp
}

/**
 * The terminal's extra-key bar: keys in a grid of equal-width columns, then the app buttons.
 *
 * The grid replaces a FlowRow that wrapped wherever the width ran out, so folding or rotating the
 * device re-flowed keys between rows, the rows never lined up, and keys past the second row were
 * silently dropped. Now each row holds a fixed half of the layout, keys resize to fill the width,
 * and when even the narrowest keys do not fit the bar scrolls instead of losing keys.
 */
@Composable
fun KeyboardAccessoryBar(
    entries: List<ResolvedAccessoryEntry>,
    modifierState: ModifierState,
    onAction: (AccessoryAction) -> Unit,
    onSettings: (() -> Unit)? = null,
    onTafhKey: (() -> Unit)? = null,
    tafhKeyActive: Boolean = false,
    onOpenFiles: (() -> Unit)? = null,
    useSingleRow: Boolean = false,
    horizontalPadding: Dp = 8.dp,
    // Each key's 48dp touch target already includes 9dp of invisible padding above and below its
    // 30dp glyph pill, so no outer vertical padding is needed to keep the glyphs breathing.
    verticalPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val typography = ChuTypography.current
    var expandedGroupId by remember { mutableStateOf<String?>(null) }
    val rowCount = if (useSingleRow) 1 else 2
    val appButtons =
        listOfNotNull(
            AppButton.TafhKey.takeIf { onTafhKey != null },
            AppButton.Files.takeIf { onOpenFiles != null },
            AppButton.Settings.takeIf { onSettings != null },
        )
    val keyRows = splitIntoRows(entries, rowCount)
    // With two rows the app buttons sit as a 2x2 block whose empty corner is top right, so ⌘ lines
    // up above files and the bottom row ends at settings.
    val appRows =
        if (rowCount == 1 || appButtons.size <= 1) listOf(appButtons)
        else listOf(appButtons.take(appButtons.size - 2), appButtons.takeLast(2))
    val keyColumns = keyRows.maxOfOrNull { it.size } ?: 0
    val appColumns = appRows.maxOf { it.size }

    Column(modifier = modifier) {
        expandedGroupId?.let { groupId ->
            val expandedGroup =
                entries.firstOrNull { it is ResolvedAccessoryEntry.Group && it.group.id == groupId }
                    as? ResolvedAccessoryEntry.Group
            if (expandedGroup != null) {
                GroupPopover(
                    group = expandedGroup.group,
                    modifierState = modifierState,
                    onAction = onAction,
                    horizontalPadding = horizontalPadding,
                )
            }
        }

        BoxWithConstraints(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding)
        ) {
            val keyGapCount = (keyColumns - 1).coerceAtLeast(0) + (appColumns - 1).coerceAtLeast(0)
            val blockGap = if (keyColumns > 0 && appColumns > 0) BLOCK_GAP else 0.dp
            val gaps = KEY_GAP * keyGapCount + blockGap
            val fittedWidth = accessoryKeyWidth(maxWidth, keyColumns + appColumns, gaps)
            val scrolls = fittedWidth < MIN_KEY_WIDTH
            val keyWidth = if (scrolls) MIN_KEY_WIDTH else fittedWidth
            val longestLabel = entries.maxOfOrNull { it.label.length } ?: 1
            val labelStyle =
                typography.label.copy(
                    fontSize =
                        fittedLabelSize(
                            longestLabelLength = longestLabel,
                            keyWidth = keyWidth,
                            fontScale = LocalDensity.current.fontScale,
                            maxSp = typography.label.fontSize.value,
                        )
                )
            val cell =
                KeyCell(
                    width = keyWidth,
                    labelStyle = labelStyle,
                    contentPadding = PaddingValues(horizontal = KEY_LABEL_PADDING),
                )

            Row(
                modifier =
                    if (scrolls) Modifier.horizontalScroll(rememberScrollState())
                    else Modifier.fillMaxWidth(),
                horizontalArrangement =
                    if (scrolls) Arrangement.spacedBy(BLOCK_GAP) else Arrangement.SpaceBetween,
                // A single app-button row lines up with the bottom key row.
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    keyRows.forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                            row.forEach { entry ->
                                AccessoryEntry(
                                    entry = entry,
                                    expanded = expandedGroupId == entry.id,
                                    modifierState = modifierState,
                                    onAction = onAction,
                                    onToggleGroup = { id ->
                                        expandedGroupId = if (expandedGroupId == id) null else id
                                    },
                                    cell = cell,
                                )
                            }
                        }
                    }
                }
                Column {
                    appRows.forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                            row.forEach { button ->
                                when (button) {
                                    AppButton.TafhKey ->
                                        TafhKeyButton(
                                            onClick = onTafhKey ?: {},
                                            active = tafhKeyActive,
                                            cell = cell,
                                        )
                                    AppButton.Files -> FilesButton(onOpenFiles ?: {}, cell)
                                    AppButton.Settings -> SettingsButton(onSettings ?: {}, cell)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Size and label style shared by every key in the bar. A null [width] lets a key wrap its label.
 */
private data class KeyCell(
    val width: Dp?,
    val labelStyle: TextStyle,
    val contentPadding: PaddingValues,
) {
    fun modifier(): Modifier = if (width != null) Modifier.width(width) else Modifier
}

@Composable
private fun KeyLabel(text: String, cell: KeyCell, color: Color) {
    ChuText(
        text,
        style = cell.labelStyle,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Clip,
    )
}

@Composable
private fun GroupPopover(
    group: AccessoryKeyGroup,
    modifierState: ModifierState,
    onAction: (AccessoryAction) -> Unit,
    horizontalPadding: Dp,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val cell =
        KeyCell(
            width = null,
            labelStyle = typography.label,
            contentPadding = PaddingValues(horizontal = 10.dp),
        )
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = horizontalPadding, vertical = 4.dp)
                    .background(colors.surface)
                    .padding(horizontal = 6.dp, vertical = 6.dp)
                    .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            group.children.forEach { child ->
                AccessoryButton(
                    item = child,
                    modifierState = modifierState,
                    onAction = onAction,
                    cell = cell,
                )
            }
        }
    }
}

@Composable
private fun AccessoryEntry(
    entry: ResolvedAccessoryEntry,
    expanded: Boolean,
    modifierState: ModifierState,
    onAction: (AccessoryAction) -> Unit,
    onToggleGroup: (String) -> Unit,
    cell: KeyCell,
) {
    when (entry) {
        is ResolvedAccessoryEntry.Single ->
            AccessoryButton(
                item = entry.item,
                modifierState = modifierState,
                onAction = onAction,
                cell = cell,
            )
        is ResolvedAccessoryEntry.Group ->
            GroupButton(
                group = entry.group,
                expanded = expanded,
                onToggle = { onToggleGroup(entry.group.id) },
                cell = cell,
            )
    }
}

@Composable
private fun GroupButton(
    group: AccessoryKeyGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    cell: KeyCell,
) {
    val colors = ChuColors.current
    ChuButton(
        onClick = onToggle,
        modifier = cell.modifier(),
        variant = if (expanded) ChuButtonVariant.Filled else ChuButtonVariant.Outlined,
        // Drawn key stays 30dp; ChuButton expands the touch target to 48dp.
        minHeight = KEY_HEIGHT,
        minTouchTargetWidth = MIN_KEY_WIDTH,
        contentPadding = cell.contentPadding,
    ) {
        KeyLabel(group.label, cell, if (expanded) colors.onAccent else colors.textPrimary)
    }
}

@Composable
private fun AccessoryButton(
    item: AccessoryKeyItem,
    modifierState: ModifierState,
    onAction: (AccessoryAction) -> Unit,
    cell: KeyCell,
) {
    val colors = ChuColors.current
    val toggleModifier = (item.action as? AccessoryAction.ToggleModifier)?.modifier
    if (toggleModifier != null) {
        ToggleButton(
            label = item.label,
            enabled = modifierState.isEnabled(toggleModifier),
            onClick = { onAction(item.action) },
            cell = cell,
        )
        return
    }
    val specialKey = (item.action as? AccessoryAction.SendSpecialKey)?.key
    if (specialKey != null && specialKey.isRepeatable) {
        RepeatableAccessoryButton(item = item, onAction = onAction, cell = cell)
        return
    }
    ChuButton(
        onClick = { onAction(item.action) },
        modifier = cell.modifier(),
        variant = ChuButtonVariant.Outlined,
        // 30dp drawn key centered in ChuButton's 48dp touch target.
        minHeight = KEY_HEIGHT,
        minTouchTargetWidth = MIN_KEY_WIDTH,
        contentPadding = cell.contentPadding,
    ) {
        KeyLabel(item.label, cell, colors.textPrimary)
    }
}

@Composable
private fun TafhKeyButton(onClick: () -> Unit, active: Boolean, cell: KeyCell) {
    val colors = ChuColors.current
    ChuButton(
        onClick = onClick,
        modifier = cell.modifier().semantics { stateDescription = if (active) "on" else "off" },
        variant = if (active) ChuButtonVariant.Filled else ChuButtonVariant.Outlined,
        minHeight = KEY_HEIGHT,
        minTouchTargetWidth = MIN_KEY_WIDTH,
        contentPadding = cell.contentPadding,
        contentDescription = "Tafh key",
    ) {
        ChuText(
            "⌘",
            style = ChuTypography.current.label,
            color = if (active) colors.onAccent else colors.textPrimary,
        )
    }
}

@Composable
private fun FilesButton(onClick: () -> Unit, cell: KeyCell) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    ChuButton(
        onClick = onClick,
        modifier = cell.modifier(),
        variant = ChuButtonVariant.Outlined,
        minHeight = KEY_HEIGHT,
        minTouchTargetWidth = MIN_KEY_WIDTH,
        contentPadding = cell.contentPadding,
        contentDescription = "Files",
    ) {
        // Folder glyph from the bundled symbols font (a private-use code point).
        ChuText(
            text = "",
            style =
                TextStyle(
                    fontFamily = ChuSymbolsFontFamily,
                    fontWeight = typography.label.fontWeight,
                    fontStyle = typography.label.fontStyle,
                    fontSize = APP_GLYPH_SIZE,
                    lineHeight = APP_GLYPH_SIZE,
                ),
            color = colors.textPrimary,
        )
    }
}

@Composable
private fun SettingsButton(onClick: () -> Unit, cell: KeyCell) {
    ChuButton(
        onClick = onClick,
        modifier = cell.modifier(),
        variant = ChuButtonVariant.Outlined,
        minHeight = KEY_HEIGHT,
        minTouchTargetWidth = MIN_KEY_WIDTH,
        contentPadding = cell.contentPadding,
        contentDescription = "Settings",
    ) {
        ChuText("⚙", style = ChuTypography.current.label)
    }
}

@Composable
private fun RepeatableAccessoryButton(
    item: AccessoryKeyItem,
    onAction: (AccessoryAction) -> Unit,
    cell: KeyCell,
) {
    val colors = ChuColors.current
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val currentOnAction by rememberUpdatedState(onAction)

    Box(
        modifier =
            Modifier.pointerInput(item.action) {
                    awaitEachGesture {
                        awaitFirstDown().consume()
                        pressed = true
                        currentOnAction(item.action)

                        try {
                            val releasedDuringDelay =
                                withTimeoutOrNull(INITIAL_REPEAT_DELAY_MS) {
                                    waitForUpOrCancellation()
                                }

                            if (releasedDuringDelay == null) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                while (true) {
                                    currentOnAction(item.action)
                                    val released =
                                        withTimeoutOrNull(REPEAT_INTERVAL_MS) {
                                            waitForUpOrCancellation()
                                        }
                                    if (released != null) break
                                }
                            }
                        } finally {
                            pressed = false
                        }
                    }
                }
                .semantics {
                    role = Role.Button
                    contentDescription = item.label
                    onClick(label = item.label) {
                        currentOnAction(item.action)
                        true
                    }
                }
                // 48dp tall touch target; the drawn surface below stays KEY_HEIGHT (30dp). Width
                // comes from the grid cell (or the label, in the group popover).
                .minimumInteractiveComponentSize(minWidth = 0.dp)
                .then(cell.modifier()),
        // Forward the cell width to the drawn surface so it fills its grid column.
        propagateMinConstraints = cell.width != null,
        contentAlignment = Alignment.Center,
    ) {
        ChuButtonSurface(
            modifier = Modifier.height(KEY_HEIGHT),
            pressed = pressed,
            variant = ChuButtonVariant.Outlined,
            contentPadding = cell.contentPadding,
        ) {
            KeyLabel(item.label, cell, colors.textPrimary)
        }
    }
}

@Composable
private fun ToggleButton(label: String, enabled: Boolean, onClick: () -> Unit, cell: KeyCell) {
    val colors = ChuColors.current
    ChuButton(
        onClick = onClick,
        // The fill alone shows the modifier is on: a "• " prefix used to widen the key and shove
        // its neighbours along the row every time a modifier was toggled.
        modifier = cell.modifier().semantics { stateDescription = if (enabled) "on" else "off" },
        // 30dp drawn key centered in ChuButton's 48dp touch target.
        minHeight = KEY_HEIGHT,
        minTouchTargetWidth = MIN_KEY_WIDTH,
        contentPadding = cell.contentPadding,
        variant = if (enabled) ChuButtonVariant.Filled else ChuButtonVariant.Outlined,
    ) {
        // Off reads like every other key (it is not disabled); on is filled with the accent.
        KeyLabel(label, cell, if (enabled) colors.onAccent else colors.textPrimary)
    }
}
