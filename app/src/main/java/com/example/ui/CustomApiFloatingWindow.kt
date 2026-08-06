package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.VoiceWeaverViewModel
import com.example.data.ImageApiEntry
import com.example.data.ImageProvider
import com.example.data.LlmApiEntry
import com.example.data.LlmProvider
import com.example.data.TtsApiEntry
import com.example.data.TtsProvider
import com.example.ui.theme.AppPalette
import com.example.ui.theme.LocalAppPalette

/**
 * API 金鑰管理浮動視窗。集中管理全部 API：
 * - AI 生圖（自訂 API）：每筆只要「服務商下拉 + 金鑰」；同一服務商可加多筆（多把金鑰，生圖時自動備援）。
 * - 文字 LLM（Groq / OpenRouter / Cohere）、Google Places、Telegram。
 *
 * 安全：金鑰只存裝置 DataStore（不進版控、不打包原始碼）；金鑰欄位預設遮罩，點眼睛才明文。
 */
@Composable
fun ApiKeysPanel(
    viewModel: VoiceWeaverViewModel,
    onDismiss: () -> Unit
) {
    val p = LocalAppPalette.current
    val settings by viewModel.settings.collectAsState()
    val imageApis by viewModel.imageApisFlow.collectAsState()
    val llmApis by viewModel.llmApisFlow.collectAsState()
    val ttsApis by viewModel.ttsApisFlow.collectAsState()
    val sttApis by viewModel.sttApisFlow.collectAsState()
    LaunchedEffect(Unit) {
        viewModel.ensureImageApisSeeded()
        viewModel.ensureLlmApisSeeded()
        viewModel.ensureTtsApisSeeded()
    }

    FloatingPanel(title = I18n.tr("API 金鑰管理"), icon = Icons.Default.Api, onDismiss = onDismiss) {

        // ── AI 生圖（自訂 API）：供應商下拉 + API 欄位；可多筆 ──
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(I18n.tr("AI 生圖"), fontSize = 13.sp, color = p.textMuted,
                modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { viewModel.addImageApi(ImageProvider.GEMINI) },
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0xFF007AFF).copy(alpha = 0.5f)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 34.dp)
            ) {
                Icon(Icons.Default.Add, null, tint = Color(0xFF007AFF), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.tr("加入"), color = Color(0xFF007AFF), fontSize = 14.sp)
            }
        }
        Text(
            I18n.tr("選供應商、填 API 金鑰即可。同一供應商可加多筆（多把金鑰），生圖時自動備援；勾選 ✓＝目前使用中。"),
            fontSize = 13.sp, color = p.textMuted, lineHeight = 17.sp
        )
        val selectedId = settings.imageApiSelectedId.ifBlank { imageApis.firstOrNull()?.id ?: "" }
        imageApis.forEach { entry ->
            ImageApiRow(
                entry = entry,
                isActive = entry.id == selectedId,
                onProvider = { viewModel.updateImageApi(entry.copy(provider = it, name = "", model = "", endpoint = "")) },
                onKey = { viewModel.updateImageApi(entry.copy(key = it)) },
                onUse = { viewModel.selectImageApi(entry.id) },
                onDelete = { viewModel.deleteImageApi(entry.id) },
                p = p
            )
        }

        Divider(p)

        // ── 文字 LLM（重組雲端備援）：供應商下拉 + 金鑰；可多筆，自動備援（像 agent 多供應商）──
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(I18n.tr("文字 LLM"), fontSize = 13.sp, color = p.textMuted,
                modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { viewModel.addLlmApi(LlmProvider.GROQ) },
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0xFF007AFF).copy(alpha = 0.5f)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 34.dp)
            ) {
                Icon(Icons.Default.Add, null, tint = Color(0xFF007AFF), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.tr("加入"), color = Color(0xFF007AFF), fontSize = 14.sp)
            }
        }
        Text(
            I18n.tr("填好金鑰就好，不用選要用哪家——系統會依實測成功率與速度自動挑，失敗自動換下一家，免費的優先用。想固定用某一家再點左邊圖釘。"),
            fontSize = 13.sp, color = p.textMuted, lineHeight = 17.sp
        )
        val llmSelId = settings.llmApiSelectedId.ifBlank { llmApis.firstOrNull()?.id ?: "" }
        llmApis.forEach { entry ->
            LlmApiRow(
                entry = entry,
                isActive = entry.id == llmSelId,
                onProvider = { viewModel.updateLlmApi(entry.copy(provider = it, name = "", model = "", endpoint = "")) },
                onKey = { viewModel.updateLlmApi(entry.copy(key = it)) },
                onUse = { viewModel.selectLlmApi(entry.id) },
                onDelete = { viewModel.deleteLlmApi(entry.id) },
                p = p
            )
        }

        Divider(p)

        // ── 語音合成：跟上面兩區同一套模式（多供應商、自動排序、自動備援）──
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(I18n.tr("語音合成"), fontSize = 13.sp, color = p.textMuted,
                modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { viewModel.addTtsApi(TtsProvider.ELEVENLABS) },
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0xFF007AFF).copy(alpha = 0.5f)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 34.dp)
            ) {
                Icon(Icons.Default.Add, null, tint = Color(0xFF007AFF), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.tr("加入"), color = Color(0xFF007AFF), fontSize = 14.sp)
            }
        }
        Text(
            I18n.tr("加幾家不同的聲音，系統會自動挑最穩最快的那家；某一家掛掉會自動換下一家。點喇叭可以先試聽。"),
            fontSize = 13.sp, color = p.textMuted, lineHeight = 17.sp
        )
        val ttsSelId = settings.ttsApiSelectedId
        // 試聽結果要顯示在按下去的那一列旁邊，不然使用者不知道成功或失敗
        var testingId by remember { mutableStateOf("") }
        val ttsStatus by viewModel.sovitsSynthesisStatus.collectAsState()
        ttsApis.forEach { entry ->
            TtsApiRow(
                entry = entry,
                testStatus = if (entry.id == testingId) ttsStatus else null,
                isPinned = entry.id == ttsSelId,
                onProvider = { viewModel.updateTtsApi(entry.copy(provider = it, name = "", model = "", voice = "", endpoint = "", extra = "")) },
                onKey = { viewModel.updateTtsApi(entry.copy(key = it)) },
                onVoice = { viewModel.updateTtsApi(entry.copy(voice = it)) },
                onExtra = { viewModel.updateTtsApi(entry.copy(extra = it)) },
                onEndpoint = { viewModel.updateTtsApi(entry.copy(endpoint = it)) },
                onPin = { viewModel.selectTtsApi(entry.id) },
                onTest = { testingId = entry.id; viewModel.testTtsApi(entry) },
                onDelete = { viewModel.deleteTtsApi(entry.id) },
                p = p
            )
        }

        Divider(p)

        // ── 語音辨識：獨立成一區，不再跟文字 LLM 綁在一起 ──────────────
        //
        // 以前轉寫掛在文字 LLM 的內建端點上，等於「想換轉寫供應商就得連重組
        // 的供應商一起換」。但重組要語意能力、轉寫要對含糊發音的容錯，
        // 實務上很少是同一家最好——而轉寫品質直接決定整條流程能不能用。
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(I18n.tr("語音辨識"), fontSize = 13.sp, color = p.textMuted,
                modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { viewModel.addSttApi(com.example.data.SttProvider.GROQ) },
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0xFF007AFF).copy(alpha = 0.5f)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 34.dp)
            ) {
                Icon(Icons.Default.Add, null, tint = Color(0xFF007AFF), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.tr("加入"), color = Color(0xFF007AFF), fontSize = 14.sp)
            }
        }
        Text(
            I18n.tr("由上往下試，第一個成功就用它。把最聽得懂你的那家排到最上面。留空＝沿用文字 LLM 附帶的轉寫。"),
            fontSize = 13.sp, color = p.textMuted, lineHeight = 17.sp
        )
        sttApis.forEachIndexed { index, entry ->
            SttApiRow(
                entry = entry,
                isFirst = index == 0,
                isLast = index == sttApis.lastIndex,
                onProvider = { viewModel.updateSttApi(entry.copy(provider = it, name = "", model = "", endpoint = "")) },
                onKey = { viewModel.updateSttApi(entry.copy(key = it)) },
                onModel = { viewModel.updateSttApi(entry.copy(model = it)) },
                onToggle = { viewModel.updateSttApi(entry.copy(enabled = !entry.enabled)) },
                onUp = { viewModel.moveSttApi(entry.id, up = true) },
                onDown = { viewModel.moveSttApi(entry.id, up = false) },
                onDelete = { viewModel.deleteSttApi(entry.id) },
                p = p
            )
        }

        Divider(p)

        // ── 定位 / 通報 ──
        SectionTitle(I18n.tr("📍 定位與通報"), p)
        KeyField(I18n.tr("Google Places（POI 反查）"), settings.apiKeyGooglePlaces, { viewModel.setApiKeyGooglePlaces(it) }, p)
        KeyField(I18n.tr("Telegram Bot Token（緊急通報）"), settings.apiKeyTelegramBotToken, { viewModel.setApiKeyTelegramBotToken(it) }, p)
        PlainField(I18n.tr("家人電話（危機即時通話）"), settings.emergencyPhoneNumber, { viewModel.setEmergencyPhoneNumber(it) }, p, hint = I18n.tr("例 0912345678"))
        PlainField(I18n.tr("視訊房伺服器（Jitsi，免登入免名字）"), settings.videoCallBaseUrl, { viewModel.setVideoCallBaseUrl(it) }, p, hint = "https://meet.jit.si")

        Text(
            I18n.tr("🔒 所有金鑰僅存在這支手機（DataStore），不會上傳、也不會被打包進原始碼。\n首次啟動已從本機 .env 自動帶入；留空則用內建預設。改完即時生效。"),
            fontSize = 13.sp, color = p.textMuted, lineHeight = 17.sp
        )
    }
}

