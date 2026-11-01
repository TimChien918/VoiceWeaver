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

/**
 * 高對比（無障礙）：純黑底 + 最高彩度文字（黃/白/亮綠/亮紅），給視力弱、低視能使用者。
 * 文字一律飽和高亮，accent 用高對比黃，錯誤用亮紅。
 */
val HighContrastPalette = AppPalette(
    darkBg     = Color(0xFF000000),
    surface    = Color(0xFF000000),
    neonCyan   = Color(0xFFFFE600),   // 高對比黃（primary，黑底上最醒目）
    neonGreen  = Color(0xFF00FF66),   // 亮綠
    auraAmber  = Color(0xFFFFB000),   // 琥珀
    dangerRose = Color(0xFFFF3B30),   // 亮紅
    textLight  = Color(0xFFFFFFFF),
    textMuted  = Color(0xFFE0E0E0)    // 高對比下次要文字也接近白
)

/**
 * 拾語者品牌風（淺色，預設）：暖米紙底 + 暖白卡片 + logo 深綠為主色、暖黃為 AI／提示色。
 * 色值與網頁版 style.css 的 :root 對齊（--bg #f7f1e6、--card #fffdf8、--accent #2f6f5e），
 * 兩邊看起來要是同一個產品。
 *
 * 每個前景色都對底色與卡片驗過 ≥ 4.5:1（WCAG AA 一般文字）——使用者多是中高齡、
 * 視力退化，品牌色再好看，讀不到就沒有意義。因此有三處刻意比網頁「深一階」：
 *  · 成功綠用 #237A55（網頁 #2a9a6c 在米底上只有 3.2:1，App 裡它常當小字顏色）；
 *  · 求救紅用 #C0392B（網頁 #d33a2f 上的米色字只有 4.2:1，App 按鈕字是 onPrimary＝底色）；
 *  · 暖黃當「文字」時用壓暗的 [BrandThreadDeep]；填色用的暖黃 [BrandGold] 只用在
 *    問候卡的「開始說話」這種大按鈕上，上面配深棕字。
 */
val BrandDayPalette = AppPalette(
    darkBg     = BrandPaper,
    surface    = BrandCard,
    neonCyan   = BrandForest,         // 主色：logo 深綠
    neonGreen  = Color(0xFF237A55),   // 成功：葉綠
    auraAmber  = BrandThreadDeep,     // AI／推薦：壓暗的暖黃
    dangerRose = Color(0xFFC0392B),
    textLight  = Color(0xFF1C2925),   // 帶綠的墨色，比純黑柔和
    textMuted  = Color(0xFF58665F)
)

/**
 * 拾語者品牌風（深色）：深綠黑底（不是純黑——純黑配白字在老花眼裡會暈）+ 玉綠主色、暖黃。
 * 與網頁版深色模式同色（--bg #0d1512、--card #15201c、--accent-ink #7fcdb3）。
 * 系統切到深色模式時自動換上，見 [MyApplicationTheme]。
 */
val BrandNightPalette = AppPalette(
    darkBg     = BrandNight,
    surface    = Color(0xFF15201C),
    neonCyan   = Color(0xFF7FCDB3),   // 深綠提亮成玉綠：深底上約 10:1
    neonGreen  = Color(0xFF3CC08A),
    auraAmber  = Color(0xFFF2C063),
    dangerRose = Color(0xFFFF6B5E),   // 比網頁 #e5483c 亮一階：深色卡片上才有 4.5:1
    textLight  = Color(0xFFE8EFEB),
    textMuted  = Color(0xFF9FB0A8)
)

/** 科技風（深色）：近黑底 + iOS 系統色（藍/綠/橙/紅），沉穩專業。 */
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
val LocalAppPalette = compositionLocalOf { BrandDayPalette }

/** 簡寫存取：`AppColors.neonCyan` 等同 `LocalAppPalette.current.neonCyan` */
object AppColors {
    val palette: AppPalette
        @Composable @ReadOnlyComposable get() = LocalAppPalette.current
}
