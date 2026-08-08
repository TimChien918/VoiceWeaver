package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.demo.DemoController

/**
 * 演示挑選面板。
 *
 * 面板本身跟著介面語言走（中／英／日／韓）；影片裡的旁白與字幕一律英文，
 * 因為這支影片是要拿去做報告的。單元名稱也保持英文——那是影片的段落名稱，
 * 會出現在畫面上的進度列裡，跟旁白同一種語言才不會一句中文一句英文。
 */
@Composable
fun DemoPickerDialog(
    /** 電腦端 GPT-SoVITS 有沒有開起來。沒有的話旁白就不是使用者自己的聲音。 */
    voiceReady: Boolean = true,
    onDismiss: () -> Unit,
    onStart: (ids: Set<String>, narrate: Boolean, record: Boolean, audio: Boolean) -> Unit,
) {
    val checked = remember { DemoController.UNITS.map { it.id }.toMutableStateList() }
    var narrate by remember { mutableStateOf(true) }
    var record by remember { mutableStateOf(true) }
    var audio by remember { mutableStateOf(true) }

    // 估時直接從步數算（含每一步的最短停留），不用手寫值——手寫的一定會跟實際節奏漂移，
    // 而使用者是靠這個數字決定要不要錄的。
    val estSec = DemoController.estimateSeconds(checked.toSet(), narrate)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFF1B1B1F), tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f)) {
            // 整個面板一個捲軸。之前清單自己再包一層 verticalScroll + weight(fill=false)，
            // 在沒有固定高度的 Dialog 裡算出來的剩餘空間幾乎是零——十五個單元只看得到一個。
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState())) {
                Text("🎬  " + I18n.tr("自動演示與螢幕錄影"), color = Color.White,
                    fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(6.dp))
                Text(
                    I18n.tr("自動走過主要功能並錄影。全程不寫入資料、不送出任何訊息——危機介入與求救只是講解，不會真的觸發。"),
                    color = Color(0xFFB9B9C2), fontSize = 12.sp
                )
                Spacer(Modifier.size(10.dp))
                Row {
                    TextButton(onClick = {
                        checked.clear(); checked.addAll(DemoController.UNITS.map { it.id })
                    }) { Text(I18n.tr("全選"), fontSize = 13.sp) }
                    TextButton(onClick = { checked.clear() }) { Text(I18n.tr("清除"), fontSize = 13.sp) }
                    Spacer(Modifier.weight(1f))
                    Text("${checked.size} ${I18n.tr("個單元")} · ~${estSec / 60}m ${estSec % 60}s",
                        color = Color(0xFFB9B9C2), fontSize = 12.sp,
                        modifier = Modifier.padding(top = 14.dp))
                }
                Column {
                    DemoController.UNITS.forEach { u ->
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                            Checkbox(
                                checked = u.id in checked,
                                onCheckedChange = { on -> if (on) checked.add(u.id) else checked.remove(u.id) }
                            )
                            Text("${u.icon}  ", fontSize = 15.sp)
                            Column {
                                Text(u.title, color = Color.White, fontSize = 14.sp)
                                Text("~${u.estSec}s" + (u.note?.let { " · $it" } ?: ""),
                                    color = Color(0xFF9A9AA4), fontSize = 11.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.size(8.dp))
                ToggleLine(I18n.tr("英文旁白與字幕"), narrate) { narrate = it }
                if (narrate) {
                    Text(
                        I18n.tr("每一句都在開始錄影前先合成好，影片裡不會有等待。電腦用 CPU 跑 GPT-SoVITS 時，第一次每句約 10～30 秒；合成過的會留著，第二次幾乎不用等。"),
                        color = Color(0xFF9A9AA4), fontSize = 11.sp,
                        modifier = Modifier.padding(start = 44.dp, bottom = 4.dp)
                    )
                }
                if (narrate && !voiceReady) {
                    // 默默用系統機器音唸完一整支影片，使用者只會在錄完之後才發現。
                    Text(
                        "⚠️  " + I18n.tr("電腦端語音（GPT-SoVITS）沒有啟用，旁白會用手機的系統語音唸。要用你自己的角色語音，請先到設定把電腦端語音打開並連上。"),
                        color = Color(0xFFFFB020), fontSize = 11.sp,
                        modifier = Modifier.padding(start = 44.dp, bottom = 6.dp)
                    )
                }
                ToggleLine(I18n.tr("把畫面錄成影片"), record) { record = it }
                if (record) {
                    ToggleLine(I18n.tr("把旁白一起錄進影片（同時產生一份無旁白版）"), audio) { audio = it }
                    Text(
                        I18n.tr("Android 規定 App 錄自己的畫面要你同意，會跳出系統視窗。旁白是直接取自 App 自己的音訊，不經過麥克風，所以不會錄到房間裡的聲音。影片會存到 Movies/VoiceWeaver，在相簿裡看得到。"),
                        color = Color(0xFF9A9AA4), fontSize = 11.sp,
                        modifier = Modifier.padding(start = 44.dp, bottom = 4.dp)
                    )
                }
                Spacer(Modifier.size(10.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text(I18n.tr("取消")) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = checked.isNotEmpty(),
                        onClick = { onStart(checked.toSet(), narrate, record, audio) }
                    ) { Text("▶  " + I18n.tr("開始")) }
                }
            }
        }
    }
}

