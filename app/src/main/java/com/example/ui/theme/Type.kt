package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

fun scaledTypography(scale: Float): Typography {
    val s = scale.coerceIn(0.8f, 1.8f)
    fun size(value: Int) = (value * s).sp

    return Typography(
      displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = size(36),
        lineHeight = size(44),
        letterSpacing = 0.sp,
      ),
      headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = size(24),
        lineHeight = size(32),
        letterSpacing = 0.sp,
      ),
      titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = size(22),
        lineHeight = size(28),
        letterSpacing = 0.sp,
      ),
      titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
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
        fontWeight = FontWeight.Medium,
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