/**
 * 一筆生圖 API：上「供應商」標籤＋下拉（右側 ✓＝使用中、🗑＝刪除），
 * 下「API」標籤＋金鑰欄位（免金鑰供應商顯示提示）。
 */
@Composable
private fun ImageApiRow(
    entry: ImageApiEntry,
    isActive: Boolean,
    onProvider: (ImageProvider) -> Unit,
    onKey: (String) -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
    p: AppPalette
) {
    // iOS 清單風：一列＝一個供應商。拿掉「供應商」「API」兩個標題字——
    // 下拉選單長什麼樣就已經說明是供應商了，多餘的標籤只是把卡片撐胖。
    // 金鑰欄只在該供應商真的需要金鑰時才出現。
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
        ) {
            IconButton(onClick = onUse, modifier = Modifier.size(30.dp)) {
                Icon(
                    if (isActive) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    if (isActive) I18n.tr("使用中") else I18n.tr("設為使用中"),
                    tint = if (isActive) Color(0xFF34C759) else p.textMuted.copy(alpha = 0.5f),
                    modifier = Modifier.size(21.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
            ProviderDropdown(entry.provider, onProvider, p, Modifier.weight(1f))
            IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Delete, I18n.tr("刪除"),
                    tint = Color(0xFFFF3B30), modifier = Modifier.size(18.dp))
            }
        }
        if (entry.provider.needsKey) {
            KeyField(I18n.tr("API 金鑰"), entry.key, onKey, p)
            Spacer(Modifier.height(4.dp))
        } else {
            Text(I18n.tr("免金鑰，直接可用"), fontSize = 13.sp, color = Color(0xFF34C759),
                modifier = Modifier.padding(start = 42.dp, bottom = 6.dp))
        }
    }
}

