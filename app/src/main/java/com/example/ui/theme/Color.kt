package com.example.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// VoiceWeaver 共用配色（三個 Screen 原本各自重複定義，集中至此）
val VWDarkBg       = Color(0xFF0F172A)
val VWSurface      = Color(0xFF1E293B)
val VWNeonCyan     = Color(0xFF06B6D4)
val VWNeonGreen    = Color(0xFF10B981)
val VWAuraAmber    = Color(0xFFF59E0B)
val VWDangerRose   = Color(0xFFF43F5E)
val VWTextLight    = Color(0xFFF1F5F9)
val VWTextMuted    = Color(0xFF94A3B8)

// ─── 拾語者 Speak My Life 品牌色 ───
// 取自網頁版 logo：深綠方塊（承載）＋白色對話泡泡（說出口）＋暖黃絲線（把碎詞織成句子）。
// 只放「品牌原色」；介面要用的明暗變體在 AppPalette.kt 的 BrandDay / BrandNight 推導，
// 因為原色直接拿來當文字色在淺底上對比不夠（暖黃尤其），要依深淺各調一次。
val BrandForest     = Color(0xFF2F6F5E)   // logo 深綠
val BrandForestDeep = Color(0xFF1E4A3F)   // 深綠壓暗：淺色主題的強調文字／按壓態
val BrandThread     = Color(0xFFFFD27A)   // logo 暖黃絲線
val BrandThreadDeep = Color(0xFF9A6200)   // 暖黃壓暗：淺底上仍有 4.5:1 的「黃」
val BrandPaper      = Color(0xFFF7F1E6)   // 淺色底：暖米紙色（同網頁 --bg），比純白不刺眼
val BrandCard       = Color(0xFFFFFDF8)   // 淺色卡片：暖白（同網頁 --card）
val BrandForestDark = Color(0xFF163A30)   // 問候卡漸層的深端（同網頁 --brand-900）
val BrandGold       = Color(0xFFF2B84B)   // 填色用暖黃（同網頁 --gold-500）：主要 CTA 的底色
val BrandGoldInk    = Color(0xFF3B2A06)   // 暖黃按鈕上的字：7.7:1
val BrandNight      = Color(0xFF0D1512)   // 深色底：深綠黑（同網頁深色 --bg），不是死黑