@Composable
private fun ToggleLine(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked = value, onCheckedChange = onChange)
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}

/**
 * 演示覆蓋層：字幕、進度、停止鈕、片頭／片尾卡、旁白預備進度。
 *
 * 這一層**不擋觸控**（除了停止鈕），演示是自己跑的，不該把畫面鎖住。
 */
@Composable
fun DemoOverlay(onStop: () -> Unit) {
    val active by DemoController.active.collectAsState()
    if (!active) return
    val caption by DemoController.caption.collectAsState()
    val progress by DemoController.progress.collectAsState()
    val card by DemoController.titleCard.collectAsState()
    val prep by DemoController.prep.collectAsState()

    Box(Modifier.fillMaxSize()) {
        card?.let { c ->
            Column(
                Modifier.fillMaxSize()
                    .background(Brush.linearGradient(listOf(Color(0xFF0A84FF), Color(0xFF0050A0)))),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(c.main, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.size(10.dp))
                Text(c.sub, color = Color(0xE6FFFFFF), fontSize = 15.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp))
            }
        }

        prep?.let { (i, n) ->
            Column(
                Modifier.fillMaxSize().background(Color(0xDD000000)),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(I18n.tr("準備旁白中…"), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(12.dp))
                LinearProgressIndicator(
                    progress = { if (n == 0) 1f else i.toFloat() / n },
                    modifier = Modifier.fillMaxWidth(0.7f)
                )
                Spacer(Modifier.size(8.dp))
                Text("$i / $n ${I18n.tr("句")}", color = Color(0xFFB9B9C2), fontSize = 12.sp)
                Spacer(Modifier.size(14.dp))
                TextButton(onClick = onStop) { Text(I18n.tr("停止")) }
            }
        }

        // 進度＋停止：**錄影時整個不顯示**。
        // 它們是給操作者看的，不是影片內容；錄進去就是一顆紅色 Stop 和
        // 「56/71」壓在 App 標題上，拿去做報告很難看。
        // 錄影中要中止改按實體返回鍵（見 MainActivity）。
        val recording by com.example.demo.ScreenRecordService.recording.collectAsState()
        if (!recording) Row(
            Modifier.align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (progress.isNotBlank()) {
                Surface(shape = RoundedCornerShape(999.dp), color = Color(0xB3000000)) {
                    Text(progress, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }
            Spacer(Modifier.width(6.dp))
            Surface(shape = RoundedCornerShape(999.dp), color = Color(0xFFFF3B30)) {
                Text("■ Stop", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .clickable(onClick = onStop))
            }
        }

        // 字幕的字級**固定**，不跟著 App 的字級設定跑。
        // 演示裡有一段就是在示範字級從 1.0 拉到 2.2 倍，字幕跟著一起放大縮小的話
        // 會忽大忽小、還會撐破自己的底框——那是影片的字幕，不是 App 的介面。
        // 同理，鎖定畫面把畫面壓暗時字幕也不該跟著變暗（見下面自己畫的遮罩）。
        if (caption.isNotBlank()) androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                density = LocalDensity.current.density, fontScale = 1f
            )
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp), color = Color(0xD1000000),
                modifier = Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 14.dp, vertical = 18.dp)
            ) {
                Text(caption, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, lineHeight = 22.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    }
}