/** 供應商下拉選單。 */
@Composable
private fun ProviderDropdown(
    current: ImageProvider,
    onSelect: (ImageProvider) -> Unit,
    p: AppPalette,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { open = true },
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, p.textMuted.copy(alpha = 0.35f)),
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
        ) {
            Text(I18n.tr(current.displayName), color = p.textLight, fontSize = 15.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Icon(Icons.Default.ExpandMore, null, tint = p.textMuted, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ImageProvider.entries.forEach { prov ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${I18n.tr(prov.displayName)}${if (!prov.needsKey) I18n.tr("（免金鑰）") else ""}",
                            fontSize = 15.sp, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            color = p.textLight
                        )
                    },
                    onClick = { onSelect(prov); open = false },
                    modifier = Modifier.heightIn(min = 44.dp)
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, p: AppPalette) {
    Text(text, fontSize = 13.sp, color = p.textMuted)
}

/** 一筆文字 LLM：上「供應商」標籤＋下拉（✓使用中、🗑刪除），下「API」標籤＋金鑰欄位。 */
@Composable
private fun LlmApiRow(
    entry: LlmApiEntry,
    isActive: Boolean,
    onProvider: (LlmProvider) -> Unit,
    onKey: (String) -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
    p: AppPalette
) {
    // 同 ImageApiRow：一列一個供應商，不要標籤字、不要外框。
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
        ) {
            // 圖釘＝人工覆寫「固定優先用這家」；沒釘就交給 SmartApiRouter 自動排序。
            IconButton(onClick = onUse, modifier = Modifier.size(30.dp)) {
                Icon(
                    if (isActive) Icons.Default.PushPin else Icons.Default.PushPin,
                    if (isActive) I18n.tr("已釘選（固定優先）") else I18n.tr("釘選以固定優先使用"),
                    tint = if (isActive) Color(0xFF007AFF) else p.textMuted.copy(alpha = 0.35f),
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
            LlmProviderDropdown(entry.provider, onProvider, p, Modifier.weight(1f))
            IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Delete, I18n.tr("刪除"),
                    tint = Color(0xFFFF3B30), modifier = Modifier.size(18.dp))
            }
        }
        KeyField(I18n.tr("API 金鑰"), entry.key, onKey, p)
        // 實測狀態：讓使用者看得到「系統為什麼這樣排」，而不是黑箱
        Text(
            com.example.ai.SmartApiRouter.statusLabel(entry.id) +
                if (isActive) I18n.tr("・已釘選") else "",
            fontSize = 12.sp, color = p.textMuted,
            modifier = Modifier.padding(start = 42.dp, top = 2.dp, bottom = 6.dp)
        )
    }
}

