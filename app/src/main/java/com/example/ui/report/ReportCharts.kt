package com.example.ui.report

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.TimeBucket
import com.example.ui.I18n
import com.example.ui.theme.AppPalette

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
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                val stepX = if (data.size > 1) w / (data.size - 1) else 0f

                // Y 軸格線（4 條）
                for (i in 0..4) {
                    val y = h - (h * i / 4f)
                    drawLine(
                        color = palette.textMuted.copy(alpha = 0.15f),
                        start = Offset(0f, y), end = Offset(w, y),
                        strokeWidth = 1f
                    )
                }

                // 折線
                val path = Path()
                data.forEachIndexed { i, p ->
                    val x = i * stepX
                    val y = h - (p.value / yMax).coerceIn(0f, 1f) * h
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = palette.neonCyan,
                    style = Stroke(width = 3f, cap = StrokeCap.Round)
                )

                // 資料點 + X 軸 label。點一多（月報 30 天）標籤會疊成一團黑，
                // 所以只畫約 8 個，最後一個一定畫（治療師最在意「最近一次」）。
                val labelEvery = ((data.size + 7) / 8).coerceAtLeast(1)
                data.forEachIndexed { i, p ->
                    val x = i * stepX
                    val y = h - (p.value / yMax).coerceIn(0f, 1f) * h
                    drawCircle(palette.neonGreen, radius = 4f, center = Offset(x, y))
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
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                val barW = w / data.size * 0.55f
                val gap = w / data.size * 0.45f
                data.forEachIndexed { i, p ->
                    val x = i * (barW + gap) + gap / 2
                    val barH = (p.value / maxVal) * h
                    drawRect(
                        color = palette.auraAmber.copy(alpha = 0.85f),
                        topLeft = Offset(x, h - barH),
                        size = Size(barW, barH)
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

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()
)

/** alias 避免和 Compose 自身的 Alignment 撞名（顯式 import） */
private object Alignment2 {
    val Center = androidx.compose.ui.Alignment.Center
}
