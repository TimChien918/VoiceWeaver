package com.example.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ai.VoiceWeaverViewModel

// 危機介入專用的療癒色盤（與主題無關，永遠柔和、安定）
private val CalmBg1 = Color(0xFF1B2A4A)
private val CalmBg2 = Color(0xFF24304F)
private val CalmBg3 = Color(0xFF142036)
private val CalmAccent = Color(0xFF7FB0E8)   // 柔藍
private val CalmWarm = Color(0xFFF4A6B0)      // 暖粉（家人）
private val CalmText = Color(0xFFEFF4FB)
private val CalmMuted = Color(0xFFA9B7CE)

/**
 * 危機介入「與家人對話」浮動視窗（病患端，優美療癒設計）。
 *
 * 出現時機：偵測到自傷類危機詞語。目的是**緩解情緒、陪伴**，不是冷冰冰的鎖定。
 * 內容：呼吸動畫、家人即時聊天、安撫快捷句、安心專線/119、錄製中提示。
 * 同時背景已在傳前後鏡頭畫面與即時音檔給家人（由 ViewModel 處理）。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CrisisConversationWindow(viewModel: VoiceWeaverViewModel) {
    val state by viewModel.crisisConv.collectAsState()
    if (!state.active) return
    val ctx = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val familyPhone = settings.emergencyPhoneNumber

    // 家人輸入 /call → forceJoinCall +1 → 強制使用者自動加入視訊房（網頁跳轉）
    LaunchedEffect(state.forceJoinCall) {
        if (state.forceJoinCall > 0 && state.videoCallUrl.isNotBlank()) {
            openUrl(ctx, state.videoCallUrl)
        }
    }

    Dialog(
        onDismissRequest = { /* 不可隨手關，需按下方「我好一點了」*/ },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
      // Dialog 開在新 Window，語言與字級都會被平台蓋回系統預設——見 ReapplyAppLocale。
      // 這個視窗是危機當下要讀的，字太小等於沒有。
      com.example.ui.ReapplyAppLocale {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 680.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.verticalGradient(listOf(CalmBg1, CalmBg2, CalmBg3)))
                .border(1.dp, CalmAccent.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 標題 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Favorite, null, tint = CalmWarm, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(I18n.tr("我們在這裡陪你"), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = CalmText)
            }
            Text(state.status.ifBlank { I18n.tr("正在聯繫家人…") }, fontSize = 12.sp, color = CalmAccent)

            // ── 即時視訊通話：用網頁跳轉到視訊房，與家人面對面（最顯眼）──
            if (state.videoCallUrl.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.horizontalGradient(listOf(Color(0xFF4C8DF6), Color(0xFF2BB0A6))))
                        .clickable { openUrl(ctx, state.videoCallUrl) }
                        .padding(vertical = 16.dp)
                ) {
                    Icon(Icons.Default.Videocam, null, tint = Color.White, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(I18n.tr("開始即時視訊通話"), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            // 備援：直接打電話給家人
            if (familyPhone.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF34C759).copy(alpha = 0.18f))
                        .border(1.dp, Color(0xFF34C759).copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                        .clickable { callFamily(ctx, familyPhone) }
                        .padding(vertical = 11.dp)
                ) {
                    Icon(Icons.Default.Call, null, tint = Color(0xFF6FE08A), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(I18n.tr("改用電話打給家人"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CalmText)
                }
            }

            // ── 呼吸動畫 ──
            BreathingOrb()

            // ── 狀態列 ──
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.videoCallUrl.isNotBlank()) StatusChip(Icons.Default.Videocam, I18n.tr("視訊連結已傳家人"), CalmAccent)
                if (state.photosSent > 0) StatusChip(Icons.Default.PhotoCamera, I18n.tr("畫面已傳家人"), CalmWarm)
            }

            // ── 家人對話區 ──
            val listState = rememberLazyListState()
            LaunchedEffect(state.messages.size) {
                if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 230.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.18f))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.messages) { m -> ChatBubble(m) }
            }

            // ── 安撫快捷句（送給家人）──
            Text(I18n.tr("想對家人說（點一下傳出）"), fontSize = 11.sp, color = CalmMuted)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf(I18n.tr("我需要陪伴"), I18n.tr("我很難過"), I18n.tr("我想聽你說話"), I18n.tr("我會撐住"), I18n.tr("請打給我")).forEach { phrase ->
                    SoftChip(phrase) { viewModel.sendCrisisMessage(phrase) }
                }
            }

            // ── 傳語音訊息給家人 ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CalmWarm.copy(alpha = if (state.recordingVoice) 0.30f else 0.16f))
                    .border(1.dp, CalmWarm.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .clickable(enabled = !state.recordingVoice) { viewModel.recordAndSendCrisisVoice() }
                    .padding(vertical = 11.dp)
            ) {
                Icon(Icons.Default.Mic, null, tint = CalmWarm, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (state.recordingVoice) I18n.tr("錄音中…（約 6 秒後自動傳出）") else I18n.tr("🎤 傳語音訊息給家人"),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CalmText
                )
            }

            // ── 安心專線 / 119 ──
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                HotlineButton(I18n.tr("1925 安心專線"), "1925", CalmAccent, Modifier.weight(1f), ctx)
                HotlineButton(I18n.tr("119 緊急"), "119", CalmWarm, Modifier.weight(1f), ctx)
            }

            // ── 結束由家人控制：使用者無法自行關閉，需家人在 Telegram 輸入 /end ──
            Text(
                I18n.tr("🔒 為了你的安全，這個畫面需要家人確認後才能關閉\n（家人在訊息輸入 /end 即可結束）"),
                fontSize = 11.sp, color = CalmText, textAlign = TextAlign.Center, lineHeight = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.18f))
                    .padding(vertical = 10.dp, horizontal = 8.dp)
            )
            Text(
                I18n.tr("畫面與聲音僅安全傳給家人，不會公開。家人可用 /call 視訊、/camera 重拍。"),
                fontSize = 9.sp, color = CalmMuted, textAlign = TextAlign.Center, lineHeight = 13.sp
            )
        }
      }
    }
}

