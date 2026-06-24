package com.example.ui

import android.app.Activity
import android.view.MotionEvent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import com.example.ai.EyeControlUiState
import com.example.ai.EyeInteractionMode
import kotlinx.coroutines.delay

/**
 * 全域凝視游標 overlay。
 *
 * - 在螢幕任意位置渲染一個半透明凝視點（cyan 圓 + 進度環）。
 * - 凝視靜止 [dwellMs] 毫秒後自動注入合成觸控事件，讓任何畫面元件都能被眼動點擊。
 * - 只在 [eyeState].enabled 為 true 時顯示；[eyeState].running 為 false 時不渲染。
 */
@Composable
fun GazeCursorOverlay(
    eyeState: EyeControlUiState,
    activity: Activity,
    dwellMs: Long = 1200L
) {
    // 眨眼掃描模式不需要游標（只看高亮標籤）
    if (!eyeState.enabled || !eyeState.running) return
    if (eyeState.mode == EyeInteractionMode.BLINK_SCAN) return

    var size by remember { mutableStateOf(IntSize.Zero) }
    var dwellProgress by remember { mutableFloatStateOf(0f) }
    var dwellStartTime by remember { mutableLongStateOf(0L) }
    // 上次「穩定中心」：若游標移動超過閾值就重置計時
    var stableX by remember { mutableFloatStateOf(eyeState.gazeX) }
    var stableY by remember { mutableFloatStateOf(eyeState.gazeY) }
    var lastClickTime by remember { mutableLongStateOf(0L) }

    val view = LocalView.current

    // 更新凝視穩定性 + dwell 計時
    LaunchedEffect(eyeState.gazeX, eyeState.gazeY) {
        val dx = eyeState.gazeX - stableX
        val dy = eyeState.gazeY - stableY
        // 移動超過 5% 螢幕寬度 → 視為新位置，重置計時
        if (dx * dx + dy * dy > 0.0025f) {
            stableX = eyeState.gazeX
            stableY = eyeState.gazeY
            dwellStartTime = System.currentTimeMillis()
            dwellProgress = 0f
        }
    }

    // dwell 進度計時
    LaunchedEffect(stableX, stableY) {
        dwellStartTime = System.currentTimeMillis()
        while (true) {
            val elapsed = System.currentTimeMillis() - dwellStartTime
            dwellProgress = (elapsed / dwellMs.toFloat()).coerceIn(0f, 1f)
            if (dwellProgress >= 1f) {
                val now = System.currentTimeMillis()
                // 防止連續觸發：兩次點擊間距至少 800ms
                if (now - lastClickTime > 800L && size != IntSize.Zero) {
                    lastClickTime = now
                    val px = stableX * size.width
                    val py = stableY * size.height
                    injectClick(activity, view, px, py)
                }
                // 觸發後暫停 1 秒再允許下一次
                dwellProgress = 0f
                dwellStartTime = System.currentTimeMillis() + 1000L
            }
            delay(16)
        }
    }

    // 游標閃爍動畫
    val infiniteTransition = rememberInfiniteTransition(label = "gaze")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.85f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { size = it.size }
    ) {
        if (size == IntSize.Zero) return@Canvas
        val cx = eyeState.gazeX * this.size.width
        val cy = eyeState.gazeY * this.size.height
        val center = Offset(cx, cy)
        val r = 18f * pulse

        // 外光圈
        drawCircle(Color(0xFF00E5FF).copy(alpha = 0.18f), radius = r * 2.2f, center = center)
        // 凝視點
        drawCircle(Color(0xFF00E5FF).copy(alpha = 0.85f), radius = r, center = center)
        // 十字線（小）
        val cross = 10f
        drawLine(Color.White.copy(alpha = 0.7f), Offset(cx - cross, cy), Offset(cx + cross, cy), 1.5f)
        drawLine(Color.White.copy(alpha = 0.7f), Offset(cx, cy - cross), Offset(cx, cy + cross), 1.5f)

        // dwell 進度弧（停留時顯示）
        if (dwellProgress > 0.02f) {
            drawArc(
                color = Color(0xFF00E5FF),
                startAngle = -90f,
                sweepAngle = 360f * dwellProgress,
                useCenter = false,
                topLeft = Offset(cx - 28f, cy - 28f),
                size = androidx.compose.ui.geometry.Size(56f, 56f),
                style = Stroke(width = 5f)
            )
        }
    }
}

/** 在 Activity 視窗注入合成觸控事件，觸發目前位置的 Compose 元件 onClick。 */
private fun injectClick(activity: Activity, view: android.view.View, x: Float, y: Float) {
    val downTime = android.os.SystemClock.uptimeMillis()
    val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
    val up   = MotionEvent.obtain(downTime, downTime + 80L, MotionEvent.ACTION_UP, x, y, 0)
    activity.runOnUiThread {
        view.dispatchTouchEvent(down)
        view.dispatchTouchEvent(up)
        down.recycle(); up.recycle()
    }
}
