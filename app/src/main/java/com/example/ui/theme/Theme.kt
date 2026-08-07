package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.min
import com.example.data.AppTheme
import com.example.data.MAX_FONT_SCALE
import com.example.data.MIN_FONT_SCALE

/**
 * 字級一律「線性」放大：選 2 倍就是 2 倍。
 *
 * Android 14 起 `.sp` 預設走**非線性字級**，小字放大、大字幾乎不動。
 * 實測（ProbeFontScaleTest）在倍率 1.8 時：
 *
 *   10sp → x1.80    16sp → x1.63    32sp → x1.15    60sp → x1.05    100sp → x1.00
 *
 * 平台的用意是「大字本來就看得到，不需要再放大」，但這個 App 剛好相反——
 * 重度模式的圖卡 emoji 是 84sp、標籤 30sp 以上，全部落在被壓成 1.0 倍的那一段。
 * 使用者把字級拉到最大，畫面上完全沒有變化，等於這個設定是壞的。
 * （倍率超過 2.0 之後平台才恢復線性，而滑桿上限原本就是 1.8，
 *   剛好整條滑桿都卡在壓縮最嚴重的區間裡。）
 *
 * 覆寫 [toDp] 把它改回單純的乘法。所有 `.sp` 都經由 `TextUnit.toPx()` →
 * `toDp()` 換算，覆寫這一個點就能一次涵蓋整個 App（含 Compose 內部的文字排版）。
 */
@Immutable
private class LinearFontDensity(
    override val density: Float,
    override val fontScale: Float,
) : Density {
    override fun TextUnit.toDp(): Dp = Dp(value * fontScale)
    override fun Dp.toSp(): TextUnit = (value / fontScale).sp
}

/** 測試用的建構入口——換算規則要能被驗證，不能只靠肉眼看畫面。 */
fun linearFontDensity(density: Float, fontScale: Float): Density =
    LinearFontDensity(density, fontScale)

/**
 * 使用者設定的字級倍率，**用我們自己的 CompositionLocal 傳**。
 *
 * 為什麼不直接讀 `LocalDensity`：Dialog／Popup 開在自己的 Window 上，
 * 平台會在那個 Window 重新提供 `LocalDensity`（連同 `LocalContext`、`LocalConfiguration`），
 * 外層設定的字級就這樣被蓋回系統預設——症狀是「設定內頁的字完全沒變大」。
 *
 * 這個 local 平台不會動，所以進得了 Dialog。[ReapplyAppLocale] 會讀它把密度重掛回去。
 */
val LocalAppFontScale = staticCompositionLocalOf { 1f }

/**
 * 給「固定高度的標題列」用的字級：照樣跟著設定放大，但放大幅度有上限。
 *
 * Material 的 `TopAppBar` 高度寫死 64dp。22sp 的標題在 3 倍字級下要 66dp，
 * 直接被切掉上下半截——標題變成一團看不懂的黑塊，比小字還難讀。
 *
 * 標題列是「這是哪一頁」的導覽資訊，不是使用者要讀的內容；內容該放多大就放多大，
 * 標題到某個程度就夠了。所以這裡只讓它長到 [maxScale] 倍為止。
 * **不要拿這個去套內文**，那樣就變回「字級調了沒反應」。
 */
@Composable
fun chromeSp(base: Float, maxScale: Float = 1.5f): TextUnit {
    val fs = LocalDensity.current.fontScale.coerceAtLeast(0.1f)
    // 算回 sp：畫出來的實際大小 = 回傳值 × fs，希望它等於 base × min(fs, maxScale)
    return (base * min(fs, maxScale) / fs).sp
}

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
    val appScale = fontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
    val scaled = LinearFontDensity(
        density = base.density,
        fontScale = base.fontScale * appScale,
    )

    CompositionLocalProvider(
        LocalAppPalette provides palette,
        LocalDensity provides scaled,
        // Dialog 進不去 LocalDensity，但進得去這個——見 [LocalAppFontScale]。
        LocalAppFontScale provides appScale,
    ) {
        MaterialTheme(
            colorScheme = materialSchemeFor(palette, isLight),
            typography = scaledTypography(1f),
            content = content
        )
    }
}
