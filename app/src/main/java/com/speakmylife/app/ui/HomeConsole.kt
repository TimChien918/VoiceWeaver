package com.speakmylife.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.speakmylife.app.ui.theme.AppPalette
import com.speakmylife.app.ui.theme.BrandForest
import com.speakmylife.app.ui.theme.BrandForestDark
import com.speakmylife.app.ui.theme.BrandGold
import com.speakmylife.app.ui.theme.BrandGoldInk
import com.speakmylife.app.ui.theme.HighContrastPalette
import com.speakmylife.app.ui.theme.Motion
import com.speakmylife.app.ui.theme.isLight
import com.speakmylife.app.ui.theme.rememberReduceMotion
import java.util.Calendar

/*
 * 首頁主控台（對齊網頁版 #tab-home）：深綠問候卡＋「開始說話」、六張功能卡入口。
 * 快速求救卡原本就在主畫面最上方，主控台放在它的上下，順序與網頁相同：
 * 問候卡 → 快速求救 → 功能卡。
 *
 * 底部導覽維持原本六項（不新增「首頁」分頁）：改導覽結構牽動演示、眨眼掃描目標、
 * 測試側門的路由表，風險大於收益；主控台就是「重組」分頁的開頭。
 */

/** 卡片圓角：24dp，同網頁 --r-lg。 */
val BrandCardShape: Shape = RoundedCornerShape(24.dp)

/**
 * 品牌卡片外觀：24dp 圓角＋柔和陰影＋細邊框。
 * 陰影用品牌深綠而不是純黑（同網頁 --shadow-rgb），淺色主題上比較像「浮起來的紙」。
 * 高對比模式：不畫陰影（純黑底上看不到，只會讓邊緣糊掉），改成 2dp 實線外框。
 */
@Composable
fun Modifier.brandCard(
    palette: AppPalette,
    container: Color = palette.surface,
    borderColor: Color? = null,
    elevation: Dp = 6.dp,
): Modifier {
    val hc = palette == HighContrastPalette
    val shadowTint = if (palette.isLight) BrandForestDark.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.6f)
    val line = when {
        hc -> palette.textMuted
        borderColor != null -> borderColor
        palette.isLight -> palette.textLight.copy(alpha = 0.08f)
        else -> Color.White.copy(alpha = 0.06f)
    }
    return this
        .then(
            if (hc) Modifier
            else Modifier.shadow(elevation, BrandCardShape, clip = false, ambientColor = shadowTint, spotColor = shadowTint)
        )
        .clip(BrandCardShape)
        .background(container)
        .border(if (hc) 2.dp else 1.dp, line, BrandCardShape)
}

/** 依時間問候（同網頁 ui.js：11 點前早安、18 點前午安、之後晚安）。 */
private fun greetingNow(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        h < 11 -> I18n.tr("早安")
        h < 18 -> I18n.tr("午安")
        else -> I18n.tr("晚安")
    }
}

/**
 * 深綠問候卡：品牌漸層底、一條暖黃絲線從左下織到右上（只在第一次出現時畫一次），
 * 一顆暖黃膠囊大按鈕「開始說話」。
 */
@Composable
fun HomeHeroCard(
    palette: AppPalette,
    onStartSpeaking: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hc = palette == HighContrastPalette
    val reduce = rememberReduceMotion()
    val heroBrush = when {
        hc -> Brush.linearGradient(listOf(Color.Black, Color.Black))
        palette.isLight -> Brush.linearGradient(listOf(BrandForest, BrandForestDark))
        else -> Brush.linearGradient(listOf(Color(0xFF24604F), Color(0xFF0F2A22)))
    }
    val thread = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (thread.value < 1f) thread.animateTo(1f, tween(1200, delayMillis = 200, easing = Motion.Emphasized))
    }
    val greeting = remember { greetingNow() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (hc) Modifier
                else Modifier.shadow(10.dp, BrandCardShape, clip = false,
                    ambientColor = BrandForestDark.copy(alpha = 0.35f), spotColor = BrandForestDark.copy(alpha = 0.35f))
            )
            .clip(BrandCardShape)
            .background(heroBrush)
            .then(if (hc) Modifier.border(2.dp, palette.neonCyan, BrandCardShape) else Modifier)
    ) {
        // 流動光暈：右上一團暖黃、左下一團玉綠，各自慢慢繞圈（20 秒上下一圈）。
        // 亮度壓得很低——白字壓在光暈正中央也要有 4.5:1。漂移只動 graphicsLayer，
        // 不重組、不重畫；減少動態時停在原位，高對比不畫。
        if (!hc) {
            GlowBlob(BrandGold, 0.18f, 0.92f, 0.10f, 0.55f, 26.dp, 18.dp, 19000, !reduce, blurRadius = 20.dp)
            GlowBlob(Color(0xFF7FCDB3), 0.16f, 0.06f, 0.95f, 0.60f, 30.dp, 14.dp, 23000, !reduce, phaseOffset = 0.5f, blurRadius = 20.dp)
        }
        // 暖黃絲線（裝飾，不參與無障礙樹）。高對比下不畫，避免干擾文字。
        if (!hc) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height
                val p = thread.value
                if (p <= 0f) return@Canvas
                val path = Path()
                val steps = 40
                for (i in 0..steps) {
                    val t = p * i / steps
                    // 與網頁 hero-thread 同形：左下起、中段一個起伏、收在右上
                    val x = -0.02f * w + t * 1.04f * w
                    val y = h * (0.80f - 0.38f * t + 0.10f * kotlin.math.sin(t * 2f * kotlin.math.PI.toFloat()))
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, BrandGold.copy(alpha = 0.55f), style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 22.dp)) {
            Text(greeting, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text(
                I18n.tr("今天想說什麼？慢慢來，我們一起把話拾起來。"),
                fontSize = 16.sp, lineHeight = 24.sp, color = Color.White.copy(alpha = if (hc) 1f else 0.9f)
            )
            Spacer(Modifier.height(18.dp))
            PillButton(
                text = I18n.tr("✨ 開始說話"),
                container = if (hc) palette.neonCyan else BrandGold,
                content = if (hc) Color.Black else BrandGoldInk,
                onClick = onStartSpeaking,
                minHeight = 56.dp,
                glass = true,
                palette = palette
            )
        }
    }
}