/** 文字 LLM 供應商下拉選單。 */
@Composable
private fun LlmProviderDropdown(
    current: LlmProvider,
    onSelect: (LlmProvider) -> Unit,
    p: AppPalette,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { open = true },
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, p.textMuted.copy(alpha = 0.35f)),
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
        ) {
            Text(I18n.tr(current.displayName), color = p.textLight, fontSize = 15.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Icon(Icons.Default.ExpandMore, null, tint = p.textMuted, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LlmProvider.entries.forEach { prov ->
                DropdownMenuItem(
                    text = {
                        Text(
                            I18n.tr(prov.displayName),
                            fontSize = 15.sp, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            color = p.textLight
                        )
                    },
                    onClick = { onSelect(prov); open = false },
                    modifier = Modifier.heightIn(min = 44.dp)
                )
            }
        }
    }
}

/**
 * 一筆語音合成設定。跟 [LlmApiRow] 同一個排版，只是多了「聲音」欄位與試聽鈕——
 * 語音合成跟文字不一樣，同一家可以有很多種聲音，選錯聲音比選錯供應商更容易發生，
 * 所以聲音要能直接改、而且要能當場聽到。
 */
@Composable
private fun TtsApiRow(
    entry: TtsApiEntry,
    isPinned: Boolean,
    /** 這一列剛按過試聽時的結果訊息；沒按過就是 null。 */
    testStatus: String?,
    onProvider: (TtsProvider) -> Unit,
    onKey: (String) -> Unit,
    onVoice: (String) -> Unit,
    onExtra: (String) -> Unit,
    onEndpoint: (String) -> Unit,
    onPin: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
    p: AppPalette
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
        ) {
            IconButton(onClick = onPin, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Default.PushPin,
                    if (isPinned) I18n.tr("已釘選（固定優先）") else I18n.tr("釘選以固定優先使用"),
                    tint = if (isPinned) Color(0xFF007AFF) else p.textMuted.copy(alpha = 0.35f),
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
            TtsProviderDropdown(entry.provider, onProvider, p, Modifier.weight(1f))
            IconButton(onClick = onTest, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.RecordVoiceOver, I18n.tr("試聽"),
                    tint = Color(0xFF34C759), modifier = Modifier.size(19.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Delete, I18n.tr("刪除"),
                    tint = Color(0xFFFF3B30), modifier = Modifier.size(18.dp))
            }
        }
        if (entry.provider.needsKey) KeyField(I18n.tr("API 金鑰"), entry.key, onKey, p)
        // 「跑在你的電腦」那種要填區網位址，其他家端點固定不必露出來
        if (!entry.provider.needsKey && entry.provider.defaultEndpoint.isNotBlank()) {
            LinkModeChips(entry, onEndpoint, p)
            PlainField(I18n.tr("電腦位址"), entry.endpoint, onEndpoint, p, hint = entry.provider.defaultEndpoint)
        }
        if (entry.provider.extraLabel.isNotBlank()) {
            PlainField(I18n.tr(entry.provider.extraLabel), entry.extra, onExtra, p, hint = I18n.tr(entry.provider.extraHint))
        }
        if (entry.provider.voiceHint.isNotBlank()) {
            PlainField(I18n.tr("聲音"), entry.voice, onVoice, p, hint = entry.provider.defaultVoice)
            Text(I18n.tr(entry.provider.voiceHint), fontSize = 12.sp, color = p.textMuted,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp))
        }
        Text(
            testStatus ?: buildString {
                append(com.example.ai.SmartApiRouter.statusLabel(entry.id))
                if (!entry.provider.multilingual) append(I18n.tr("・只有英文"))
                if (isPinned) append(I18n.tr("・已釘選"))
            },
            fontSize = 12.sp,
            color = when {
                testStatus == null -> p.textMuted
                testStatus.startsWith("❌") -> Color(0xFFFF3B30)
                testStatus.startsWith("✅") -> Color(0xFF34C759)
                else -> p.textMuted
            },
            lineHeight = 16.sp,
            modifier = Modifier.padding(start = 42.dp, top = 2.dp, bottom = 6.dp)
        )
    }
}

