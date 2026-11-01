package com.speakmylife.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.speakmylife.app.ui.theme.AppPalette
import com.speakmylife.app.ui.theme.BrandForest
import com.speakmylife.app.ui.theme.BrandThread
import com.speakmylife.app.ui.theme.Motion
import com.speakmylife.app.ui.theme.rememberReduceMotion
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * 拾語者品牌視覺元件：logo、「拾起碎詞、織成句子」載入動畫、分數環、聆聽脈動、依序進場。
 *
 * 全部用 Canvas 自己畫，不加任何依賴。每個循環動畫都先看 [rememberReduceMotion]：
 * 使用者在系統關掉動畫時，改畫一張「已經完成」的靜態圖，而不是凍在半路的影格。
 */

// ─── Logo ──────────────────────────────────────────────────────────────

/**
 * 拾語者 logo：深綠方塊 + 白色對話泡泡 + 暖黃絲線。座標與網頁版 logo 的 64×64 SVG 相同，
 * 改 logo 時兩邊對著改。
 *
 * [animate] = true 時絲線會在第一次出現時「織」進來（約 0.9 秒），之後靜止——
 * 只在開 App 時講一次品牌故事，不在使用者每次回到主畫面時都跑。
 */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    contentDescription: String? = null,
    animate: Boolean = true,
) {
    val reduce = rememberReduceMotion()
    val thread = remember { Animatable(if (animate && !reduce) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (thread.value < 1f) {
            delay(180)
            thread.animateTo(1f, tween(900, easing = Motion.Emphasized))
        }
    }
    val label = contentDescription
    val semanticsModifier = if (label != null) {
        Modifier.semantics { this.contentDescription = label }
    } else Modifier
    Canvas(modifier = modifier.size(size).then(semanticsModifier)) {
        drawBrandMark(thread.value)
    }
}

/** 在目前的 DrawScope 裡畫 logo；[threadProgress] 0..1 控制絲線畫到哪裡。 */
private fun DrawScope.drawBrandMark(threadProgress: Float) {
    val s = min(size.width, size.height) / 64f
    fun o(x: Float, y: Float) = Offset(x * s, y * s)

    drawRoundRect(
        color = BrandForest,
        topLeft = Offset.Zero,
        size = Size(64f * s, 64f * s),
        cornerRadius = CornerRadius(14f * s)
    )
    // 對話泡泡本體 + 左下的尾巴
    drawRoundRect(
        color = Color.White,
        topLeft = o(10f, 9f),
        size = Size(44f * s, 24f * s),
        cornerRadius = CornerRadius(6f * s)
    )
    val tail = Path().apply {
        moveTo(34f * s, 32f * s)
        lineTo(27f * s, 39f * s)
        lineTo(27f * s, 32f * s)
        close()
    }
    drawPath(tail, Color.White)
    // 三個點＝還沒說出口的碎詞
    for (cx in floatArrayOf(22f, 32f, 42f)) {
        drawCircle(BrandForest, radius = 3f * s, center = o(cx, 21f))
    }
    // 暖黃絲線：三次貝茲 (9,43) → (14,57),(50,57) → (55,43)，依進度取樣畫出一段
    val p = threadProgress.coerceIn(0f, 1f)
    if (p > 0f) {
        val path = Path()
        val steps = 28
        for (i in 0..steps) {
            val t = p * i / steps
            val u = 1f - t
            val x = u * u * u * 9f + 3f * u * u * t * 14f + 3f * u * t * t * 50f + t * t * t * 55f
            val y = u * u * u * 43f + 3f * u * u * t * 57f + 3f * u * t * t * 57f + t * t * t * 43f
            if (i == 0) path.moveTo(x * s, y * s) else path.lineTo(x * s, y * s)
        }
        drawPath(path, BrandThread, style = Stroke(width = 5f * s, cap = StrokeCap.Round))
    }
}

// ─── 載入動畫：拾起碎詞、織成句子 ──────────────────────────────────────

/**
 * AI 重組中的載入動畫。不是隨便轉圈——它在演這個功能本身：
 *  ① 四塊散落、歪斜的「碎詞」慢慢歸位排成一列（拾起）
 *  ② 一條暖黃絲線從左到右穿過它們，一上一下交錯（織成句子）
 *  ③ 停一下讓眼睛看清楚「完成了」的樣子，淡出，重來
 *
 * 一輪 2.8 秒，比一般 spinner 慢很多：對中高齡使用者，慢而可預期的動作比快速旋轉安定。
 * 關掉動畫時直接畫「已織好」的靜態圖。
 */
@Composable
fun WeavingLoader(
    palette: AppPalette,
    modifier: Modifier = Modifier,
) {
    val reduce = rememberReduceMotion()
    // 用 State 傳進 Canvas、在 draw 階段才讀：每一幀只重畫，不重組。
    val progress: State<Float> = if (reduce) {
        remember { mutableFloatStateOf(0.82f) }
    } else {
        rememberInfiniteTransition(label = "weave").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart),
            label = "weaveProgress"
        )
    }
    val chipColor = palette.neonCyan
    val threadColor = palette.auraAmber
    Canvas(modifier = modifier) {
        val t = progress.value
        val w = size.width
        val h = size.height
        val n = 4
        val gather = easeInOut(((t - 0.04f) / 0.40f).coerceIn(0f, 1f))
        val weave = easeInOut(((t - 0.44f) / 0.36f).coerceIn(0f, 1f))
        val fade = 1f - ((t - 0.90f) / 0.10f).coerceIn(0f, 1f)
        val chipW = w / (n * 1.55f)
        val chipH = h * 0.30f
        val gap = (w - n * chipW) / (n + 1)
        val baseY = h * 0.5f
        // 散落時的位置與角度：固定值，不用亂數——每一輪都一樣，才不會像在閃。
        val scatterX = floatArrayOf(-0.08f, 0.05f, -0.04f, 0.07f)
        val scatterY = floatArrayOf(-0.30f, 0.24f, -0.16f, 0.30f)
        val tilt = floatArrayOf(-16f, 12f, -9f, 14f)

        fun chip(i: Int) {
            val targetX = gap + i * (chipW + gap)
            val x = targetX + scatterX[i] * w * (1f - gather)
            val y = baseY - chipH / 2f + scatterY[i] * h * (1f - gather)
            rotate(degrees = tilt[i] * (1f - gather), pivot = Offset(x + chipW / 2f, y + chipH / 2f)) {
                drawRoundRect(
                    color = chipColor.copy(alpha = (0.35f + 0.6f * gather) * fade),
                    topLeft = Offset(x, y),
                    size = Size(chipW, chipH),
                    cornerRadius = CornerRadius(chipH / 2f)
                )
            }
        }

        // 偶數塊在線下、奇數塊在線上——一上一下，看起來才像「織」而不是「劃過」。
        for (i in 0 until n step 2) chip(i)
        if (weave > 0f) {
            val endX = w * weave
            val path = Path()
            val steps = 48
            val amp = chipH * 0.85f
            for (k in 0..steps) {
                val x = endX * k / steps
                // 波長對齊碎詞間距：每經過一塊換一次上下
                val y = baseY + sin((x - gap) / (chipW + gap) * PI.toFloat()) * amp
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path,
                threadColor.copy(alpha = fade),
                style = Stroke(width = (h * 0.075f).coerceAtLeast(2f), cap = StrokeCap.Round)
            )
        }
        for (i in 1 until n step 2) chip(i)
    }
}

