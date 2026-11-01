package com.speakmylife.app.ui.aac

import com.speakmylife.app.ui.I18n

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.speakmylife.app.data.AacItem
import com.speakmylife.app.ui.theme.AppPalette

/**
 * Combo Area — 把選中的圖卡序列以「圖文接龍」的方式橫向排出，最後接 1–5 星評分。
 *
 * 與舊版 (只能「買入」) 差異：
 *  - 這層只負責把 [items] 渲染出來，不做語意判斷。雙向交易（賣出 / 找零）由
 *    [com.speakmylife.app.ai.InferenceEngine.composeAacSentence] 在自然語句層處理；
 *    Combo Area 顯示原始符號（如「包葉檳榔 → NT$100 → 找零 →$50」），讓客人也看得懂。
 *
 * @param items 目前選中的圖卡序列
 * @param finalSentence AI 串好的自然句（先 null，等 [com.speakmylife.app.ai.InferenceEngine] 回傳）
 * @param rating 0 = 尚未評分；1–5 = 使用者打的星等
 * @param onRatingChanged 點某顆星時的 callback；用來寫回 Room
 * @param onRemove 點圖卡叉叉時的 callback
 */
@Composable
fun ComboArea(
    items: List<AacItem>,
    finalSentence: String?,
    rating: Int,
    onRatingChanged: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    palette: AppPalette,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(palette.surface)
            .border(1.dp, palette.neonCyan.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            I18n.tr("▌ 組合區（Combo Area）"),
            color = palette.neonCyan,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )

        if (items.isEmpty()) {
            Text(
                I18n.tr("尚未選擇圖卡。請從下方類別挑選想表達的內容（賣出商品、收款、找零都可以混搭）。"),
                color = palette.textMuted,
                fontSize = 12.sp
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items.size) { idx ->
                    ComboChip(
                        item = items[idx],
                        showArrow = idx != items.lastIndex,
                        onRemove = { onRemove(idx) },
                        palette = palette
                    )
                }
            }
        }

        if (!finalSentence.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.neonGreen.copy(alpha = 0.14f))
                    .border(1.dp, palette.neonGreen, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    finalSentence,
                    color = palette.textLight,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // 1–5 星評分。寫入 Room 後可作為下一次 AI 推薦的工作記憶權重。
        if (items.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    I18n.tr("這次有傳達到嗎？"),
                    color = palette.textMuted,
                    fontSize = 12.sp
                )
                Spacer(Modifier.width(8.dp))
                (1..5).forEach { star ->
                    val filled = star <= rating
                    Icon(
                        imageVector = if (filled) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = "rating $star",
                        tint = if (filled) palette.auraAmber else palette.textMuted,
                        modifier = Modifier
                            .size(28.dp)
                            .clickable { onRatingChanged(star) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ComboChip(
    item: AacItem,
    showArrow: Boolean,
    onRemove: () -> Unit,
    palette: AppPalette
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(palette.neonCyan.copy(alpha = 0.12f))
                .border(1.dp, palette.neonCyan, RoundedCornerShape(12.dp))
                .clickable(onClick = onRemove)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.emoji, fontSize = 18.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    com.speakmylife.app.data.AacI18n.label(item),
                    color = palette.textLight,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(6.dp))
                Text("×", color = palette.textMuted, fontSize = 14.sp)
            }
        }
        if (showArrow) {
            Spacer(Modifier.width(6.dp))
            Text("→", color = palette.neonCyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
        }
    }
}

/**
 * 強制圖形確認門 — Spec #5 要求：「發聲前畫面需彈出佔據半個螢幕的 🟢 [✔️ 接受] 與 🔴 [✖️ 重來]」。
 *
 * 用法：當 ViewModel 的組句完成（`composing = false` 且 `pendingSentence != null`），
 * 把這個 Composable 疊在 AacScreen 上（建議用 Box + zIndex 或者直接 Dialog window）。
 *
 * 兩顆按鈕都接眼動 dwell：[gazeOnAccept] / [gazeOnReject] 由 EyeControlManager 餵入。
 */
@Composable
fun ImageOnlyConfirmGate(
    sentenceForVisual: String,
    items: List<AacItem>,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    palette: AppPalette,
    modifier: Modifier = Modifier,
    gazeOnAccept: Boolean = false,
    gazeOnReject: Boolean = false
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.darkBg.copy(alpha = 0.96f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            I18n.tr("▌ 確認再唸出來"),
            color = palette.neonCyan,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        // 純圖卡序列（不顯示文字，避免不識字使用者讀不懂）
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items.size) { idx ->
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.surface)
                        .border(1.dp, palette.neonCyan, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(items[idx].emoji, fontSize = 40.sp)
                }
            }
        }
        // 半個螢幕的接受 / 重來
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GazeConfirmBlock(
                label = I18n.tr("接受"),
                emoji = "✔️",
                bg = palette.neonGreen,
                onTriggered = onAccept,
                palette = palette,
                gazeWithin = gazeOnAccept,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            GazeConfirmBlock(
                label = I18n.tr("重來"),
                emoji = "✖️",
                bg = palette.dangerRose,
                onTriggered = onReject,
                palette = palette,
                gazeWithin = gazeOnReject,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
        Text(
            I18n.tr("說明：AI 已組好「%s」。確認後才會唸出來。").format(sentenceForVisual),
            color = palette.textMuted,
            fontSize = 11.sp
        )
    }
}
