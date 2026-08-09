package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.VoiceWeaverViewModel
import com.example.ui.theme.AppPalette

/**
 * 常駐求救列。
 *
 * 釘在所有分頁之上，因為需要它的時候人不一定停在主畫面——而「先切回主畫面
 * 再找按鈕」在那個當下是做不到的事。與網頁版標題列的 🆘 求救 同一個位置、
 * 同一套行為。
 *
 * 按下去**會真的通知家人**（Telegram ＋ 目前位置）並開啟與家人的對話視窗，
 * 所以中間隔一道確認。用二次確認而不是長按：長按對手抖、肌張力異常的使用者
 * 本身就是障礙，那正是這個 App 的使用者。
 */
@Composable
fun SosBar(viewModel: VoiceWeaverViewModel, p: AppPalette) {
    var confirming by remember { mutableStateOf(false) }
    val result by viewModel.sosResult.collectAsState()
    // 演示要拍到這顆鈕，所以**不隱藏**。演示只做視覺按壓、不會真的觸發它；
    // 而且就算真的被點到，中間還隔著一道確認才會送出去。
    val demoPressed by viewModel.demoPressed.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = { confirming = true },
            colors = ButtonDefaults.buttonColors(containerColor = p.dangerRose),
            shape = RoundedCornerShape(20.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            // 演示：被「按」到時縮一下，影片才看得出這一步是按了這顆鍵
            modifier = Modifier.scale(if (demoPressed == "sos") 0.92f else 1f),
        ) {
            Text(I18n.tr("🆘 求救"), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            icon = { Icon(Icons.Default.Warning, null, tint = p.dangerRose) },
            title = { Text(I18n.tr("要通知家人嗎？"), color = p.textLight) },
            text = {
                Text(
                    I18n.tr("會立刻傳訊息給你的緊急聯絡人，並附上你目前的位置，然後開啟與家人的對話視窗。"),
                    color = p.textMuted, fontSize = 14.sp, lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { confirming = false; viewModel.triggerUserSos() },
                    colors = ButtonDefaults.buttonColors(containerColor = p.dangerRose)
                ) {
                    Text(I18n.tr("通知家人"), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(I18n.tr("取消"), color = p.textMuted)
                }
            },
            containerColor = p.surface
        )
    }

    // 送出結果一定要給回饋。沒有的話使用者按了鈕、什麼都沒發生，
    // 他不會知道到底通報出去了沒有——而這正是最不能含糊的一件事。
    result?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissSosResult() },
            title = { Text(I18n.tr("求救"), color = p.textLight) },
            text = { Text(msg, color = p.textMuted, fontSize = 15.sp, lineHeight = 21.sp) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissSosResult() }) {
                    Text(I18n.tr("我知道了"), color = p.textLight)
                }
            },
            containerColor = p.surface
        )
    }
}
