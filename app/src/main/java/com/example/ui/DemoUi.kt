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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.demo.DemoController

/**
 * 演示挑選面板。全英文——錄出來的影片旁白與字幕都是英文，面板跟著英文才一致。
 */
@Composable
fun DemoPickerDialog(
    onDismiss: () -> Unit,
    onStart: (ids: Set<String>, narrate: Boolean, record: Boolean, audio: Boolean) -> Unit,
) {
    val checked = remember { DemoController.UNITS.map { it.id }.toMutableStateList() }
    var narrate by remember { mutableStateOf(true) }
    var record by remember { mutableStateOf(true) }
    var audio by remember { mutableStateOf(true) }

    val estSec = DemoController.UNITS.filter { it.id in checked }.sumOf { it.estSec }
        .let { if (narrate) (it * 1.8).toInt() else it }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFF1B1B1F), tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f)) {
            // 整個面板一個捲軸。之前清單自己再包一層 verticalScroll + weight(fill=false)，
            // 在沒有固定高度的 Dialog 裡算出來的剩餘空間幾乎是零——十五個單元只看得到一個。
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState())) {
                Text("🎬  Auto demo & screen recording", color = Color.White,
                    fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(6.dp))
                Text(
                    "Walks through the app on its own and records the screen. " +
                        "Nothing is written and no messages are sent — the crisis and SOS sections are described, not triggered.",
                    color = Color(0xFFB9B9C2), fontSize = 12.sp
                )
                Spacer(Modifier.size(10.dp))
                Row {
                    TextButton(onClick = {
                        checked.clear(); checked.addAll(DemoController.UNITS.map { it.id })
                    }) { Text("Select all", fontSize = 13.sp) }
                    TextButton(onClick = { checked.clear() }) { Text("Clear", fontSize = 13.sp) }
                    Spacer(Modifier.weight(1f))
                    Text("${checked.size} sections · ~${estSec / 60}m ${estSec % 60}s",
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
                ToggleLine("Narration + subtitles (English)", narrate) { narrate = it }
                if (narrate) {
                    Text(
                        "Every line is synthesised before recording starts, so the video has no waiting in it. " +
                            "With GPT-SoVITS on a computer CPU that takes roughly 10–30 s per line the first time; " +
                            "lines are cached, so a second run starts almost immediately.",
                        color = Color(0xFF9A9AA4), fontSize = 11.sp,
                        modifier = Modifier.padding(start = 44.dp, bottom = 4.dp)
                    )
                }
                ToggleLine("Record the screen to a video file", record) { record = it }
                if (record) {
                    ToggleLine("Record sound through the microphone", audio) { audio = it }
                    Text(
                        "Android only lets an app record its own screen with your permission — " +
                            "a system prompt will appear. Sound is picked up by the microphone from the speaker, " +
                            "so keep the room quiet.",
                        color = Color(0xFF9A9AA4), fontSize = 11.sp,
                        modifier = Modifier.padding(start = 44.dp, bottom = 4.dp)
                    )
                }
                Spacer(Modifier.size(10.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = checked.isNotEmpty(),
                        onClick = { onStart(checked.toSet(), narrate, record, audio) }
                    ) { Text("▶  Start") }
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
                Text("Preparing narration…", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(12.dp))
                LinearProgressIndicator(
                    progress = { if (n == 0) 1f else i.toFloat() / n },
                    modifier = Modifier.fillMaxWidth(0.7f)
                )
                Spacer(Modifier.size(8.dp))
                Text("$i / $n lines", color = Color(0xFFB9B9C2), fontSize = 12.sp)
                Spacer(Modifier.size(14.dp))
                TextButton(onClick = onStop) { Text("Stop") }
            }
        }

        // 進度＋停止：靠上，避開底部字幕
        Row(
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

        if (caption.isNotBlank()) {
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
