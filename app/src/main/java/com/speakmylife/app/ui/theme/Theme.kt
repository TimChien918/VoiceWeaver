package com.speakmylife.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.min
import com.speakmylife.app.data.AppTheme
import com.speakmylife.app.data.MAX_FONT_SCALE
import com.speakmylife.app.data.MIN_FONT_SCALE

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

private fun materialSchemeFor(palette: AppPalette, isLight: Boolean): androidx.compose.material3.ColorScheme {
    // 沒有指定的角色 Material 會填基準紫色（FilterChip 選中、Switch 軌道、選單底…），
    // 在綠色品牌畫面上冒出一塊紫非常突兀。從 8 色推導出容器色，讓每種風格都自洽。
    val container = lerp(palette.surface, palette.neonCyan, if (isLight) 0.14f else 0.24f)
    val variant = lerp(palette.surface, palette.neonCyan, if (isLight) 0.06f else 0.10f)
    return if (isLight) lightColorScheme(
        primary    = palette.neonCyan,
        secondary  = palette.neonGreen,
        tertiary   = palette.auraAmber,
        background = palette.darkBg,
        surface    = palette.surface,
        onPrimary  = palette.darkBg,
        onSurface  = palette.textLight,
        onBackground = palette.textLight,
        error      = palette.dangerRose,
        primaryContainer = container,
        onPrimaryContainer = palette.textLight,
        secondaryContainer = container,
        onSecondaryContainer = palette.textLight,
        surfaceVariant = variant,
        onSurfaceVariant = palette.textMuted,
        outline = palette.textMuted
    ) else darkColorScheme(
        primary    = palette.neonCyan,
        secondary  = palette.neonGreen,
        tertiary   = palette.auraAmber,
        background = palette.darkBg,
        surface    = palette.surface,
        onPrimary  = palette.darkBg,
        onSurface  = palette.textLight,
        onBackground = palette.textLight,
        error      = palette.dangerRose,
        primaryContainer = container,
        onPrimaryContainer = palette.textLight,
        secondaryContainer = container,
        onSecondaryContainer = palette.textLight,
        surfaceVariant = variant,
        onSurfaceVariant = palette.textMuted,
        outline = palette.textMuted
    )
}

/**
 * 全 App 共用的圓角階層。手寫 `RoundedCornerShape(16.dp)` 的卡片很多，
 * 這裡讓 Material 元件（Card、Dialog、Menu…）用同一套數字，畫面才不會一張圓一張方。
 * 偏大的圓角是刻意的：對手抖、偏癱的使用者，柔和的大區塊比銳角細框更像「可以按」。
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small      = RoundedCornerShape(10.dp),
    // 卡片 24dp（同網頁 --r-lg），對話框 32dp（同 --r-xl）
    medium     = RoundedCornerShape(24.dp),
    large      = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** 把 [AppTheme] 設定值對應到具體 [AppPalette]（品牌風取淺色版；要跟隨系統深淺用 [MyApplicationTheme]）。 */
fun paletteOf(theme: AppTheme): AppPalette = when (theme) {
    AppTheme.Brand   -> BrandDayPalette
    AppTheme.Tech    -> TechPalette
    AppTheme.Cute    -> CutePalette
    AppTheme.Anime   -> AnimePalette
    AppTheme.Minimal -> MinimalPalette
}

@Composable
fun MyApplicationTheme(
    theme: AppTheme = AppTheme.Brand,
    fontScale: Float = 1.0f,
    highContrast: Boolean = false,
    content: @Composable () -> Unit,
) {
    // 品牌風是唯一跟隨系統深淺色的風格：其他四種的深淺本來就是風格的一部分
    // （科技風就是黑底），但品牌風要在白天、夜裡都舒服——夜裡起床的患者
    // 被一整片紙白照到會刺眼。高對比永遠優先，蓋過任何風格與系統設定。
    val systemDark = isSystemInDarkTheme()
    val palette = when {
        highContrast -> HighContrastPalette
        theme == AppTheme.Brand -> if (systemDark) BrandNightPalette else BrandDayPalette
        else -> paletteOf(theme)
    }
    // 深淺由背景亮度決定（可愛風、簡約風、品牌白天版為淺色）；高對比一律深色
    val isLight = !highContrast && palette.isLight

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
            shapes = AppShapes,
            content = content
        )
    }
}
