package com.speakmylife.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * lineHeight 一律用 sp。
 *
 * **不要改成 em。** 試過，會 crash：Material3 的 OutlinedTextField 在動浮動標籤時
 * 會 lerp(bodyLarge, bodySmall)，而 lerp 不能混用 Em 與 Sp
 * （IllegalArgumentException: Cannot perform operation for Em and Sp）。
 * 這裡沒有覆寫 bodySmall，它會拿 Material 預設的 sp 行高，於是每一個帶 label 的
 * 輸入框一顯示就閃退——全 App 有 12 個，含 API 金鑰管理與設定頁。
 *
 * 代價是 `Text(x, fontSize = 12.sp)` 這種只覆寫字級的寫法會沿用 bodyLarge 的
 * 絕對行高，字體倍率放大時行框會比字大很多。需要緊湊排版的地方請在該處
 * 明確給 lineHeight（例如復健題庫的字卡），不要動這張表。
 */
fun scaledTypography(scale: Float): Typography {
    val s = scale.coerceIn(0.8f, 1.8f)
    fun size(value: Int) = (value * s).sp

    // 字重層級（拾語者品牌）：標題 Bold → 小標 SemiBold → 內文 Normal → 按鈕 SemiBold。
    // 中文字在 Medium 跟 Normal 之間幾乎看不出差別，所以層級要拉開到 SemiBold／Bold
    // 才分得出「這是標題」——對閱讀吃力的失語症使用者，層級就是導覽。

    return Typography(
      displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = size(36),
        lineHeight = size(44),
        letterSpacing = 0.sp,
      ),
      headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = size(24),
        lineHeight = size(32),
        letterSpacing = 0.sp,
      ),
      titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = size(22),
        lineHeight = size(28),
        letterSpacing = 0.sp,
      ),
      titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = size(16),
        lineHeight = size(24),
        letterSpacing = 0.sp,
      ),
      bodyLarge =
        TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = size(16),
        lineHeight = size(24),
        letterSpacing = 0.sp,
      ),
      bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = size(14),
        lineHeight = size(20),
        letterSpacing = 0.sp
      ),
      labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = size(14),
        lineHeight = size(20),
        letterSpacing = 0.sp,
      ),
      labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = size(12),
        lineHeight = size(16),
        letterSpacing = 0.sp,
      ),
    )
}

// Set of Material typography styles to start with
val Typography = scaledTypography(1.0f)
