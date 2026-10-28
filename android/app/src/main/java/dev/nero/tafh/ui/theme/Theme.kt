package dev.nero.tafh.ui.theme

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun ChuTheme(themeName: String? = null, fontName: String? = null, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val palette =
        themeName?.let { GhosttyThemeRegistry.getTheme(context, it) }?.toChuColorPalette()
            ?: ChuDarkColors

    val fontOption = remember(fontName) { ChuFontOption.fromId(fontName) }
    val typography = remember(fontOption) { chuTypographyFor(fontOption) }

    // Drive status- and navigation-bar icon brightness from the active theme's
    // background. Without this, the system clock/wifi/battery icons stay white
    // and become unreadable on every light theme.
    val view = LocalView.current
    if (!view.isInEditMode) {
        val isLightBackground = palette.background.luminance() >= 0.5f
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = isLightBackground
            controller.isAppearanceLightNavigationBars = isLightBackground
        }
    }

    CompositionLocalProvider(
        LocalChuColors provides palette,
        LocalChuFont provides fontOption,
        LocalChuTypography provides typography,
        content = content,
    )
}

private fun androidx.compose.ui.graphics.Color.luminance(): Float =
    0.299f * red + 0.587f * green + 0.114f * blue