@Composable
private fun BreathingOrb() {
    val t = rememberInfiniteTransition(label = "breath")
    val scale by t.animateFloat(
        initialValue = 0.7f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3800), RepeatMode.Reverse), label = "scale"
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .scale(scale)
                .clip(RoundedCornerShape(60.dp))
                .background(Brush.radialGradient(listOf(CalmAccent.copy(alpha = 0.55f), Color.Transparent)))
        )
        Text(if (scale > 0.85f) I18n.tr("吸氣…") else I18n.tr("吐氣…"), fontSize = 14.sp, color = CalmText, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StatusChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(tint.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 10.sp, color = CalmText, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ChatBubble(m: VoiceWeaverViewModel.CrisisChatMsg) {
    val isFamily = m.from == VoiceWeaverViewModel.CrisisSender.Family
    val isPatient = m.from == VoiceWeaverViewModel.CrisisSender.Patient
    val align = when {
        isPatient -> Alignment.End
        isFamily -> Alignment.Start
        else -> Alignment.CenterHorizontally
    }
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = align) {
        if (m.from == VoiceWeaverViewModel.CrisisSender.System) {
            Text(m.text, fontSize = 11.sp, color = CalmMuted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(), lineHeight = 15.sp)
        } else {
            if (isFamily) Text(I18n.tr("家人"), fontSize = 9.sp, color = CalmWarm, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
            Box(
                modifier = Modifier
                    .wrapContentWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isPatient) CalmAccent.copy(alpha = 0.28f) else CalmWarm.copy(alpha = 0.20f))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(m.text, fontSize = 13.sp, color = CalmText, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun SoftChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(CalmText.copy(alpha = 0.08f))
            .border(1.dp, CalmAccent.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(text, fontSize = 13.sp, color = CalmText)
    }
}

/** 用網頁跳轉開啟即時視訊通話房（瀏覽器/Jitsi）。 */
private fun openUrl(ctx: android.content.Context, url: String) {
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** 即時通話：優先用 ACTION_CALL 直撥（已授權時），否則退回撥號盤帶入號碼。 */
private fun callFamily(ctx: android.content.Context, number: String) {
    val tel = Uri.parse("tel:${number.filter { it.isDigit() || it == '+' }}")
    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
        ctx, android.Manifest.permission.CALL_PHONE
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    runCatching {
        val action = if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL
        ctx.startActivity(Intent(action, tel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, tel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

@Composable
private fun HotlineButton(label: String, number: String, tint: Color, modifier: Modifier, ctx: android.content.Context) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.18f))
            .border(1.dp, tint.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .clickable {
                runCatching {
                    ctx.startActivity(
                        Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            .padding(vertical = 12.dp)
    ) {
        Icon(Icons.Default.Call, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CalmText)
    }
}
