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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
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

/**
 * Width of each accessory key's invisible touch area.
 *
 * The accessibility minimum is 48dp, but the bar packs up to 13 keys into two FlowRow lines on a
 * narrow (folded) screen. Forcing 48dp width overflows the row and drops the trailing keys off the
 * layout entirely, which is worse than a slightly narrow target; height still reaches 48dp.
 */
private val ACCESSORY_TOUCH_WIDTH: Dp = 44.dp

@OptIn(ExperimentalLayoutApi::class)
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
    val buttonHeight = 30.dp
    val buttonPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 3.dp, bottom = 3.dp)
    var expandedGroupId by remember { mutableStateOf<String?>(null) }
    val onChildAction: (AccessoryAction) -> Unit = { action -> onAction(action) }

    Column(modifier = modifier) {
        expandedGroupId?.let { groupId ->
            val expandedGroup =
                entries.firstOrNull { it is ResolvedAccessoryEntry.Group && it.group.id == groupId }
                    as? ResolvedAccessoryEntry.Group
            if (expandedGroup != null) {
                GroupPopover(
                    group = expandedGroup.group,
                    modifierState = modifierState,
                    onAction = onChildAction,
                    buttonHeight = buttonHeight,
                    buttonPadding = buttonPadding,
                    horizontalPadding = horizontalPadding,
                )
            }
        }

        if (useSingleRow) {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(horizontal = horizontalPadding, vertical = verticalPadding)
                        .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                entries.forEach { entry ->
                    AccessoryEntry(
                        entry = entry,
                        expanded = expandedGroupId == entry.id,
                        modifierState = modifierState,
                        onAction = onAction,
                        onToggleGroup = { id ->
                            expandedGroupId = if (expandedGroupId == id) null else id
                        },
                        buttonHeight = buttonHeight,
                        buttonPadding = buttonPadding,
                    )
                }
                FilesButton(
                    onTafhKey = onTafhKey,
                    tafhKeyActive = tafhKeyActive,
                    onOpenFiles = onOpenFiles,
                    buttonHeight = buttonHeight,
                    buttonPadding = buttonPadding,
                )
                SettingsButton(
                    onSettings = onSettings,
                    buttonHeight = buttonHeight,
                    buttonPadding = buttonPadding,
                )
            }
            return
        }

        // Every key draws a 30dp pill but carries a 48dp touch target, so each row occupies 48dp
        // (30dp pill + 9dp invisible padding above and below). The glyphs and their gaps are
        // unchanged; only the invisible padding makes the bar taller.
        //
        // The ⌘/⚙ actions live OUTSIDE the FlowRow on purpose: FlowRow silently drops items that
        // do not fit within maxLines, and these two are the only in-terminal route to the file
        // browser and settings, so they must never be the ones that disappear.
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                maxLines = 2,
            ) {
                entries.forEach { entry ->
                    AccessoryEntry(
                        entry = entry,
                        expanded = expandedGroupId == entry.id,
                        modifierState = modifierState,
                        onAction = onAction,
                        onToggleGroup = { id ->
                            expandedGroupId = if (expandedGroupId == id) null else id
                        },
                        buttonHeight = buttonHeight,
                        buttonPadding = buttonPadding,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilesButton(
                    onTafhKey = onTafhKey,
                    tafhKeyActive = tafhKeyActive,
                    onOpenFiles = onOpenFiles,
                    buttonHeight = buttonHeight,
                    buttonPadding = buttonPadding,
                )
                SettingsButton(
                    onSettings = onSettings,
                    buttonHeight = buttonHeight,
                    buttonPadding = buttonPadding,
                )
            }
        }
    }
}

@Composable
private fun GroupPopover(
    group: AccessoryKeyGroup,
    modifierState: ModifierState,
    onAction: (AccessoryAction) -> Unit,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
    horizontalPadding: Dp,
) {
    val colors = ChuColors.current
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
                    buttonHeight = buttonHeight,
                    buttonPadding = buttonPadding,
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
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
) {
    when (entry) {
        is ResolvedAccessoryEntry.Single ->
            AccessoryButton(
                item = entry.item,
                modifierState = modifierState,
                onAction = onAction,
                buttonHeight = buttonHeight,
                buttonPadding = buttonPadding,
            )
        is ResolvedAccessoryEntry.Group ->
            GroupButton(
                group = entry.group,
                expanded = expanded,
                onToggle = { onToggleGroup(entry.group.id) },
                buttonHeight = buttonHeight,
                buttonPadding = buttonPadding,
            )
    }
}

@Composable
private fun GroupButton(
    group: AccessoryKeyGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    ChuButton(
        onClick = onToggle,
        variant = if (expanded) ChuButtonVariant.Filled else ChuButtonVariant.Outlined,
        // Drawn key stays 30dp; ChuButton expands the touch target to 48dp.
        minHeight = buttonHeight,
        minTouchTargetWidth = ACCESSORY_TOUCH_WIDTH,
        contentPadding = buttonPadding,
    ) {
        ChuText(
            group.label,
            style = typography.label,
            color = if (expanded) colors.onAccent else colors.textPrimary,
        )
    }
}

