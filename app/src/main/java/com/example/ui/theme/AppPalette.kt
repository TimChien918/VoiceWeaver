package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * App 主題配色（8 槽位，所有 Screen 統一使用）。
 *
 * 切換主題時所有 Screen 的色彩同時換掉——透過 [LocalAppPalette] CompositionLocal 提供。
 */
data class AppPalette(
    val darkBg: Color,
    val surface: Color,
    val neonCyan: Color,    // primary accent（按鈕/title/icon）
    val neonGreen: Color,   // success（已就緒/正向回饋）
    val auraAmber: Color,   // warning/AI（推薦/進度）
    val dangerRose: Color,  // error/destructive
    val textLight: Color,   // 主要文字
    val textMuted: Color    // 次要文字/說明
)

/** 科技風（深色，預設）：近黑底 + iOS 系統色（藍/綠/橙/紅），沉穩專業。 */
val TechPalette = AppPalette(
    darkBg     = Color(0xFF000000),
    surface    = Color(0xFF1C1C1E),
    neonCyan   = Color(0xFF0A84FF),   // iOS 系統藍（深色）
    neonGreen  = Color(0xFF30D158),   // iOS 系統綠
    auraAmber  = Color(0xFFFF9F0A),   // iOS 系統橙
    dangerRose = Color(0xFFFF453A),   // iOS 系統紅
    textLight  = Color(0xFFFFFFFF),
    textMuted  = Color(0xFF98989F)
)

/** 可愛風（淺色，兒童）：柔和奶油底 + 玫瑰粉 accent，溫暖不刺眼。 */
val CutePalette = AppPalette(
    darkBg     = Color(0xFFFFF5F7),   // 淡粉奶油
    surface    = Color(0xFFFFFFFF),
    neonCyan   = Color(0xFFFF5C8A),   // 玫瑰粉（accent）
    neonGreen  = Color(0xFF42C98E),   // 薄荷綠
    auraAmber  = Color(0xFFFFB340),   // 蜜桃橙
    dangerRose = Color(0xFFFF3B30),
    textLight  = Color(0xFF4A2E3A),   // 暖深莓
    textMuted  = Color(0xFFA68A95)    // 灰玫
)

/** 動漫風（深色，青少年）：深紫黑底 + 櫻粉 accent、薄荷與星黃，活潑但不過曝。 */
val AnimePalette = AppPalette(
    darkBg     = Color(0xFF0D0B1A),   // 深紫黑
    surface    = Color(0xFF1C1830),   // 紫影
    neonCyan   = Color(0xFFFF6FB5),   // 櫻粉（主 accent）
    neonGreen  = Color(0xFF3DE0A0),   // 螢光薄荷
    auraAmber  = Color(0xFFFFD60A),   // 星黃
    dangerRose = Color(0xFFFF453A),
    textLight  = Color(0xFFF6F2FF),   // 柔白
    textMuted  = Color(0xFFB6A6D6)    // 淡紫
)

/** 簡約風（淺色）：iOS 淺色系統色（#F2F2F7 底 + 系統藍），高對比、低干擾。 */
val MinimalPalette = AppPalette(
    darkBg     = Color(0xFFF2F2F7),   // systemGroupedBackground light
    surface    = Color(0xFFFFFFFF),
    neonCyan   = Color(0xFF007AFF),   // iOS 系統藍
    neonGreen  = Color(0xFF34C759),
    auraAmber  = Color(0xFFFF9500),
    dangerRose = Color(0xFFFF3B30),
    textLight  = Color(0xFF1C1C1E),
    textMuted  = Color(0xFF8E8E93)
)

/**
 * iOS 高對比可讀色盤（蘋果深色設計）：給「整頁設定面板」用，確保文字在深底上清楚可讀。
 * 透過 CompositionLocalProvider 覆蓋當前主題，面板內所有元件自動套用。
 */
val IosReadablePalette = AppPalette(
    darkBg     = Color(0xFF000000),
    surface    = Color(0xFF1C1C1E),
    neonCyan   = Color(0xFF0A84FF),   // 系統藍
    neonGreen  = Color(0xFF30D158),   // 系統綠
    auraAmber  = Color(0xFFFF9F0A),   // 系統橙
    dangerRose = Color(0xFFFF453A),   // 系統紅
    textLight  = Color(0xFFFFFFFF),   // 純白主文字
    textMuted  = Color(0xFFAEAEB2)    // 淺灰次要文字（深底可讀）
)

/** iOS 高對比可讀色盤——淺色版（給淺色主題用，白底黑字）。 */
val IosReadableLightPalette = AppPalette(
    darkBg     = Color(0xFFF2F2F7),   // systemGroupedBackground light
    surface    = Color(0xFFFFFFFF),   // 白卡片
    neonCyan   = Color(0xFF007AFF),   // 系統藍
    neonGreen  = Color(0xFF34C759),   // 系統綠
    auraAmber  = Color(0xFFFF9500),   // 系統橙
    dangerRose = Color(0xFFFF3B30),   // 系統紅
    textLight  = Color(0xFF000000),   // 黑色主文字
    textMuted  = Color(0xFF6C6C70)    // 灰色次要文字
)

/** 依當前主題深淺，回傳對應的 iOS 可讀色盤。 */
val AppPalette.iosReadable: AppPalette
    get() = if (isLight) IosReadableLightPalette else IosReadablePalette

// ─── 設計師層：從 8 色衍生出漸層/光暈/分隔線，讓畫面有深度而不只是平塗 ───
// 不改 AppPalette 結構（避免波及所有 Screen），用擴充屬性即可。

/** 判斷是否為淺色主題（決定漸層往亮或往暗收）。以背景亮度估算。 */
val AppPalette.isLight: Boolean
    get() = (darkBg.red * 0.299f + darkBg.green * 0.587f + darkBg.blue * 0.114f) > 0.5f

/**
 * 主畫面背景漸層：深色主題由上方微帶 accent 色暈往下沉到純底；
 * 淺色主題則維持柔和奶白，避免刺眼。比單色平塗更有空間層次。
 */
val AppPalette.backgroundGradient: Brush
    get() {
        val top = if (isLight) lerp(darkBg, neonCyan, 0.04f)
                  else lerp(darkBg, neonCyan, 0.10f)
        val bottom = if (isLight) darkBg else lerp(darkBg, Color.Black, 0.25f)
        return Brush.verticalGradient(listOf(top, darkBg, bottom))
    }

/** 主要 CTA / 強調卡片用的對角漸層（accent → 偏綠），按鈕不再是死板單色。 */
val AppPalette.accentGradient: Brush
    get() = Brush.linearGradient(listOf(neonCyan, lerp(neonCyan, neonGreen, 0.55f)))

/** 卡片表面的細緻漸層：表面色 → 微亮，模擬上方受光，增加立體感。 */
val AppPalette.surfaceGradient: Brush
    get() = Brush.verticalGradient(
        listOf(lerp(surface, if (isLight) Color.White else neonCyan, 0.06f), surface)
    )

/** 分隔線 / 邊框統一色（accent 低透明），取代到處手寫 alpha。 */
val AppPalette.hairline: Color
    get() = neonCyan.copy(alpha = 0.22f)

/** CompositionLocal：所有 Screen 透過 `LocalAppPalette.current.xxx` 讀取當前主題色 */
val LocalAppPalette = compositionLocalOf { TechPalette }

/** 簡寫存取：`AppColors.neonCyan` 等同 `LocalAppPalette.current.neonCyan` */
object AppColors {
    val palette: AppPalette
        @Composable @ReadOnlyComposable get() = LocalAppPalette.current
}
