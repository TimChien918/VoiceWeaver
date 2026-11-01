package com.speakmylife.app.ui.aac

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.speakmylife.app.ui.theme.AppPalette
import kotlinx.coroutines.delay

/**
 * Dwell（停留觸發）封裝：支援 3 種觸發來源融合，全部走同一個 [DwellTrigger] 介面。
 *
 * - 觸控**單擊** → 立即觸發（accuracy-first：手指比眼睛準）
 * - 觸控**按住** → 啟動 dwell 進度環；達 [dwellMillis] 觸發
 * - 眼動「凝視」(`gazeWithin = true`) → 啟動 dwell 進度環；達 [dwellMillis] 觸發
 *
 * 視覺磁吸：[gazeWithin] 為 true 時自動放大 + 高亮邊框，降低眼動誤差感知。
 */
@Composable
fun DwellableSurface(
    onTriggered: () -> Unit,
    palette: AppPalette,
    modifier: Modifier = Modifier,
    dwellMillis: Long = 1000L,
    enableTouchTap: Boolean = true,
    gazeWithin: Boolean = false,    // EyeControlManager 餵入：是否凝視在本元件上
    cornerRadius: Int = 18,
    content: @Composable BoxScope.() -> Unit
) {
    var dwellStart by remember { mutableStateOf<Long?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }

    // 眼動凝視自動觸發 dwell
    LaunchedEffect(gazeWithin) {
        if (gazeWithin) dwellStart = System.currentTimeMillis()
        else if (dwellStart != null) { dwellStart = null; progress = 0f }
    }

    // 滾動填進度，達標即觸發
    LaunchedEffect(dwellStart) {
        val start = dwellStart ?: return@LaunchedEffect
        while (dwellStart == start) {
            val elapsed = System.currentTimeMillis() - start
            progress = (elapsed / dwellMillis.toFloat()).coerceIn(0f, 1f)
            if (progress >= 1f) {
                onTriggered()
                dwellStart = null
                progress = 0f
                break
            }
            delay(16)
        }
    }

    val scale by animateFloatAsState(if (gazeWithin) 1.08f else 1f, label = "snap")

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(if (gazeWithin) palette.neonCyan.copy(alpha = 0.12f) else Color.Transparent)
            .border(
                if (gazeWithin) 2.dp else 0.dp,
                if (gazeWithin) palette.neonCyan else Color.Transparent,
                RoundedCornerShape(cornerRadius.dp)
            )
            .pointerInput(enableTouchTap) {
                if (!enableTouchTap) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent()
                        val firstDown = down.changes.firstOrNull { it.pressed } ?: continue
                        dwellStart = System.currentTimeMillis()
                        // 等待釋放或取消
                        try {
                            var released = false
                            while (!released) {
                                val ev = awaitPointerEvent()
                                if (ev.changes.none { it.pressed }) {
                                    val elapsed = System.currentTimeMillis() - (dwellStart ?: 0L)
                                    if (elapsed < 250L) {
                                        // 視為「單擊」：立即觸發
                                        dwellStart = null
                                        progress = 0f
                                        onTriggered()
                                    } else {
                                        // 鬆開（dwell 未滿）→ 取消
                                        dwellStart = null
                                        progress = 0f
                                    }
                                    released = true
                                }
                            }
                        } catch (_: Throwable) {
                            dwellStart = null
                            progress = 0f
                        }
                    }
                }
            }
    ) {
        content()
        if (progress > 0.02f) {
            CircularProgressIndicator(
                progress = { progress },
                color = palette.neonCyan,
                strokeWidth = 5.dp,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(28.dp)
            )
        }
    }
}
