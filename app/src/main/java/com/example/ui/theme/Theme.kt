package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.example.data.AppTheme

private fun materialSchemeFor(palette: AppPalette, isLight: Boolean) =
    if (isLight) lightColorScheme(
        primary    = palette.neonCyan,
        secondary  = palette.neonGreen,
        tertiary   = palette.auraAmber,
        background = palette.darkBg,
        surface    = palette.surface,
        onPrimary  = palette.darkBg,
        onSurface  = palette.textLight,
        onBackground = palette.textLight,
        error      = palette.dangerRose
    ) else darkColorScheme(
        primary    = palette.neonCyan,
        secondary  = palette.neonGreen,
        tertiary   = palette.auraAmber,
        background = palette.darkBg,
        surface    = palette.surface,
        onPrimary  = palette.darkBg,
        onSurface  = palette.textLight,
        onBackground = palette.textLight,
        error      = palette.dangerRose
    )

/** 把 [AppTheme] 設定值對應到具體 [AppPalette] */
fun paletteOf(theme: AppTheme): AppPalette = when (theme) {
    AppTheme.Tech    -> TechPalette
    AppTheme.Cute    -> CutePalette
    AppTheme.Anime   -> AnimePalette
    AppTheme.Minimal -> MinimalPalette
}

@Composable
fun MyApplicationTheme(
    theme: AppTheme = AppTheme.Tech,
    fontScale: Float = 1.0f,
    content: @Composable () -> Unit,
) {
    val palette = paletteOf(theme)
    // 簡約風是「淺色」風，其餘 3 種為深色（背景色決定）
    val isLight = theme == AppTheme.Minimal || theme == AppTheme.Cute
    CompositionLocalProvider(LocalAppPalette provides palette) {
        MaterialTheme(
            colorScheme = materialSchemeFor(palette, isLight),
            typography = scaledTypography(fontScale),
            content = content
        )
    }
}
