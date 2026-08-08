package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.VoiceWeaverViewModel
import com.example.data.AacItem
import com.example.ui.theme.AppPalette

/**
 * 發聲風險閘門對話框（醫師臨床建議：語意安全閥風險分級）。
 * 掛在任何可能發聲的畫面 root：
 *  - NeedConfirm（醫療/疼痛）→ 確認對話框，按「確認朗讀」才發聲
 *  - Locked（自傷/拒絕治療）→ 強制鎖定語音輸出，僅顯示警示
 */
@Composable
fun SpeakGateDialogs(viewModel: VoiceWeaverViewModel, p: AppPalette) {
    val gate by viewModel.speakGate.collectAsState()
    when (val g = gate) {
        is VoiceWeaverViewModel.SpeakGate.NeedConfirm -> AlertDialog(
            onDismissRequest = { viewModel.dismissSpeakGate() },
            icon = { Icon(Icons.Default.Warning, null, tint = p.auraAmber) },
            title = { Text(I18n.tr("醫療相關內容，請確認"), color = p.textLight) },
            text = {
                Text(
                    I18n.tr("「%s」\n\n請患者或照護者確認句子正確（部位、意圖無誤）後再朗讀。").format(g.text),
                    color = p.textMuted, fontSize = 14.sp, lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmGatedSpeak() },
                    colors = ButtonDefaults.buttonColors(containerColor = p.neonGreen)
                ) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(I18n.tr("確認朗讀"), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissSpeakGate() }) {
                    Text(I18n.tr("取消"), color = p.textMuted)
                }
            },
            containerColor = p.surface
        )
        is VoiceWeaverViewModel.SpeakGate.Locked -> {
            // 演示進行中把對話框視窗的「壓暗」關掉。
            // 那層暗是整個視窗的 dim，會連字幕一起壓黑——而字幕是影片的一部分，
            // 必須維持可讀。App 畫面本身要不要看起來變暗，交給演示自己畫的遮罩處理。
            if (com.example.demo.DemoController.active.collectAsState().value) {
                val view = androidx.compose.ui.platform.LocalView.current
                androidx.compose.runtime.SideEffect {
                    ((view.parent as? androidx.compose.ui.window.DialogWindowProvider))
                        ?.window?.setDimAmount(0f)
                }
            }
            AlertDialog(
            onDismissRequest = { viewModel.dismissSpeakGate() },
            icon = { Icon(Icons.Default.Block, null, tint = p.dangerRose) },
            title = { Text(I18n.tr("語音輸出已鎖定"), color = p.dangerRose) },
            text = { Text(g.reason, color = p.textMuted, fontSize = 14.sp, lineHeight = 20.sp) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissSpeakGate() }) {
                    Text(I18n.tr("我知道了"), color = p.textLight)
                }
            },
            containerColor = p.surface
            )
        }
        else -> Unit
    }
}

/**
 * 動態 AAC 意圖確認卡：AI 組好句子後**不直接發聲**，先以大圖卡呈現
 * 「圖卡 emoji 序列 + 句子」讓不識字使用者確認：
 *  ✓ 朗讀（過風險閘門）／ 🔁 再猜一次 ／ ✗ 返回修改。
 *
 * @param bigCard 由設定「意圖確認卡」開關控制：開 = 全寬大卡（不識字適用）；
 *                關 = 精簡列（識字使用者快速確認），兩者都**不會**自動發聲。
 */
@Composable
fun AacPendingConfirmCard(
    pendingText: String,
    items: List<AacItem>,
    bigCard: Boolean,
    onAccept: () -> Unit,
    onRegenerate: () -> Unit,
    onDismiss: () -> Unit,
    p: AppPalette,
    /** 意圖卡 AI 專屬圖路徑（開啟「意圖卡 AI 生成專屬圖」時）；null = 不顯示。 */
    intentImagePath: String? = null,
    /** AI 圖生成中（顯示轉圈，不擋確認鈕）。 */
    intentImageLoading: Boolean = false
) {
    if (pendingText.isBlank()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(p.neonCyan.copy(alpha = 0.10f))
            .border(2.dp, p.neonCyan.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(if (bigCard) 16.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(if (bigCard) 12.dp else 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // AI 生成的「專屬圖卡」（不識字適用）：有圖顯示圖、生成中顯示轉圈
        if (bigCard && intentImagePath != null) {
            coil.compose.AsyncImage(
                model = java.io.File(intentImagePath),
                contentDescription = I18n.tr("意圖示意圖"),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        } else if (bigCard && intentImageLoading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = p.neonCyan, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(I18n.tr("正在生成專屬圖卡…"), fontSize = 12.sp, color = p.textMuted)
            }
        }
        if (bigCard && items.isNotEmpty()) {
            Text(
                items.joinToString(" ") { it.emoji },
                fontSize = 34.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        Text(
            pendingText,
            fontSize = if (bigCard) 22.sp else 14.sp,
            fontWeight = FontWeight.Bold,
            color = p.textLight,
            lineHeight = if (bigCard) 30.sp else 18.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            I18n.tr("是這個意思嗎？"),
            fontSize = if (bigCard) 14.sp else 11.sp,
            color = p.textMuted
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(containerColor = p.neonGreen),
                modifier = Modifier.weight(1.6f).height(if (bigCard) 56.dp else 40.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, null, modifier = Modifier.size(if (bigCard) 24.dp else 16.dp))
                Spacer(Modifier.width(6.dp))
                Text(I18n.tr("對，朗讀"), fontWeight = FontWeight.Bold, fontSize = if (bigCard) 17.sp else 12.sp)
            }
            OutlinedButton(
                onClick = onRegenerate,
                border = BorderStroke(1.dp, p.neonCyan.copy(alpha = 0.55f)),
                modifier = Modifier.weight(1f).height(if (bigCard) 56.dp else 40.dp)
            ) {
                Icon(Icons.Default.Refresh, null, tint = p.neonCyan, modifier = Modifier.size(if (bigCard) 20.dp else 14.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.tr("再猜"), color = p.neonCyan, fontSize = if (bigCard) 15.sp else 11.sp)
            }
            OutlinedButton(
                onClick = onDismiss,
                border = BorderStroke(1.dp, p.dangerRose.copy(alpha = 0.55f)),
                modifier = Modifier.weight(1f).height(if (bigCard) 56.dp else 40.dp)
            ) {
                Icon(Icons.Default.Close, null, tint = p.dangerRose, modifier = Modifier.size(if (bigCard) 20.dp else 14.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.tr("修改"), color = p.dangerRose, fontSize = if (bigCard) 15.sp else 11.sp)
            }
        }
    }
}
