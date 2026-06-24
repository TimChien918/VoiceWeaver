package com.example.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.EyeControlUiState
import com.example.ai.EyeInteractionMode
import com.example.ai.VoiceWeaverViewModel
import kotlinx.coroutines.delay

/**
 * 全域眨眼掃描 overlay。
 * - 顯示目前高亮的 target 標籤（底部橫幅）。
 * - 每隔 [intervalMs] 自動前進到下一個 target。
 * - 用眨眼（[eyeState].lastBlinkAt 改變）確認選取。
 * - 游標不顯示（游標在 GazeCursorOverlay 中根據模式自行隱藏）。
 */
@Composable
fun BlinkScanOverlay(
    viewModel: VoiceWeaverViewModel,
    eyeState: EyeControlUiState,
    intervalMs: Long = 1500L
) {
    if (!eyeState.enabled || eyeState.mode != EyeInteractionMode.BLINK_SCAN) return

    val targets by viewModel.globalScanTargets.collectAsState()
    val scanIndex by viewModel.globalScanIndex.collectAsState()
    if (targets.isEmpty()) return

    val currentLabel = targets.getOrNull(scanIndex)?.label ?: return

    // 自動 advance
    LaunchedEffect(targets, intervalMs) {
        while (true) {
            delay(intervalMs)
            viewModel.advanceScan()
        }
    }

    // 高亮閃爍
    val infiniteTransition = rememberInfiniteTransition(label = "scan")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.7f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(400), RepeatMode.Reverse),
        label = "alpha"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // 底部目標標籤橫幅
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF00E5FF).copy(alpha = 0.15f))
                .border(2.dp, Color(0xFF00E5FF).copy(alpha = alpha), RoundedCornerShape(16.dp))
                .padding(vertical = 14.dp, horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = currentLabel,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "${scanIndex + 1} / ${targets.size}  ·  眨眼確認",
                    color = Color(0xFF00E5FF),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