/**
 * 有線 USB／無線 Wi-Fi 一鍵切換（連到自己電腦的那種供應商才有）。
 *
 * 為什麼要切：Wi-Fi 不穩或外出（醫院、復健中心的 Wi-Fi 常常不能用）時，
 * 直接拿 USB 線接電腦就能繼續用自己錄的聲音。兩種模式差別只在位址：
 *  - 有線：電腦先跑 `adb reverse tcp:9880 tcp:9880`，手機打 127.0.0.1
 *  - 無線：手機與電腦同一個區網，打電腦的區網 IP
 *
 * 切換時保留原本的埠號——使用者可能把 server 開在別的埠，不該被覆蓋掉。
 */
@Composable
private fun LinkModeChips(entry: TtsApiEntry, onEndpoint: (String) -> Unit, p: AppPalette) {
    val current = entry.effectiveEndpoint
    val wired = current.contains("127.0.0.1") || current.contains("localhost")
    val port = Regex(":(\\d+)").find(current)?.groupValues?.get(1) ?: "9880"
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 42.dp, top = 2.dp, bottom = 2.dp)
    ) {
        LinkChip(I18n.tr("有線 USB"), Icons.Default.Usb, wired, p) {
            onEndpoint("http://127.0.0.1:$port")
        }
        LinkChip(I18n.tr("無線 Wi-Fi"), Icons.Default.Wifi, !wired, p) {
            // 已經是區網位址就不要覆蓋掉使用者填好的 IP
            if (wired) onEndpoint("http://192.168.1.x:$port")
        }
    }
}

