package dev.nero.tafh.ui.screens.Terminal

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nero.tafh.ui.components.ChuText
import dev.nero.tafh.ui.theme.ChuColors
import dev.nero.tafh.ui.theme.ChuTypography

/** Hint chip showing a key binding and its action, used in overlay bottom bars. */
@Composable
fun PaletteHint(key: String, label: String) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier =
                Modifier.border(1.dp, colors.border).padding(horizontal = 5.dp, vertical = 1.dp)
        ) {
            ChuText(key, style = typography.labelSmall, color = colors.textSecondary)
        }
        ChuText(label, style = typography.labelSmall, color = colors.textMuted)
    }
}
