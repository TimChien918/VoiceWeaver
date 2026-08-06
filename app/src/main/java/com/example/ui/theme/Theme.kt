package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
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
    highContrast: Boolean = false,
    content: @Composable () -> Unit,
) {
    val palette = if (highContrast) HighContrastPalette else paletteOf(theme)
    // 簡約風是「淺色」風，其餘 3 種為深色（背景色決定）；高對比一律深色
    val isLight = !highContrast && (theme == AppTheme.Minimal || theme == AppTheme.Cute)

    // 字級改由 LocalDensity 統一放大，不再只縮放 typography。
    //
    // 原因：這個 App 幾乎所有文字都是寫死的 `fontSize = 14.sp` 之類，
    // 沒有走 MaterialTheme.typography，所以「字體大小」拉到 1.6x 時
    // 只有極少數用到 typography 的地方會變大，其餘完全沒反應——
    // 對視力弱的使用者來說等於這個設定是壞的。
    //
    // 改成調整 density 的 fontScale 之後，任何 `.sp` 都會跟著放大，
    // 包含既有的每一處寫死值，不必逐檔改。
    // typography 這裡就不能再乘一次，否則會變成平方放大。
    val base = LocalDensity.current
    val scaled = Density(
        density = base.density,
        fontScale = base.fontScale * fontScale.coerceIn(0.8f, 1.8f),
    )

    CompositionLocalProvider(
        LocalAppPalette provides palette,
        LocalDensity provides scaled,
    ) {
        MaterialTheme(
            colorScheme = materialSchemeFor(palette, isLight),
            typography = scaledTypography(1f),
            content = content
        )
    }
}