@Composable
private fun LinkChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    p: AppPalette,
    onClick: () -> Unit
) {
    val accent = Color(0xFF007AFF)
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (selected) accent else p.textMuted.copy(alpha = 0.3f)),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        modifier = Modifier.heightIn(min = 32.dp)
    ) {
        Icon(icon, null, tint = if (selected) accent else p.textMuted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 13.sp, color = if (selected) accent else p.textMuted, maxLines = 1)
    }
}

/** 語音合成供應商下拉選單。 */
@Composable
private fun TtsProviderDropdown(
    current: TtsProvider,
    onSelect: (TtsProvider) -> Unit,
    p: AppPalette,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { open = true },
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, p.textMuted.copy(alpha = 0.35f)),
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
        ) {
            Text(I18n.tr(current.displayName), color = p.textLight, fontSize = 15.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Icon(Icons.Default.ExpandMore, null, tint = p.textMuted, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TtsProvider.entries.forEach { prov ->
                DropdownMenuItem(
                    text = {
                        Text(I18n.tr(prov.displayName), fontSize = 15.sp, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            color = p.textLight)
                    },
                    onClick = { onSelect(prov); open = false },
                    modifier = Modifier.heightIn(min = 44.dp)
                )
            }
        }
    }
}

@Composable
private fun Divider(p: AppPalette) {
    HorizontalDivider(color = p.textMuted.copy(alpha = 0.18f), modifier = Modifier.padding(vertical = 2.dp))
}

@Composable
private fun PlainField(label: String, value: String, onChange: (String) -> Unit, p: AppPalette, hint: String = "") {
    // 本地編輯狀態 + 防抖存檔：打字時只更新本地（不掉字），停手 350ms 或失焦才寫入。
    var text by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!focused && value != text) text = value }
    LaunchedEffect(text) {
        if (focused && text != value) { kotlinx.coroutines.delay(350); onChange(text) }
    }
    // 欄位字級寫死：這裡的字會跟著全域字體放大倍率跑，1.8× 時「Google Places（POI 反查）」
    // 會撐成兩行、輸入框變成一個巨大的方塊。放大是給主畫面看的，設定裡的欄位維持一般大小才讀得順。
    OutlinedTextField(
        value = text, onValueChange = { text = it },
        label = { Text(label, fontSize = 12.sp, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp),
        placeholder = if (hint.isNotBlank()) ({ Text(I18n.tr("預設：%s").format(hint), fontSize = 12.sp, color = p.textMuted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }) else null,
        modifier = Modifier.fillMaxWidth().onFocusChanged { st ->
            focused = st.isFocused
            if (!st.isFocused && text != value) onChange(text)
        }, colors = fieldColors(p)
    )
}

/** 金鑰欄位：預設遮罩，眼睛切換明文。 */
@Composable
private fun KeyField(label: String, value: String, onChange: (String) -> Unit, p: AppPalette) {
    var visible by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!focused && value != text) text = value }
    LaunchedEffect(text) {
        if (focused && text != value) { kotlinx.coroutines.delay(350); onChange(text) }
    }
    OutlinedTextField(
        value = text, onValueChange = { text = it },
        label = { Text(label, fontSize = 12.sp, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    if (visible) I18n.tr("隱藏") else I18n.tr("顯示"), tint = p.textMuted, modifier = Modifier.size(18.dp)
                )
            }
        },
        modifier = Modifier.fillMaxWidth().onFocusChanged { st ->
            focused = st.isFocused
            if (!st.isFocused && text != value) onChange(text)
        }, colors = fieldColors(p)
    )
}

