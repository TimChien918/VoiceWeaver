package com.example.ui.aac

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AppPalette
import kotlinx.coroutines.delay

/**
 * 眼動 + 觸控融合按鈕。Spec 段 #1 的「Gaze Hover + Dwell Control」實作。
 *
 * 行為：
 *  - `gazeWithin = true` 時：平滑放大到 [magneticScale]、加上 neon 邊框（磁吸感）。
 *  - `gazeWithin` 持續 [dwellMillis]（預設 3000ms） → 自動觸發 [onTriggered] + Haptic。
 *  - 觸控單擊（< 250ms）→ 立即觸發（觸控比眼動準，accuracy-first）。
 *  - 觸控長按（≥ dwellMillis）→ 也觸發（給家屬手動操作備援）。
 *
 * 與既有 [DwellableSurface] 的差異：
 *  - 預設 dwell = 3000ms（spec 要求），DwellableSurface 預設 1000ms。
 *  - 內建 Haptic（LongPress 強度）。
 *  - 內建 label slot，UI 端少寫 boilerplate。
 */
@Composable
fun GazeMagneticButton(
    label: String,
    emoji: String,
    onTriggered: () -> Unit,
    palette: AppPalette,
    modifier: Modifier = Modifier,
    gazeWithin: Boolean = false,
    dwellMillis: Long = 3_000L,
    magneticScale: Float = 1.18f,
    isDanger: Boolean = false
) {
    val haptic = LocalHapticFeedback.current
    var dwellStart by remember { mutableStateOf<Long?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(gazeWithin) {
        if (gazeWithin) {
            dwellStart = System.currentTimeMillis()
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) // 進入凝視時的輕觸
        } else if (dwellStart != null) {
            dwellStart = null
            progress = 0f
        }
    }

    LaunchedEffect(dwellStart) {
        val start = dwellStart ?: return@LaunchedEffect
        while (dwellStart == start) {
            val elapsed = System.currentTimeMillis() - start
            progress = (elapsed / dwellMillis.toFloat()).coerceIn(0f, 1f)
            if (progress >= 1f) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onTriggered()
                dwellStart = null
                progress = 0f
                break
            }
            delay(16)
        }
    }

    val scale by animateFloatAsState(
        targetValue = if (gazeWithin) magneticScale else 1f,
        animationSpec = tween(durationMillis = 220),
        label = "magnetic-scale"
    )

    val borderColor = when {
        isDanger -> palette.dangerRose
        gazeWithin -> palette.neonCyan
        else -> palette.neonCyan.copy(alpha = 0.25f)
    }
    val bg = when {
        isDanger -> palette.dangerRose.copy(alpha = 0.10f)
        gazeWithin -> palette.neonCyan.copy(alpha = 0.16f)
        else -> palette.surface
    }

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(if (gazeWithin || isDanger) 2.5.dp else 1.dp, borderColor, RoundedCornerShape(20.dp))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onTriggered()
            }
            .padding(horizontal = 14.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 34.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                label,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isDanger) palette.dangerRose else palette.textLight
            )
        }
        if (progress > 0.02f) {
            CircularProgressIndicator(
                progress = { progress },
                color = if (isDanger) palette.dangerRose else palette.neonCyan,
                strokeWidth = 4.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(26.dp)
            )
        }
        // 隱藏一個 LocalContentColor 提供值，避免子元件去吃預設 Material 顏色
        CompositionLocalProvider(LocalContentColor provides palette.textLight) { /* anchor */ }
    }
}

/** 與 [GazeMagneticButton] 同邏輯，但純色塊版本，給「✔️ 接受 / ✖️ 重來」這種大按鈕用。 */
@Composable
fun GazeConfirmBlock(
    label: String,
    emoji: String,
    bg: Color,
    onTriggered: () -> Unit,
    palette: AppPalette,
    modifier: Modifier = Modifier,
    gazeWithin: Boolean = false,
    dwellMillis: Long = 3_000L
) {
    val haptic = LocalHapticFeedback.current
    var dwellStart by remember { mutableStateOf<Long?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(gazeWithin) {
        if (gazeWithin) dwellStart = System.currentTimeMillis()
        else if (dwellStart != null) { dwellStart = null; progress = 0f }
    }
    LaunchedEffect(dwellStart) {
        val start = dwellStart ?: return@LaunchedEffect
        while (dwellStart == start) {
            val elapsed = System.currentTimeMillis() - start
            progress = (elapsed / dwellMillis.toFloat()).coerceIn(0f, 1f)
            if (progress >= 1f) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onTriggered(); dwellStart = null; progress = 0f; break
            }
            delay(16)
        }
    }
    val scale by animateFloatAsState(if (gazeWithin) 1.10f else 1f, label = "confirm-scale")

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(28.dp))
            .background(bg)
            .border(3.dp, if (gazeWithin) palette.neonCyan else Color.Transparent, RoundedCornerShape(28.dp))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onTriggered()
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 64.sp)
            Spacer(Modifier.height(8.dp))
            Text(label, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        }
        if (progress > 0.02f) {
            CircularProgressIndicator(
                progress = { progress },
                color = Color.White,
                strokeWidth = 6.dp,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(36.dp)
            )
        }
    }
}
