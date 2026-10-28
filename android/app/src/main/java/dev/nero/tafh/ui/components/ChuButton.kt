package dev.nero.tafh.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import dev.nero.tafh.ui.theme.ChuColors
import dev.nero.tafh.ui.theme.ChuTypography

/** Android's minimum recommended touch target: 48x48dp. */
internal val MinimumTouchTarget: Dp = 48.dp

/**
 * Reserves at least [minWidth] x [minHeight] and keeps the drawn content centered inside it.
 *
 * Equivalent to Material3's `Modifier.minimumInteractiveComponentSize` (not a dependency here): the
 * terminal chrome stays visually compact (30dp accessory keys, 32dp tabs, 36dp buttons) while the
 * interactive area reaches the 48dp accessibility minimum. Place it *after* the pointer-input and
 * semantics modifiers so their measured bounds cover the expanded area, and *before* the drawing
 * modifiers so backgrounds and borders keep their compact size.
 */
internal fun Modifier.minimumInteractiveComponentSize(
    minWidth: Dp = MinimumTouchTarget,
    minHeight: Dp = MinimumTouchTarget,
): Modifier = this.then(MinimumInteractiveComponentSizeElement(minWidth, minHeight))

private data class MinimumInteractiveComponentSizeElement(val minWidth: Dp, val minHeight: Dp) :
    ModifierNodeElement<MinimumInteractiveComponentSizeNode>() {
    override fun create() = MinimumInteractiveComponentSizeNode(minWidth, minHeight)

    override fun update(node: MinimumInteractiveComponentSizeNode) {
        node.minWidth = minWidth
        node.minHeight = minHeight
    }
}

private class MinimumInteractiveComponentSizeNode(var minWidth: Dp, var minHeight: Dp) :
    Modifier.Node(), LayoutModifierNode {
    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        // Clamped by the incoming constraints so a tightly-sized parent never overflows.
        val width = maxOf(placeable.width, constraints.constrainWidth(minWidth.roundToPx()))
        val height = maxOf(placeable.height, constraints.constrainHeight(minHeight.roundToPx()))
        return layout(width, height) {
            placeable.place(x = (width - placeable.width) / 2, y = (height - placeable.height) / 2)
        }
    }
}

enum class ChuButtonVariant {
    Filled,
    Outlined,
    Ghost,
}

/**
 * Reusable non-clickable button surface: background, border, shape, press alpha, min-height, and
 * content padding. Used by [ChuButton] for clickable buttons and by
 * [RepeatableAccessoryButton][dev.nero.tafh.ui.terminal.RepeatableAccessoryButton] for repeat-touch
 * buttons so both share consistent visual styling.
 */
@Composable
fun ChuButtonSurface(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pressed: Boolean = false,
    variant: ChuButtonVariant = ChuButtonVariant.Filled,
    backgroundColor: Color? = null,
    borderColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    minHeight: Dp = 36.dp,
    content: @Composable () -> Unit,
) {
    val colors = ChuColors.current
    val shape = RectangleShape

    val background: Color =
        when {
            !enabled -> colors.disabledSurface
            variant == ChuButtonVariant.Filled -> colors.accent
            backgroundColor != null -> backgroundColor
            else -> Color.Transparent
        }

    val effectiveBorderColor: Color = borderColor ?: colors.border

    val border: BorderStroke? =
        when {
            !enabled && variant != ChuButtonVariant.Ghost -> BorderStroke(1.dp, colors.border)
            variant == ChuButtonVariant.Outlined -> BorderStroke(1.dp, effectiveBorderColor)
            else -> null
        }

    Box(
        modifier =
            modifier
                .clip(shape)
                .background(background, shape)
                .then(if (border != null) Modifier.border(border, shape) else Modifier)
                .defaultMinSize(minHeight = minHeight)
                .alpha(if (pressed && enabled) 0.7f else 1f)
                .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
fun ChuButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: ChuButtonVariant = ChuButtonVariant.Filled,
    bracketed: Boolean = false,
    borderColor: Color? = null,
    backgroundColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    minHeight: Dp = 36.dp,
    /**
     * Width of the invisible touch target. Height always reaches the 48dp minimum; the width is
     * configurable because dense rows (the accessory bar packs 13 keys into two lines) cannot
     * afford 48dp per key without overflowing their parent and silently losing the last keys.
     */
    minTouchTargetWidth: Dp = MinimumTouchTarget,
    testTag: String? = null,
    contentDescription: String? = null,
    content: @Composable () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val bracketColor: Color =
        when {
            !enabled -> colors.disabledText
            variant == ChuButtonVariant.Filled -> colors.onAccent
            borderColor != null -> borderColor
            else -> colors.accent
        }

    val semanticsModifier =
        Modifier.semantics {
            if (testTag != null) {
                this.testTag = testTag
            }
            if (contentDescription != null) {
                this.contentDescription = contentDescription
            }
        }

    Box(
        modifier =
            modifier
                .then(semanticsModifier)
                .clickable(
                    enabled = enabled,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                // 48dp touch target; the drawn surface below stays [minHeight] (36dp by default)
                // and is centered inside this invisible padding.
                .minimumInteractiveComponentSize(minWidth = minTouchTargetWidth),
        // Callers that size the button (fillMaxWidth, fixed height) must keep sizing the drawn
        // surface, so forward their minimum constraints instead of letting it wrap.
        propagateMinConstraints = true,
        contentAlignment = Alignment.Center,
    ) {
        ChuButtonSurface(
            enabled = enabled,
            pressed = pressed,
            variant = variant,
            backgroundColor = backgroundColor,
            borderColor = borderColor,
            contentPadding = contentPadding,
            minHeight = minHeight,
        ) {
            if (bracketed) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ChuText("[", style = typography.label, color = bracketColor)
                    content()
                    ChuText("]", style = typography.label, color = bracketColor)
                }
            } else {
                content()
            }
        }
    }
}