@Composable
private fun AccessoryButton(
    item: AccessoryKeyItem,
    modifierState: ModifierState,
    onAction: (AccessoryAction) -> Unit,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
) {
    val typography = ChuTypography.current
    val toggleModifier = (item.action as? AccessoryAction.ToggleModifier)?.modifier
    if (toggleModifier != null) {
        ToggleButton(
            label = item.label,
            enabled = modifierState.isEnabled(toggleModifier),
            onClick = { onAction(item.action) },
            buttonHeight = buttonHeight,
            contentPadding = buttonPadding,
        )
    } else {
        val specialKey = (item.action as? AccessoryAction.SendSpecialKey)?.key
        if (specialKey != null && specialKey.isRepeatable) {
            RepeatableAccessoryButton(
                item = item,
                onAction = onAction,
                buttonHeight = buttonHeight,
                buttonPadding = buttonPadding,
            )
        } else {
            ChuButton(
                onClick = { onAction(item.action) },
                variant = ChuButtonVariant.Outlined,
                // 30dp drawn key centered in ChuButton's 48dp touch target.
                minHeight = buttonHeight,
                minTouchTargetWidth = ACCESSORY_TOUCH_WIDTH,
                contentPadding = buttonPadding,
            ) {
                ChuText(item.label, style = typography.label)
            }
        }
    }
}

@Composable
private fun FilesButton(
    onTafhKey: (() -> Unit)?,
    tafhKeyActive: Boolean,
    onOpenFiles: (() -> Unit)?,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    if (onTafhKey != null) {
        ChuButton(
            onClick = onTafhKey,
            variant = if (tafhKeyActive) ChuButtonVariant.Filled else ChuButtonVariant.Outlined,
            minHeight = buttonHeight,
            minTouchTargetWidth = ACCESSORY_TOUCH_WIDTH,
            contentPadding = buttonPadding,
        ) {
            ChuText(
                "⌘",
                style = typography.label,
                color = if (tafhKeyActive) colors.onAccent else colors.textPrimary,
            )
        }
    }
    if (onOpenFiles == null) return
    ChuButton(
        onClick = onOpenFiles,
        variant = ChuButtonVariant.Outlined,
        minHeight = buttonHeight,
        minTouchTargetWidth = ACCESSORY_TOUCH_WIDTH,
        contentPadding = buttonPadding,
    ) {
        ChuText(
            text = "",
            style =
                TextStyle(
                    fontFamily = ChuSymbolsFontFamily,
                    fontWeight = typography.label.fontWeight,
                    fontStyle = typography.label.fontStyle,
                    fontSize = 16.sp,
                    lineHeight = 16.sp,
                ),
            color = colors.textPrimary,
        )
    }
}

@Composable
private fun SettingsButton(
    onSettings: (() -> Unit)?,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
) {
    val typography = ChuTypography.current
    if (onSettings == null) return
    ChuButton(
        onClick = onSettings,
        variant = ChuButtonVariant.Outlined,
        minHeight = buttonHeight,
        minTouchTargetWidth = ACCESSORY_TOUCH_WIDTH,
        contentPadding = buttonPadding,
    ) {
        ChuText("⚙", style = typography.label)
    }
}

@Composable
private fun RepeatableAccessoryButton(
    item: AccessoryKeyItem,
    onAction: (AccessoryAction) -> Unit,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
) {
    val typography = ChuTypography.current
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
                // 48dp tall touch target; the drawn surface below stays buttonHeight
                // (30dp). Width is left to the key's own content: forcing 48dp wide
                // keys overflows the two-row bar and silently drops the trailing
                // Enter/Space/⌘ keys off the end of the FlowRow.
                .minimumInteractiveComponentSize(minWidth = 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        ChuButtonSurface(
            modifier = Modifier.height(buttonHeight),
            pressed = pressed,
            variant = ChuButtonVariant.Outlined,
            contentPadding = buttonPadding,
        ) {
            ChuText(item.label, style = typography.label)
        }
    }
}

@Composable
private fun ToggleButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    buttonHeight: Dp,
    contentPadding: PaddingValues,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val activeLabel = if (enabled) "• $label" else label
    ChuButton(
        onClick = onClick,
        // 30dp drawn key centered in ChuButton's 48dp touch target.
        minHeight = buttonHeight,
        minTouchTargetWidth = ACCESSORY_TOUCH_WIDTH,
        contentPadding = contentPadding,
        variant = if (enabled) ChuButtonVariant.Filled else ChuButtonVariant.Outlined,
    ) {
        ChuText(
            activeLabel,
            style = typography.label,
            color = if (enabled) colors.onAccent else colors.textSecondary,
        )
    }
}
