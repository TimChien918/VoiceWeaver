package com.example.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.EyeControlManager
import kotlinx.coroutines.delay

/**
 * 5 點眼動校準畫面（OptiKey / EyeGestures 作法）。
 * 依序顯示 5 個靶點（中心＋四角），每點凝視 1.8 秒後自動前進。
 * 完成後呼叫 [onDone]。
 */
@Composable
fun EyeCalibrationScreen(
    manager: EyeControlManager,
    onDone: () -> Unit
) {
    // 靶點位置（screen 比例：0=左/上，1=右/下）
    val targets = remember {
        listOf(
            0.5f to 0.5f,   // 中心
            0.1f to 0.1f,   // 左上
            0.9f to 0.1f,   // 右上
            0.1f to 0.9f,   // 左下
            0.9f to 0.9f,   // 右下
        )
    }
    var step by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    val dwellMs = 1800L

    // 每步：通知 manager 開始採樣 → 等 dwellMs → 結束採樣 → 下一步
    LaunchedEffect(step) {
        if (step >= targets.size) { manager.clearCalibration(); onDone(); return@LaunchedEffect }
        val (sx, sy) = targets[step]
        manager.beginCalibSample(sx, sy)
        val start = System.currentTimeMillis()
        while (true) {
            val elapsed = System.currentTimeMillis() - start
            progress = (elapsed / dwellMs.toFloat()).coerceIn(0f, 1f)
            if (elapsed >= dwellMs) break
            delay(16)
        }
        manager.endCalibSample()
        delay(200)
        progress = 0f
        step++
    }

    // 閃爍動畫
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.7f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0D0D0D)),
        contentAlignment = Alignment.Center
    ) {
        if (step < targets.size) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val (sx, sy) = targets[step]
                val cx = sx * size.width
                val cy = sy * size.height

                // 外圓（進度環）
                drawCircle(Color(0xFF00E5FF).copy(alpha = 0.25f), radius = 60f * pulse, center = Offset(cx, cy))
                // 進度弧
                drawArc(
                    color = Color(0xFF00E5FF),
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(cx - 50f, cy - 50f),
                    size = androidx.compose.ui.geometry.Size(100f, 100f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 8f)
                )
                // 內點
                drawCircle(Color(0xFF00E5FF), radius = 14f, center = Offset(cx, cy))
            }

            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "請用眼睛凝視亮點",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "第 ${step + 1} / ${targets.size} 點",
                    color = Color(0xFF00E5FF),
                    fontSize = 16.sp
                )
            }
        } else {
            Text("校準完成 ✓", color = Color(0xFF00E5FF), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}