@Composable
private fun fieldColors(p: AppPalette) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = p.textLight, unfocusedTextColor = p.textLight,
    focusedBorderColor = p.neonCyan, unfocusedBorderColor = p.textMuted.copy(alpha = 0.35f),
    focusedLabelColor = p.neonCyan, unfocusedLabelColor = p.textMuted, cursorColor = p.neonCyan,
    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent
)

/**
 * 語音辨識的一列。
 *
 * 跟生圖／文字那兩區的差別是這裡有「上下移動」而不是「設為使用中」——
 * 轉寫是由上往下試到成功為止，順序本身就是設定，不是排版偏好。
 */
@Composable
private fun SttApiRow(
    entry: com.example.data.SttApiEntry,
    isFirst: Boolean,
    isLast: Boolean,
    onProvider: (com.example.data.SttProvider) -> Unit,
    onKey: (String) -> Unit,
    onModel: (String) -> Unit,
    onToggle: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDelete: () -> Unit,
    p: AppPalette
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
        ) {
            // 停用而不是刪除：換家測試時不必把金鑰重打一次
            IconButton(onClick = onToggle, modifier = Modifier.size(30.dp)) {
                Icon(
                    if (entry.enabled) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    if (entry.enabled) I18n.tr("已啟用") else I18n.tr("已停用"),
                    tint = if (entry.enabled) Color(0xFF34C759) else p.textMuted.copy(alpha = 0.5f),
                    modifier = Modifier.size(21.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
            SttProviderDropdown(entry.provider, onProvider, p, Modifier.weight(1f))
            IconButton(onClick = onUp, enabled = !isFirst, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, I18n.tr("往上移"),
                    tint = if (isFirst) p.textMuted.copy(alpha = 0.3f) else p.textLight,
                    modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onDown, enabled = !isLast, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, I18n.tr("往下移"),
                    tint = if (isLast) p.textMuted.copy(alpha = 0.3f) else p.textLight,
                    modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Delete, I18n.tr("刪除"),
                    tint = Color(0xFFFF3B30), modifier = Modifier.size(18.dp))
            }
        }
        if (entry.provider.needsKey) {
            KeyField(I18n.tr("API 金鑰"), entry.key, onKey, p)
        } else {
            Text(I18n.tr("免金鑰，直接可用"), fontSize = 13.sp, color = Color(0xFF34C759),
                modifier = Modifier.padding(start = 42.dp, bottom = 6.dp))
        }
        // 模型可改：同一家常有快/準兩種選擇，而哪個好用得看使用者的發音狀況
        PlainField(I18n.tr("模型"), entry.model, onModel, p, hint = entry.provider.defaultModel)
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SttProviderDropdown(
    current: com.example.data.SttProvider,
    onSelect: (com.example.data.SttProvider) -> Unit,
    p: AppPalette,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { open = true },
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, p.textMuted.copy(alpha = 0.35f)),
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
        ) {
            Text(I18n.tr(current.displayName), color = p.textLight, fontSize = 15.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Icon(Icons.Default.ExpandMore, null, tint = p.textMuted, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            com.example.data.SttProvider.entries.forEach { prov ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${I18n.tr(prov.displayName)}${if (!prov.needsKey) I18n.tr("（免金鑰）") else ""}",
                            fontSize = 15.sp, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            color = p.textLight
                        )
                    },
                    onClick = { onSelect(prov); open = false },
                )
            }
        }
    }
}
