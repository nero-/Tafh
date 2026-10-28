package com.jossephus.chuchu.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.ui.theme.ChuColors

private val TrackWidth = 36.dp
private val TrackHeight = 18.dp

/**
 * Compact terminal-styled switch.
 *
 * The drawn track stays 36x18dp, but the switch occupies (and responds across) a 48dp touch target
 * via [minimumInteractiveComponentSize]. [contentDescription] labels the switch for TalkBack; when
 * null only its role and on/off [stateDescription] are exposed.
 */
@Composable
fun ChuSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val colors = ChuColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val trackColor = if (checked) colors.accent else colors.surface
    val thumbColor = if (checked) colors.onAccent else colors.textPrimary
    val thumbOffset by animateDpAsState(if (checked) 17.dp else 2.dp, label = "chu-switch")

    Box(
        modifier =
            modifier
                .semantics {
                    role = Role.Switch
                    stateDescription = if (checked) "on" else "off"
                    if (contentDescription != null) {
                        this.contentDescription = contentDescription
                    }
                }
                .clickable(
                    enabled = enabled,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { onCheckedChange(!checked) },
                )
                // 48dp interactive area; the 36x18dp track is centered inside it.
                .minimumInteractiveComponentSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier.size(width = TrackWidth, height = TrackHeight)
                    .background(trackColor)
                    .border(1.dp, colors.border)
                    .alpha(if (enabled) 1f else 0.5f)
        ) {
            Box(
                modifier =
                    Modifier.offset(x = thumbOffset, y = 1.dp).size(16.dp).background(thumbColor)
            )
        }
    }
}