private fun easeInOut(x: Float): Float = x * x * (3f - 2f * x)

// ─── 分數環／進度環 ───────────────────────────────────────────────────

/**
 * 跟讀分數：環從 0 畫到分數、中間數字跟著往上數。
 * 「數上去」讓使用者有時間把目光移過來，而不是一眨眼就出現一個冷冰冰的數字。
 * 環的大小跟字級一起放大（數字也是 sp），大字模式下數字不會撐破圓圈。
 */
@Composable
fun ScoreRing(
    score: Int,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    suffix: String = "",
    diameter: Dp = 92.dp,
    strokeWidth: Dp = 9.dp,
) {
    val reduce = rememberReduceMotion()
    val shown = remember { Animatable(if (reduce) score.toFloat() else 0f) }
    LaunchedEffect(score) {
        if (reduce) shown.snapTo(score.toFloat())
        else shown.animateTo(score.toFloat(), tween(Motion.Calm * 2, easing = Motion.Emphasized))
    }
    val fs = LocalDensity.current.fontScale.coerceIn(1f, 2.2f)
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(diameter * fs)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRing(
                fraction = shown.value / 100f,
                color = color,
                trackColor = trackColor,
                stroke = strokeWidth.toPx()
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${shown.value.roundToInt()}",
                fontSize = 30.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Black,
                color = color
            )
            if (suffix.isNotBlank()) {
                Text(suffix, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, color = color)
            }
        }
    }
}

