package com.example.ui.settings

import com.example.ui.I18n

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AacItem
import com.example.data.AppSettings
import com.example.data.CorpusImporter
import com.example.data.SettingsRepository
import com.example.ui.theme.LocalAppPalette
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtendedSettingsScreen(
    settingsRepo: SettingsRepository,
    onClearAllHistory: suspend () -> Unit,
    onImportCorpus: suspend (List<AacItem>) -> Unit,
    onUseCurrentGpsAsHome: suspend () -> Triple<String, Double, Double>? = { null },
    embedded: Boolean = false,
    modifier: Modifier = Modifier
) {
    val p = LocalAppPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val settings by settingsRepo.settings.collectAsState(initial = AppSettings())
    var chatIdDraft by remember(settings.emergencyChatId) { mutableStateOf(settings.emergencyChatId) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var importStatus by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            scope.launch {
                when (val r = CorpusImporter.importFromUri(ctx, uri)) {
                    is CorpusImporter.Result.Ok -> { onImportCorpus(r.items); importStatus = I18n.tr("✅ 已匯入 %d 筆語料").format(r.items.size) }
                    is CorpusImporter.Result.Err -> { importStatus = "❌ ${r.reason}" }
                }
            }
        }
    }

    val content: @Composable () -> Unit = {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .background(p.darkBg),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 無障礙與提醒 ──
            item {
                Section(title = I18n.tr("無障礙與提醒"), icon = Icons.Default.Accessibility, p = p) {
                    RowToggle(I18n.tr("高對比模式"), I18n.tr("純黑底 + 高彩文字，視力弱者用"),
                        settings.highContrast, p) { scope.launch { settingsRepo.setHighContrast(it) } }
                    Spacer(Modifier.height(10.dp))
                    SliderRow(I18n.tr("超大字"), "%.1fx", settings.fontScale, 0.8f..1.8f, p) {
                        scope.launch { settingsRepo.setFontScale(it) }
                    }
                    Spacer(Modifier.height(10.dp))
                    SliderRow(I18n.tr("鼻控靈敏度"), "%.1fx", settings.noseSensitivity, 0.5f..2.0f, p,
                        thumbColor = p.neonGreen) {
                        scope.launch { settingsRepo.setNoseSensitivity(it) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(I18n.tr("靈敏度即時生效，數值越大頭轉一點點游標移越多。"),
                        fontSize = 13.sp, color = p.textMuted, lineHeight = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    RowToggle(I18n.tr("每日復健提醒"), I18n.tr("每天固定時間通知來練習"),
                        settings.dailyReminderEnabled, p) { on ->
                        scope.launch {
                            settingsRepo.setDailyReminder(on, settings.dailyReminderHour)
                            com.example.work.RehabScheduler.ensureChannel(ctx)
                            com.example.work.RehabScheduler.scheduleDailyReminder(ctx, on, settings.dailyReminderHour)
                        }
                    }
                    if (settings.dailyReminderEnabled) {
                        SliderRow(I18n.tr("提醒時間"), "%.0f:00", settings.dailyReminderHour.toFloat(),
                            0f..23f, p, steps = 22) { h ->
                            scope.launch {
                                settingsRepo.setDailyReminder(true, h.toInt())
                                com.example.work.RehabScheduler.scheduleDailyReminder(ctx, true, h.toInt())
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(I18n.tr("成績單自動推送家屬"), color = p.textLight, fontSize = 17.sp)
                    Text(I18n.tr("定期把成績單發到緊急聯絡人 Telegram"), fontSize = 13.sp, color = p.textMuted)
                    Spacer(Modifier.height(8.dp))
                    // iOS segmented control 風：整排等寬、緊湊、選中才上色
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("none" to I18n.tr("關閉"), "daily" to I18n.tr("每日"), "weekly" to I18n.tr("每週")).forEach { (key, label) ->
                            val sel = settings.reportPushFrequency == key
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        settingsRepo.setReportPushFrequency(key)
                                        com.example.work.RehabScheduler.scheduleReportPush(ctx, key)
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                border = BorderStroke(1.dp, if (sel) p.neonCyan else p.textMuted.copy(alpha = 0.35f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = if (sel) p.neonCyan else p.textMuted),
                                modifier = Modifier.weight(1f).heightIn(min = 34.dp)
                            ) { Text(label, fontSize = 13.sp, maxLines = 1) }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(I18n.tr("需先填下方「緊急聯絡人 Chat ID」。"), fontSize = 12.sp, color = p.textMuted)
                }
            }

            // ── 自訂 AAC 圖卡 ──
            item {
                Section(title = I18n.tr("自訂 AAC 圖卡"), icon = Icons.Default.AddCard, p = p) {
                    val existing = remember(settings.customAacItems) {
                        runCatching { org.json.JSONArray(settings.customAacItems.ifBlank { "[]" }) }
                            .getOrDefault(org.json.JSONArray())
                    }
                    var newLabel by remember { mutableStateOf("") }
                    var newEmoji by remember { mutableStateOf("⭐") }
                    Text(I18n.tr("已新增 %d 張自訂圖卡（顯示在 AAC 板）").format(existing.length()),
                        fontSize = 11.sp, color = p.textMuted)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newEmoji, onValueChange = { newEmoji = it.take(2) },
                            label = { Text(I18n.tr("圖示")) }, singleLine = true,
                            modifier = Modifier.width(80.dp),
                            colors = fieldColors(p)
                        )
                        OutlinedTextField(
                            value = newLabel, onValueChange = { newLabel = it.take(10) },
                            label = { Text(I18n.tr("詞語")) }, singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = fieldColors(p)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = {
                            if (newLabel.isBlank()) return@Button
                            val arr = org.json.JSONArray(settings.customAacItems.ifBlank { "[]" })
                            arr.put(org.json.JSONObject().put("label", newLabel.trim()).put("emoji", newEmoji.ifBlank { "⭐" }))
                            scope.launch { settingsRepo.setCustomAacItems(arr.toString()) }
                            newLabel = ""; newEmoji = "⭐"
                        },
                        enabled = newLabel.isNotBlank(),
                        shape = BTN_SHAPE,
                        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF), contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().heightIn(min = BTN_MIN_H.dp)
                    ) { Text(I18n.tr("新增圖卡"), fontSize = 15.sp, fontWeight = FontWeight.Medium) }
                    if (existing.length() > 0) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = { scope.launch { settingsRepo.setCustomAacItems("") } }) {
                            Text(I18n.tr("清除全部自訂圖卡"), fontSize = 14.sp, color = Color(0xFFFF3B30))
                        }
                    }
                }
            }

            // ── 無線連結電腦 ──
            item {
                Section(title = I18n.tr("無線連結電腦（掃 QR Code）"), icon = Icons.Default.Wifi, p = p) {
                    var showQr by remember { mutableStateOf(false) }
                    var scanStatus by remember { mutableStateOf<String?>(null) }
                    Text(I18n.tr("電腦啟動語音中心後，點「顯示 QR Code」按鈕，再用下方「掃描」對準即可自動填入 IP。"),
                        fontSize = 13.sp, color = p.textMuted, lineHeight = 15.sp)
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { showQr = true; scanStatus = null },
                        shape = BTN_SHAPE,
                        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF), contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().heightIn(min = BTN_MIN_H.dp)
                    ) {
                        Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(I18n.tr("掃描電腦 QR Code"), fontWeight = FontWeight.Bold)
                    }
                    scanStatus?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, fontSize = 12.sp,
                            color = if (it.startsWith("✅")) p.neonGreen
                                    else if (it.startsWith("❌")) p.dangerRose
                                    else p.textMuted)
                    }
                    if (settings.gptSovitApiBaseUrl.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(I18n.tr("已設定：%s").format(settings.gptSovitApiBaseUrl), fontSize = 10.sp, color = p.textMuted)
                    }
                    if (showQr) {
                        com.example.ui.QrScanDialog(
                            onResult = { url ->
                                showQr = false
                                scope.launch {
                                    settingsRepo.setGptSovitApiBaseUrl(url)
                                    scanStatus = I18n.tr("✅ 已套用 %s").format(url)
                                }
                            },
                            onDismiss = {
                                showQr = false
                                if (scanStatus == null) scanStatus = I18n.tr("已取消")
                            }
                        )
                    }
                }
            }

            // ── 緊急聯絡人 ──
            item {
                Section(title = I18n.tr("緊急聯絡人 · Telegram Chat ID"),
                    icon = Icons.Default.ContactPhone, p = p) {
                    OutlinedTextField(
                        value = chatIdDraft,
                        onValueChange = { chatIdDraft = it.filter { c -> c.isDigit() || c == '-' }.take(16) },
                        label = { Text("Chat ID") },
                        supportingText = {
                            Text(I18n.tr("預設值：%s（Tim Chien 測試帳號）").format(AppSettings.DEFAULT_EMERGENCY_CHAT_ID),
                                fontSize = 11.sp, color = p.textMuted)
                        },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Phone),
                        colors = fieldColors(p),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { scope.launch { settingsRepo.setEmergencyChatId(chatIdDraft) } },
                            enabled = chatIdDraft.isNotBlank() && chatIdDraft != settings.emergencyChatId,
                            shape = BTN_SHAPE,
                            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF), contentColor = Color.White),
                            modifier = Modifier.weight(1f).heightIn(min = BTN_MIN_H.dp)
                        ) { Text(I18n.tr("儲存"), fontSize = 15.sp, fontWeight = FontWeight.Medium) }
                        OutlinedButton(
                            onClick = {
                                chatIdDraft = AppSettings.DEFAULT_EMERGENCY_CHAT_ID
                                scope.launch { settingsRepo.setEmergencyChatId(AppSettings.DEFAULT_EMERGENCY_CHAT_ID) }
                            },
                            shape = BTN_SHAPE,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = p.textLight),
                            border = BorderStroke(1.dp, p.textMuted.copy(alpha = 0.4f)),
                            modifier = Modifier.weight(1f).heightIn(min = BTN_MIN_H.dp)
                        ) { Text(I18n.tr("回預設值"), fontSize = 15.sp) }
                    }
                }
            }

            // ── 歷史紀錄管理 ──
            item {
                Section(title = I18n.tr("歷史紀錄管理"), icon = Icons.Default.History, p = p) {
                    Text(I18n.tr("刪除所有重組歷史、復健紀錄、AAC 組合紀錄、故事訓練紀錄。DataStore 設定（風格、語言等）不會被清除。"),
                        color = p.textMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { showClearDialog = true },
                        shape = BTN_SHAPE,
                        border = BorderStroke(1.dp, Color(0xFFFF3B30).copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF3B30)),
                        modifier = Modifier.fillMaxWidth().heightIn(min = BTN_MIN_H.dp)
                    ) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(I18n.tr("清除所有歷史紀錄"), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // ── 一鍵重置 ──
            item {
                Section(title = I18n.tr("一鍵重置"), icon = Icons.Default.RestartAlt, p = p) {
                    Text(I18n.tr("將所有 App 設定（主題、語言、TTS 速率、Chat ID 等）回到出廠預設。**不會**刪除歷史紀錄。"),
                        color = p.textMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { showResetDialog = true },
                        shape = BTN_SHAPE,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF3B30)),
                        border = BorderStroke(1.dp, Color(0xFFFF3B30).copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth().heightIn(min = BTN_MIN_H.dp)
                    ) {
                        Icon(Icons.Default.RestartAlt, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(I18n.tr("重置為預設設定"), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // ── 匯入外部語料庫 ──
            item {
                Section(title = I18n.tr("匯入外部語料庫（JSON / CSV）"), icon = Icons.Default.UploadFile, p = p) {
                    Text(I18n.tr("支援 .json（含完整欄位）或 .csv（家屬可用 Excel 編輯）。範例格式請見專案 README。"),
                        color = p.textMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            importLauncher.launch(arrayOf("application/json", "text/csv",
                                "text/comma-separated-values", "*/*"))
                        },
                        shape = BTN_SHAPE,
                        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF), contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().heightIn(min = BTN_MIN_H.dp)
                    ) {
                        Icon(Icons.Default.UploadFile, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(I18n.tr("選擇檔案"), fontWeight = FontWeight.Bold)
                    }
                    importStatus?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = p.textMuted, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    if (embedded) {
        content()
    } else {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            containerColor = p.darkBg,
            topBar = {
                TopAppBar(
                    title = { Text(I18n.tr("▌ 進階設定"), color = p.textLight, fontSize = 17.sp, fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = p.darkBg)
                )
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().background(p.darkBg).padding(padding)) {
                content()
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(I18n.tr("確認刪除所有歷史紀錄？")) },
            text = { Text(I18n.tr("這個動作無法復原。設定值（主題、Chat ID 等）會被保留。")) },
            confirmButton = {
                TextButton(onClick = { showClearDialog = false; scope.launch { onClearAllHistory() } }) {
                    Text(I18n.tr("確認刪除"), color = p.dangerRose)
                }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text(I18n.tr("取消")) } }
        )
    }
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(I18n.tr("確認重置所有設定？")) },
            text = { Text(I18n.tr("主題會回到「科技風」、Chat ID 會回到 %s。").format(AppSettings.DEFAULT_EMERGENCY_CHAT_ID)) },
            confirmButton = {
                TextButton(onClick = { showResetDialog = false; scope.launch { settingsRepo.resetAll() } }) {
                    Text(I18n.tr("確認重置"), color = p.auraAmber)
                }
            },
            dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text(I18n.tr("取消")) } }
        )
    }
}

