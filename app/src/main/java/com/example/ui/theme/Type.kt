package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * lineHeight 一律用 em（相對字級）而不是 sp（絕對值）。
 *
 * 為什麼：預設的 LocalTextStyle 是 bodyLarge。程式裡大量 `Text(x, fontSize = 12.sp)`
 * 只覆寫字級、沒覆寫 lineHeight，於是 12sp 的字被塞進 `24sp × fontScale` 的行高裡；
 * 字體倍率開到「特大」時，一個 12sp 的字會撐出 37sp 的行框，卡片被拉高、字跟字之間
 * 出現大片空白（復健題庫的中文＋譯文雙行最明顯）。
 *
 * 改成 em 之後行高跟著字級走，覆寫字級的地方自然拿到對應行高；名目字級下的比例
 * 與原本完全相同（例如 bodyLarge 1.5em × 16sp = 24sp），所以標準字級的版面不變。
 */
fun scaledTypography(scale: Float): Typography {
    val s = scale.coerceIn(0.8f, 1.8f)
    fun size(value: Int) = (value * s).sp

    return Typography(
      displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = size(36),
        lineHeight = 1.2222f.em,
        letterSpacing = 0.sp,
      ),
      headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = size(24),
        lineHeight = 1.3333f.em,
        letterSpacing = 0.sp,
      ),
      titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = size(22),
        lineHeight = 1.2727f.em,
        letterSpacing = 0.sp,
      ),
      titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = size(16),
        lineHeight = 1.5000f.em,
        letterSpacing = 0.sp,
      ),
      bodyLarge =
        TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = size(16),
        lineHeight = 1.5000f.em,
        letterSpacing = 0.sp,
      ),
      bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = size(14),
        lineHeight = 1.4286f.em,
        letterSpacing = 0.sp
      ),
      labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = size(14),
        lineHeight = 1.4286f.em,
        letterSpacing = 0.sp,
      ),
      labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = size(12),
        lineHeight = 1.3333f.em,
        letterSpacing = 0.sp,
      ),
    )
}

// Set of Material typography styles to start with
val Typography = scaledTypography(1.0f)
