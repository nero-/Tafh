package dev.nero.tafh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nero.tafh.ui.theme.ChuTypography

@Composable
fun TuiBadge(text: String, color: Color) {
    val typography = ChuTypography.current
    Box(
        modifier =
            Modifier.background(color.copy(alpha = 0.12f))
                .border(1.dp, color.copy(alpha = 0.6f))
                .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        ChuText(text, style = typography.labelSmall, color = color)
    }
}