// ── 私用 helper composables ──

// ─── 統一按鈕視覺 ───
// 這頁原本混了 6 種按鈕樣式（青色實心／綠色實心／紅色實心／黃框／灰底／白框），
// 高度、圓角、字級全都不一樣。統一成三種語意：主要動作、次要動作、破壞性動作。
private val BTN_SHAPE = RoundedCornerShape(10.dp)
private const val BTN_MIN_H = 44

/** 主要動作：實心藍（iOS 系統藍）。 */
@Composable
private fun PrimaryBtn(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = BTN_SHAPE,
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF), contentColor = Color.White),
        modifier = modifier.heightIn(min = BTN_MIN_H.dp)
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
}

/** 次要動作：灰框，不搶主要動作的視覺。 */
@Composable
private fun SecondaryBtn(
    text: String,
    p: com.example.ui.theme.AppPalette,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = BTN_SHAPE,
        border = BorderStroke(1.dp, p.textMuted.copy(alpha = 0.4f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = p.textLight),
        modifier = modifier.heightIn(min = BTN_MIN_H.dp)
    ) { Text(text, fontSize = 15.sp, maxLines = 1) }
}

/** 破壞性動作：紅字紅框（iOS 慣例，不用整片紅底嚇人）。 */
@Composable
private fun DestructiveBtn(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        shape = BTN_SHAPE,
        border = BorderStroke(1.dp, Color(0xFFFF3B30).copy(alpha = 0.5f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF3B30)),
        modifier = modifier.heightIn(min = BTN_MIN_H.dp)
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
}

