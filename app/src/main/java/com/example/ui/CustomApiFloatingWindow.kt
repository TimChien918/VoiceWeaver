package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RadioButtonUnchecked
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
    LaunchedEffect(Unit) {
        viewModel.ensureImageApisSeeded()
        viewModel.ensureLlmApisSeeded()
    }

    FloatingPanel(title = "API 金鑰管理", icon = Icons.Default.Api, onDismiss = onDismiss) {

        // ── AI 生圖（自訂 API）：供應商下拉 + API 欄位；可多筆 ──
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("🖼 AI 生圖（自訂 API）", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = p.neonCyan, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { viewModel.addImageApi(ImageProvider.GEMINI) },
                border = BorderStroke(1.dp, p.neonGreen.copy(alpha = 0.55f)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.Add, null, tint = p.neonGreen, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("加入", color = p.neonGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            "選供應商、填 API 金鑰即可。同一供應商可加多筆（多把金鑰），生圖時自動備援；勾選 ✓＝目前使用中。",
            fontSize = 10.sp, color = p.textMuted, lineHeight = 15.sp
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
            Text("💬 文字 LLM（多供應商）", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = p.neonCyan, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { viewModel.addLlmApi(LlmProvider.GROQ) },
                border = BorderStroke(1.dp, p.neonGreen.copy(alpha = 0.55f)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.Add, null, tint = p.neonGreen, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("加入", color = p.neonGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            "選供應商、填金鑰即可。可加多筆（含同供應商多把金鑰），重組句子時用 ✓ 那筆，失敗自動換下一筆；全部失敗才用手機本機 AI。",
            fontSize = 10.sp, color = p.textMuted, lineHeight = 15.sp
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

        // ── 定位 / 通報 ──
        SectionTitle("📍 定位與通報", p)
        KeyField("Google Places（POI 反查）", settings.apiKeyGooglePlaces, { viewModel.setApiKeyGooglePlaces(it) }, p)
        KeyField("Telegram Bot Token（緊急通報）", settings.apiKeyTelegramBotToken, { viewModel.setApiKeyTelegramBotToken(it) }, p)
        PlainField("家人電話（危機即時通話）", settings.emergencyPhoneNumber, { viewModel.setEmergencyPhoneNumber(it) }, p, hint = "例 0912345678")
        PlainField("視訊房伺服器（Jitsi，免登入免名字）", settings.videoCallBaseUrl, { viewModel.setVideoCallBaseUrl(it) }, p, hint = "https://meet.jit.si")

        Text(
            "🔒 所有金鑰僅存在這支手機（DataStore），不會上傳、也不會被打包進原始碼。\n" +
            "首次啟動已從本機 .env 自動帶入；留空則用內建預設。改完即時生效。",
            fontSize = 10.sp, color = p.textMuted, lineHeight = 15.sp
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
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (isActive) p.neonGreen.copy(alpha = 0.55f) else p.textMuted.copy(alpha = 0.22f),
                RoundedCornerShape(10.dp)
            )
            .padding(10.dp)
    ) {
        // 供應商
        Text("供應商", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = p.textLight)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            ProviderDropdown(entry.provider, onProvider, p, Modifier.weight(1f))
            IconButton(onClick = onUse, modifier = Modifier.size(34.dp)) {
                Icon(
                    if (isActive) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    if (isActive) "使用中" else "設為使用中",
                    tint = if (isActive) p.neonGreen else p.textMuted, modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.Delete, "刪除", tint = p.dangerRose, modifier = Modifier.size(18.dp))
            }
        }
        // API
        Text("API", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = p.textLight,
            modifier = Modifier.padding(top = 4.dp))
        if (entry.provider.needsKey) {
            KeyField("API 金鑰", entry.key, onKey, p)
        } else {
            Text("此供應商免金鑰，直接可用", fontSize = 11.sp, color = p.neonGreen)
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
            border = BorderStroke(1.dp, p.neonCyan.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(current.displayName, color = p.textLight, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ExpandMore, null, tint = p.textMuted, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ImageProvider.entries.forEach { prov ->
                DropdownMenuItem(
                    text = { Text("${prov.displayName}${if (!prov.needsKey) "（免金鑰）" else ""}") },
                    onClick = { onSelect(prov); open = false }
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, p: AppPalette) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = p.neonCyan)
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
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (isActive) p.neonGreen.copy(alpha = 0.55f) else p.textMuted.copy(alpha = 0.22f),
                RoundedCornerShape(10.dp)
            )
            .padding(10.dp)
    ) {
        Text("供應商", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = p.textLight)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            LlmProviderDropdown(entry.provider, onProvider, p, Modifier.weight(1f))
            IconButton(onClick = onUse, modifier = Modifier.size(34.dp)) {
                Icon(
                    if (isActive) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    if (isActive) "使用中" else "設為使用中",
                    tint = if (isActive) p.neonGreen else p.textMuted, modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.Delete, "刪除", tint = p.dangerRose, modifier = Modifier.size(18.dp))
            }
        }
        Text("API", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = p.textLight,
            modifier = Modifier.padding(top = 4.dp))
        KeyField("API 金鑰", entry.key, onKey, p)
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
            border = BorderStroke(1.dp, p.neonCyan.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(current.displayName, color = p.textLight, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ExpandMore, null, tint = p.textMuted, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LlmProvider.entries.forEach { prov ->
                DropdownMenuItem(text = { Text(prov.displayName) }, onClick = { onSelect(prov); open = false })
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
    OutlinedTextField(
        value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true,
        placeholder = if (hint.isNotBlank()) ({ Text("預設：$hint", fontSize = 10.sp, color = p.textMuted) }) else null,
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
        value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    if (visible) "隱藏" else "顯示", tint = p.textMuted, modifier = Modifier.size(18.dp)
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
