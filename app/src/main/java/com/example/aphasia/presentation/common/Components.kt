package com.example.aphasia.presentation.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalAppPalette

/**
 * 錄音中的音量環。
 *
 * 這不是裝飾。失語症使用者按著按鈕卻沒被收音時（麥克風被佔用、權限被撤、
 * 手指擋住收音孔），沒有這個環就只能等到「比對不到」才發現，
 * 而那時他已經以為自己說錯了。環會動＝機器真的聽得到。
 */
@Composable
fun AmplitudeRing(
    amplitude: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
    ringColor: Color? = null,
    /** 環的直徑。預設給大按鈕用；小按鈕要自己縮小，否則環會蓋到旁邊的文字。 */
    ringSize: Int = 200,
    content: @Composable () -> Unit,
) {
    val palette = LocalAppPalette.current
    val ring = ringColor ?: palette.neonCyan
    val scale by animateFloatAsState(
        targetValue = if (active) 1f + amplitude.coerceIn(0f, 1f) * 0.35f else 1f,
        label = "amplitude",
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (active) {
            Box(
                Modifier
                    .size(ringSize.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(ring.copy(alpha = 0.18f)),
            )
        }
        content()
    }
}

/** 一個碎詞。點 × 可以刪掉——選錯了不該只能整組清空重來。 */
@Composable
fun FragmentChip(text: String, onRemove: () -> Unit) {
    val palette = LocalAppPalette.current
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = palette.neonCyan.copy(alpha = 0.15f),
        border = null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                color = palette.textLight,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove $text",
                    tint = palette.textMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * 大尺寸的方形選項卡。給意圖樹用。
 *
 * 最小 96dp：Material 的建議是 48dp，但這裡的使用者常有偏癱或動作控制困難，
 * 單手操作、手部顫抖是常態，48dp 的目標會一直點錯。
 */
@Composable
fun IntentTile(
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalAppPalette.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = palette.surface,
        modifier = modifier
            .height(96.dp)
            .border(2.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                color = palette.textLight,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

/** 提示／錯誤列。可關掉，不會自己消失——使用者需要時間讀完。 */
@Composable
fun MessageBar(message: String, onDismiss: () -> Unit) {
    val palette = LocalAppPalette.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = palette.auraAmber.copy(alpha = 0.15f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                message,
                color = palette.textLight,
                fontSize = 16.sp,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = palette.textMuted)
            }
        }
    }
}

@Composable
fun SectionHeader(text: String, color: Color? = null) {
    val resolved = color ?: LocalAppPalette.current.textMuted
    Column {
        Spacer(Modifier.height(8.dp))
        Text(text, color = resolved, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
fun HSpace(dp: Int) = Spacer(Modifier.width(dp.dp))

@Composable
fun VSpace(dp: Int) = Spacer(Modifier.height(dp.dp))