// ─── iOS inset-grouped 樣式（與設定主頁一致）───
// 統一在這裡定字級，不要讓任何 Text 落到「沒指定 fontSize」——那會吃到
// 預設 bodyLarge，再乘上使用者的字體倍率（可到 1.8x），標題就會腫成兩倍大。

@Composable
private fun Section(
    title: String,
    icon: ImageVector,
    p: com.example.ui.theme.AppPalette,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 分組標題在卡片外（iOS 風：小灰字、非粗體）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp)
        ) {
            Icon(icon, null, tint = p.textMuted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(title, color = p.textMuted, fontSize = 13.sp)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(p.surface)
                .padding(horizontal = 14.dp, vertical = 4.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun RowToggle(
    label: String,
    sub: String,
    checked: Boolean,
    p: com.example.ui.theme.AppPalette,
    onChecked: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = p.textLight, fontSize = 17.sp)
            if (sub.isNotBlank()) {
                Text(sub, fontSize = 13.sp, color = p.textMuted, lineHeight = 17.sp)
            }
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked, onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF34C759),
                checkedBorderColor = Color(0xFF34C759),
                uncheckedThumbColor = Color.White
            )
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    fmt: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    p: com.example.ui.theme.AppPalette,
    steps: Int = 0,
    thumbColor: Color = p.neonCyan,
    onValue: (Float) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Text(label, color = p.textMuted, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Slider(
            value = value, onValueChange = onValue,
            valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = thumbColor),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(fmt.format(value), color = p.textMuted, fontSize = 13.sp)
    }
}

@Composable
private fun fieldColors(p: com.example.ui.theme.AppPalette) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = p.textLight,
    unfocusedTextColor = p.textLight,
    focusedBorderColor = p.neonCyan,
    unfocusedBorderColor = p.neonCyan.copy(alpha = 0.4f),
    focusedLabelColor = p.neonCyan,
    unfocusedLabelColor = p.textMuted
)
