package com.example.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.AacItem
import com.example.data.AppSettings
import com.example.data.CorpusImporter
import com.example.data.SettingsRepository
import com.example.ui.theme.LocalAppPalette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Spec #1 要求的「完整設定頁面」：緊急聯絡 Chat ID（預設 8372934436）、
 * 一鍵刪歷史 / 重置、JSON/CSV 語料庫匯入。
 *
 * 設計選擇：
 *  - 這支**不取代** [com.example.ui.SettingsScreen]，而是「進階子頁」的形式存在。
 *    主設定頁負責主題 / 語言 / TTS 速率等 UX 設定；這支負責「資料治理」設定，
 *    分離有助於避免 LazyColumn 超過 20 個 item 後的滾動效能掉幀。
 *  - 重置按鈕**只清 DataStore**；不會清掉 Room 歷史。要清歷史請按各自的「清除」按鈕。
 *    把兩件事分開的原因：使用者通常想「換個風格」而不是「把復健成果都丟掉」。
 *
 * @param onClearAllHistory  ViewModel 端要 inject 一個 lambda，呼叫所有 DAO 的 clearAll()
 * @param onImportCorpus     收到 parse 完的 [List]<[AacItem]>，由 ViewModel 決定要 append 還是 replace
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtendedSettingsScreen(
    settingsRepo: SettingsRepository,
    onClearAllHistory: suspend () -> Unit,
    onImportCorpus: suspend (List<AacItem>) -> Unit,
    /** 由 ViewModel 提供：使用「目前 GPS」當住家時呼叫。回傳 Triple(地址, 經度, 緯度)；null = 失敗。 */
    onUseCurrentGpsAsHome: suspend () -> Triple<String, Double, Double>? = { null },
    /** true：嵌入浮動視窗（不要自己的 Scaffold/TopAppBar，由外層 FloatingPanel 提供標題與框）。 */
    embedded: Boolean = false,
    modifier: Modifier = Modifier
) {
    val p = LocalAppPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // === DataStore：緊急聯絡 Chat ID === ※ 預設值 8372934436 來自 AppSettings.DEFAULT_EMERGENCY_CHAT_ID
    val settings by settingsRepo.settings.collectAsState(initial = AppSettings())
    var chatIdDraft by remember(settings.emergencyChatId) {
        mutableStateOf(settings.emergencyChatId)
    }

    var showClearDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var importStatus by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // 取得長期讀取權限（避免進程被回收後 URI 失效）
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            scope.launch {
                when (val r = CorpusImporter.importFromUri(ctx, uri)) {
                    is CorpusImporter.Result.Ok -> {
                        onImportCorpus(r.items)
                        importStatus = "✅ 已匯入 ${r.items.size} 筆語料"
                    }
                    is CorpusImporter.Result.Err -> {
                        importStatus = "❌ ${r.reason}"
                    }
                }
            }
        }
    }

    // 共用內容：LazyColumn（自帶捲動）。嵌入浮動視窗時不套 Scaffold，由 FloatingPanel 提供外框與標題。
    val list: @Composable (Modifier) -> Unit = { listMod ->
        LazyColumn(
            modifier = listMod,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            // ─── 住家地址（影響 GPS 自動分類「住家」標籤）───
            item {
                SettingSection(title = "住家地址", icon = Icons.Default.Home) {
                    val homeAddrField = remember(settings.homeAddress) {
                        mutableStateOf(settings.homeAddress)
                    }
                    OutlinedTextField(
                        value = homeAddrField.value,
                        onValueChange = { homeAddrField.value = it.take(120) },
                        label = { Text("住家地址") },
                        placeholder = { Text("例：台北市大安區羅斯福路四段 1 號") },
                        supportingText = {
                            if (settings.hasHome) {
                                Text("已存座標 %.4f, %.4f".format(settings.homeLatitude, settings.homeLongitude),
                                     fontSize = 10.sp, color = p.textMuted)
                            } else {
                                Text("未設定 · 目前位置不會自動標為「住家」", fontSize = 10.sp, color = p.textMuted)
                            }
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = p.textLight,
                            unfocusedTextColor = p.textLight,
                            focusedBorderColor = p.neonCyan,
                            unfocusedBorderColor = p.neonCyan.copy(alpha = 0.4f),
                            focusedLabelColor = p.neonCyan,
                            unfocusedLabelColor = p.textMuted
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))

                    // 半徑滑桿（公寓 100m / 透天 50m / 大社區 300m）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("命中半徑", color = p.textMuted, fontSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                        Slider(
                            value = settings.homeRadiusMeters,
                            onValueChange = { scope.launch { settingsRepo.setHomeRadius(it) } },
                            valueRange = 30f..500f,
                            colors = SliderDefaults.colors(
                                thumbColor = p.neonCyan, activeTrackColor = p.neonCyan
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("%.0f m".format(settings.homeRadiusMeters),
                             color = p.textLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))

                    // 取得目前 GPS / 清除 / 儲存
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val result = onUseCurrentGpsAsHome()
                                    if (result != null) {
                                        val (addr, lat, lon) = result
                                        homeAddrField.value = addr
                                        settingsRepo.setHome(addr, lat, lon)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = p.neonGreen, contentColor = p.darkBg
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.MyLocation, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("用目前 GPS", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    homeAddrField.value = ""
                                    settingsRepo.setHome("", null, null)
                                }
                            },
                            border = androidx.compose.foundation.BorderStroke(1.dp, p.dangerRose),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = p.dangerRose),
                            modifier = Modifier.weight(1f)
                        ) { Text("清除", fontSize = 12.sp) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "「住家」標籤靠座標距離判定，不會被 Geocoder 的「巷/弄」字串誤判。",
                        fontSize = 10.sp, color = p.textMuted, lineHeight = 13.sp
                    )
                }
            }

            // ─── 緊急聯絡人 Chat ID ───
            item {
                SettingSection(
                    title = "緊急聯絡人 · Telegram Chat ID",
                    icon = Icons.Default.ContactPhone
                ) {
                    OutlinedTextField(
                        value = chatIdDraft,
                        onValueChange = { input ->
                            // 只接受數字 / `-` 開頭的群組 ID
                            chatIdDraft = input.filter { it.isDigit() || it == '-' }.take(16)
                        },
                        label = { Text("Chat ID") },
                        supportingText = {
                            Text("預設值：${AppSettings.DEFAULT_EMERGENCY_CHAT_ID}（Tim Chien 測試帳號）",
                                 fontSize = 11.sp, color = p.textMuted)
                        },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Phone
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = p.neonCyan,
                            unfocusedBorderColor = p.neonCyan.copy(alpha = 0.4f),
                            focusedTextColor = p.textLight,
                            unfocusedTextColor = p.textLight,
                            focusedLabelColor = p.neonCyan,
                            unfocusedLabelColor = p.textMuted
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch { settingsRepo.setEmergencyChatId(chatIdDraft) }
                            },
                            enabled = chatIdDraft.isNotBlank() &&
                                      chatIdDraft != settings.emergencyChatId,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = p.neonGreen, contentColor = p.darkBg
                            ),
                            modifier = Modifier.weight(1f)
                        ) { Text("儲存") }

                        OutlinedButton(
                            onClick = {
                                chatIdDraft = AppSettings.DEFAULT_EMERGENCY_CHAT_ID
                                scope.launch {
                                    settingsRepo.setEmergencyChatId(AppSettings.DEFAULT_EMERGENCY_CHAT_ID)
                                }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = p.textLight),
                            border = androidx.compose.foundation.BorderStroke(1.dp, p.textMuted),
                            modifier = Modifier.weight(1f)
                        ) { Text("回預設值") }
                    }
                }
            }

            // ─── 歷史紀錄管理 ───
            item {
                SettingSection(
                    title = "歷史紀錄管理",
                    icon = Icons.Default.History
                ) {
                    Text(
                        "刪除所有重組歷史、復健紀錄、AAC 組合紀錄、故事訓練紀錄。" +
                        "DataStore 設定（風格、語言等）不會被清除。",
                        color = p.textMuted, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { showClearDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = p.dangerRose, contentColor = androidx.compose.ui.graphics.Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("清除所有歷史紀錄")
                    }
                }
            }

            // ─── 一鍵重置 ───
            item {
                SettingSection(
                    title = "一鍵重置",
                    icon = Icons.Default.RestartAlt
                ) {
                    Text(
                        "將所有 App 設定（主題、語言、TTS 速率、Chat ID 等）回到出廠預設。" +
                        "**不會**刪除歷史紀錄。",
                        color = p.textMuted, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showResetDialog = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = p.auraAmber),
                        border = androidx.compose.foundation.BorderStroke(1.dp, p.auraAmber),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.RestartAlt, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("重置為預設設定")
                    }
                }
            }

            // ─── 匯入外部語料庫 ───
            item {
                SettingSection(
                    title = "匯入外部語料庫（JSON / CSV）",
                    icon = Icons.Default.UploadFile
                ) {
                    Text(
                        "支援 .json（含完整欄位）或 .csv（家屬可用 Excel 編輯）。" +
                        "範例格式請見專案 README。",
                        color = p.textMuted, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            // 兩個 MIME 都收：application/json 與 text/csv
                            importLauncher.launch(arrayOf("application/json", "text/csv", "text/comma-separated-values", "*/*"))
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = p.neonCyan, contentColor = p.darkBg
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.UploadFile, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("選擇檔案", fontWeight = FontWeight.Bold)
                    }
                    importStatus?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = p.textMuted, fontSize = 12.sp)
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    if (embedded) {
        list(modifier.fillMaxWidth().heightIn(max = 540.dp).padding(horizontal = 4.dp))
    } else {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            containerColor = p.darkBg,
            topBar = {
                TopAppBar(
                    title = {
                        Text("▌ 進階設定", color = p.textLight,
                             fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = p.darkBg)
                )
            }
        ) { padding ->
            list(modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp))
        }
    }

    // === 確認對話框 ===
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("確認刪除所有歷史紀錄？") },
            text = { Text("這個動作無法復原。設定值（主題、Chat ID 等）會被保留。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    scope.launch { onClearAllHistory() }
                }) { Text("確認刪除", color = p.dangerRose) }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("取消") } }
        )
    }
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("確認重置所有設定？") },
            text = { Text("主題會回到「科技風」、Chat ID 會回到 ${AppSettings.DEFAULT_EMERGENCY_CHAT_ID}。") },
            confirmButton = {
                TextButton(onClick = {
                    showResetDialog = false
                    scope.launch { settingsRepo.resetAll() }
                }) { Text("確認重置", color = p.auraAmber) }
            },
            dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun SettingSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    val p = LocalAppPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(p.surface)
            .border(1.dp, p.neonCyan.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = p.neonCyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(title, color = p.textLight, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}
