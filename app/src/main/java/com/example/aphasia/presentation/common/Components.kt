package com.example.aphasia.presentation.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
fun VSpace(dp: Int) = Spacer(Modifier.height(dp.dp))