/**
 * 膠囊按鈕（同網頁 .btn 的 999px 圓角）。按下縮到 96%、放開輕彈回來——按鈕自己動一下，
 * 手抖的使用者就知道有按到。系統關掉動畫時 Compose 會直接跳到終值。
 */
@Composable
fun PillButton(
    text: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    minHeight: Dp = 52.dp,
    glass: Boolean = false,
    palette: AppPalette? = null,
) {
    val source = remember { MutableInteractionSource() }
    // 液態玻璃膠囊：同色但半透明（0.86）、上半部一道白色高光、上亮下暗的描邊。
    // 底下是深綠問候卡，深棕字在這顆膠囊上仍有 ~6:1。高對比：實色＋ 2dp 白框。
    val useGlass = glass && palette != null
    val glassMod = if (useGlass && palette != null) {
        if (palette.isHighContrast) Modifier.clip(CircleShape).background(container).border(2.dp, Color.White, CircleShape)
        else Modifier.liquidGlass(
            palette = palette,
            shape = CircleShape,
            fill = container.copy(alpha = 0.86f),
            elevation = 6.dp,
        )
    } else Modifier
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (useGlass) Color.Transparent else container,
            contentColor = content
        ),
        contentPadding = PaddingValues(horizontal = 26.dp, vertical = 12.dp),
        interactionSource = source,
        modifier = modifier
            .heightIn(min = minHeight)
            .pressBounce(source)
            .then(glassMod)
    ) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/** 功能卡要去哪裡；主畫面依此決定是捲動、切分頁還是開圖卡。 */
enum class HomeFeature { Compose, Rehab, Aac, Report, Languages, Sync }

private data class FeatureSpec(
    val feature: HomeFeature,
    val icon: ImageVector,
    val title: String,
    val desc: String,
    val tint: Color,
)

/**
 * 「功能」卡：設計書的六張功能卡，兩欄排列，整張卡都可以按（最小 120dp 高）。
 * 依序淡入浮上（[enterStaggered]），眼睛會從左上讀到右下。
 */
@Composable
fun HomeFeatureGrid(
    palette: AppPalette,
    onFeature: (HomeFeature) -> Unit,
    modifier: Modifier = Modifier,
) {
    val specs = listOf(
        FeatureSpec(HomeFeature.Compose, Icons.Default.ChatBubbleOutline, I18n.tr("句子重組"), I18n.tr("AI 將零散詞語重組為自然語句"), palette.neonCyan),
        FeatureSpec(HomeFeature.Rehab, Icons.Default.Mic, I18n.tr("語音復健"), I18n.tr("AI 評分、錯誤標示與連續發音追蹤"), palette.neonGreen),
        FeatureSpec(HomeFeature.Aac, Icons.Default.GridView, I18n.tr("AAC 符號卡"), I18n.tr("依類別點選圖卡溝通"), palette.auraAmber),
        FeatureSpec(HomeFeature.Report, Icons.Default.BarChart, I18n.tr("進度報告"), I18n.tr("趨勢圖、CSV 匯出、Telegram 推送"), palette.dangerRose),
        FeatureSpec(HomeFeature.Languages, Icons.Default.Language, I18n.tr("多語言 TTS/STT"), I18n.tr("中英日韓語音輸入與朗讀"), palette.neonCyan),
        FeatureSpec(HomeFeature.Sync, Icons.Default.Cloud, I18n.tr("雲端同步"), I18n.tr("Firebase 跨裝置同步"), palette.neonGreen),
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .brandCard(palette)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(I18n.tr("功能"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = palette.textLight)
        specs.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                pair.forEachIndexed { col, spec ->
                    FeatureTile(
                        spec = spec,
                        palette = palette,
                        onClick = { onFeature(spec.feature) },
                        modifier = Modifier
                            .weight(1f)
                            .enterStaggered(index = row * 2 + col, key = Unit)
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FeatureTile(
    spec: FeatureSpec,
    palette: AppPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hc = palette == HighContrastPalette
    val source = remember { MutableInteractionSource() }
    val tileShape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .heightIn(min = 120.dp)
            // 彈簧按壓回饋（減少動態時不縮放）
            .pressBounce(source)
            .clip(tileShape)
            .background(if (hc) Color.Black else spec.tint.copy(alpha = if (palette.isLight) 0.06f else 0.10f))
            .border(
                if (hc) 2.dp else 1.dp,
                if (hc) palette.textMuted else spec.tint.copy(alpha = 0.22f),
                tileShape
            )
            .clickable(
                interactionSource = source,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick
            )
            .padding(14.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (hc) Color.Black else spec.tint.copy(alpha = 0.14f))
                .then(if (hc) Modifier.border(BorderStroke(1.5.dp, palette.neonCyan), RoundedCornerShape(16.dp)) else Modifier)
        ) {
            Icon(spec.icon, contentDescription = null, tint = if (hc) palette.neonCyan else spec.tint, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(spec.title, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, color = palette.textLight)
        Spacer(Modifier.height(4.dp))
        Text(spec.desc, fontSize = 13.sp, lineHeight = 18.sp, color = palette.textMuted)
    }
}
