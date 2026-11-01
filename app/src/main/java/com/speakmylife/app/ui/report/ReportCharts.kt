package com.speakmylife.app.ui.report

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.speakmylife.app.ai.TimeBucket
import com.speakmylife.app.ui.I18n
import com.speakmylife.app.ui.theme.AppPalette
import com.speakmylife.app.ui.theme.Motion
import com.speakmylife.app.ui.theme.rememberReduceMotion

/**
 * 純 Compose Canvas 圖表骨架。沒選 MPAndroidChart 是因為：
 *   (a) MPAndroidChart 是 View-based，要包 AndroidView 才能進 Compose，動畫/手勢轉換麻煩
 *   (b) 我們只要折線 + 長條，自己畫 60 行就能定制配色配合 AppPalette
 *   (c) 截圖匯出走 ComposeView → Bitmap 也比 AndroidView 順
 *
 * 想換 MPAndroidChart 時只要把這檔的兩個 @Composable 換成 AndroidView { LineChart(...) }，
 * ReportScreen 不需要改。
 */

@Composable
fun LineChartCompose(
    data: List<TimeBucket>,
    palette: AppPalette,
    modifier: Modifier = Modifier,
    yMax: Float = 100f,
    title: String = I18n.tr("進步趨勢（平均分數）"),
) {
    Column(modifier = modifier) {
        Text(title, color = palette.textLight, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(palette.surface)
                .border(1.dp, palette.neonCyan.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 22.dp)
        ) {
            if (data.isEmpty()) {
                Text(
                    I18n.tr("尚無資料"),
                    color = palette.textMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment2.Center)
                )
                return@Box
            }
            val labelColor = palette.textMuted.toArgb()
            val density = LocalDensity.current
            val labelPx = with(density) { 10.sp.toPx() }
            val reveal = rememberChartReveal(data)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                val stepX = if (data.size > 1) w / (data.size - 1) else 0f
                // 進場：折線由左往右「畫」出來（裁切寬度跟著 reveal 走）。治療師的視線會
                // 跟著線從最早走到最近，正好是讀趨勢的方向。
                val revealX = w * reveal.value

                // Y 軸格線（4 條）
                for (i in 0..4) {
                    val y = h - (h * i / 4f)
                    drawLine(
                        color = palette.textMuted.copy(alpha = 0.15f),
                        start = Offset(0f, y), end = Offset(w, y),
                        strokeWidth = 1f
                    )
                }

                // 折線（＋線下淡色面積，讓趨勢的「高低」一眼看得出來）
                val path = Path()
                data.forEachIndexed { i, p ->
                    val x = i * stepX
                    val y = h - (p.value / yMax).coerceIn(0f, 1f) * h
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                val area = Path().apply {
                    addPath(path)
                    lineTo((data.size - 1) * stepX, h)
                    lineTo(0f, h)
                    close()
                }
                clipRect(left = 0f, top = -8f, right = revealX + 6f, bottom = h + 8f) {
                    if (data.size > 1) {
                        drawPath(
                            path = area,
                            brush = Brush.verticalGradient(
                                listOf(palette.neonCyan.copy(alpha = 0.22f), palette.neonCyan.copy(alpha = 0f)),
                                startY = 0f, endY = h
                            )
                        )
                    }
                    drawPath(
                        path = path,
                        color = palette.neonCyan,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }

                // 資料點 + X 軸 label。點一多（月報 30 天）標籤會疊成一團黑，
                // 所以只畫約 8 個，最後一個一定畫（治療師最在意「最近一次」）。
                val labelEvery = ((data.size + 7) / 8).coerceAtLeast(1)
                data.forEachIndexed { i, p ->
                    val x = i * stepX
                    val y = h - (p.value / yMax).coerceIn(0f, 1f) * h
                    // 資料點在線畫到它時才出現；最後一點（最近一次）畫大一圈
                    if (x <= revealX + 1f) {
                        val r = if (i == data.size - 1) 5.dp.toPx() else 3.5.dp.toPx()
                        drawCircle(palette.surface, radius = r + 1.5.dp.toPx(), center = Offset(x, y))
                        drawCircle(palette.neonGreen, radius = r, center = Offset(x, y))
                    }
                    if (i % labelEvery != 0 && i != data.size - 1) return@forEachIndexed
                    drawContext.canvas.nativeCanvas.drawText(
                        p.labelShort,
                        x - labelPx,
                        h + labelPx + 4f,
                        android.graphics.Paint().apply {
                            color = labelColor
                            textSize = labelPx
                            isAntiAlias = true
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun BarChartCompose(
    data: List<TimeBucket>,
    palette: AppPalette,
    modifier: Modifier = Modifier,
    title: String = I18n.tr("正向情緒字眼次數"),
) {
    Column(modifier = modifier) {
        Text(title, color = palette.textLight, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(palette.surface)
                .border(1.dp, palette.auraAmber.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 22.dp)
        ) {
            if (data.isEmpty()) {
                Text(I18n.tr("尚無資料"), color = palette.textMuted, fontSize = 12.sp,
                     modifier = Modifier.align(Alignment2.Center))
                return@Box
            }
            val maxVal = (data.maxOfOrNull { it.value } ?: 1f).coerceAtLeast(1f)
            val labelColor = palette.textMuted.toArgb()
            val density = LocalDensity.current
            val labelPx = with(density) { 10.sp.toPx() }
            val reveal = rememberChartReveal(data)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                val barW = w / data.size * 0.55f
                val gap = w / data.size * 0.45f
                // 進場：長條由左到右依序長高（每根落後一點），跟折線圖同一個節奏
                val n = data.size
                data.forEachIndexed { i, p ->
                    val x = i * (barW + gap) + gap / 2
                    val start = if (n > 1) i.toFloat() / n * 0.5f else 0f
                    val grow = ((reveal.value - start) / 0.5f).coerceIn(0f, 1f)
                    val barH = (p.value / maxVal) * h * grow
                    val corner = (barW * 0.3f).coerceAtMost(6.dp.toPx())
                    drawRoundRect(
                        color = palette.auraAmber.copy(alpha = 0.85f),
                        topLeft = Offset(x, h - barH),
                        size = Size(barW, barH),
                        cornerRadius = CornerRadius(corner, corner)
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        p.labelShort,
                        x,
                        h + labelPx + 4f,
                        android.graphics.Paint().apply {
                            color = labelColor; textSize = labelPx; isAntiAlias = true
                        }
                    )
                }
            }
        }
    }
}

/**
 * 圖表進場進度 0→1（約 0.9 秒）。資料換了（切週報／月報）會重畫一次；
 * 系統關掉動畫時直接是 1，圖表一出現就是完整的。
 */
@Composable
private fun rememberChartReveal(data: List<TimeBucket>): Animatable<Float, AnimationVector1D> {
    val reduce = rememberReduceMotion()
    val reveal = remember(data) { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(data) {
        if (reveal.value < 1f) reveal.animateTo(1f, tween(900, easing = Motion.Emphasized))
    }
    return reveal
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()
)

/** alias 避免和 Compose 自身的 Alignment 撞名（顯式 import） */
private object Alignment2 {
    val Center = androidx.compose.ui.Alignment.Center
}