/** 小進度環（整段練習第幾句）。進度改變時平滑補過去，不是一格一格跳。 */
@Composable
fun ProgressRing(
    progress: Float,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    diameter: Dp = 32.dp,
    strokeWidth: Dp = 4.dp,
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(Motion.Calm, easing = Motion.Emphasized),
        label = "progressRing"
    )
    Canvas(modifier = modifier.size(diameter)) {
        drawRing(fraction = animated, color = color, trackColor = trackColor, stroke = strokeWidth.toPx())
    }
}

private fun DrawScope.drawRing(fraction: Float, color: Color, trackColor: Color, stroke: Float) {
    val d = min(size.width, size.height) - stroke
    val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f)
    val arcSize = Size(d, d)
    drawArc(
        color = trackColor,
        startAngle = -90f,
        sweepAngle = 360f,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = stroke)
    )
    val sweep = 360f * fraction.coerceIn(0f, 1f)
    if (sweep > 0.5f) {
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

// ─── 聆聽脈動 ─────────────────────────────────────────────────────────

/**
 * 錄音中的脈動圈：三圈由內往外擴散、漸淡。
 *
 * 刻意**不畫成音量波形**：錄音管線沒有回報即時音量，畫一條會跳的波形就是在編資料，
 * 使用者會以為「線沒動＝我沒出聲」。這個圈只表達一件事：「我正在聽」。
 * 關掉動畫時畫一個靜態的淡圈。
 */
@Composable
fun ListeningPulse(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val reduce = rememberReduceMotion()
    if (reduce) {
        Canvas(modifier = modifier) {
            drawCircle(color.copy(alpha = 0.22f), radius = min(size.width, size.height) / 2f)
        }
        return
    }
    val phase by rememberInfiniteTransition(label = "listen").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "listenPhase"
    )
    Canvas(modifier = modifier) {
        val maxR = min(size.width, size.height) / 2f
        val ring = 3.dp.toPx()
        for (k in 0 until 3) {
            val p = (phase + k / 3f) % 1f
            drawCircle(
                color = color.copy(alpha = (1f - p) * 0.5f),
                radius = maxR * (0.34f + 0.66f * p),
                style = Stroke(width = ring)
            )
        }
        drawCircle(color.copy(alpha = 0.18f), radius = maxR * 0.34f)
    }
}

// ─── 依序進場 ─────────────────────────────────────────────────────────

/**
 * 依序進場：第 [index] 項延遲 index × [Motion.Stagger] 後，由下方 20dp 淡入浮上。
 * [key] 改變（例如 AI 重新生成了一批候選句）時重播。
 *
 * 讓三個候選句「一句一句」到，眼睛會自然從第一句（信心最高）往下讀，
 * 而不是三句同時砸過來不知道先看哪裡。
 */
@Composable
fun Modifier.enterStaggered(index: Int, key: Any?): Modifier {
    val reduce = rememberReduceMotion()
    val p = remember(key) { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(key) {
        if (p.value < 1f) {
            delay(index.coerceAtLeast(0) * Motion.Stagger.toLong())
            p.animateTo(1f, tween(Motion.Standard + 80, easing = Motion.Emphasized))
        }
    }
    return this.graphicsLayer {
        val v = p.value
        alpha = v
        translationY = (1f - v) * 20.dp.toPx()
    }
}

/**
 * 彈出：第一次出現（或 [key] 改變）時從 92% 放大、淡入到原尺寸，略帶一點點回彈。
 * 用在「需要患者確認」的卡片上——它出現的那一刻就是要把目光拉過去。
 */
@Composable
fun Modifier.popIn(key: Any?): Modifier {
    val reduce = rememberReduceMotion()
    val p = remember(key) { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(key) {
        if (p.value < 1f) {
            p.animateTo(
                1f,
                androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 380f)
            )
        }
    }
    return this.graphicsLayer {
        val v = p.value
        alpha = v.coerceIn(0f, 1f)
        val sc = 0.92f + 0.08f * v
        scaleX = sc
        scaleY = sc
    }
}
