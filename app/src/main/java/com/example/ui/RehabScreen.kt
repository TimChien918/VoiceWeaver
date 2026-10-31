package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.ai.EyeControlUiState
import com.example.ai.EyeInteractionMode
import com.example.ai.RehabUiState
import com.example.ai.SttState
import com.example.ai.VoiceWeaverViewModel
import com.example.ui.theme.LocalAppPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 語音復健獨立分頁（與主畫面、歷史紀錄並列）。
 * - 未啟動：顯示「自訂目標句」輸入框 + 從歷史紀錄挑句子
 * - 已啟動：練習卡片（聽整句／點字卡發音／跟讀錄音 → Levenshtein 評分 → 寫日誌）
 * - 底部：最近練習日誌 + 場景平均分統計
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RehabScreen(
    viewModel: VoiceWeaverViewModel,
    onTraining: () -> Unit = {}
) {
    val rehab by viewModel.rehabState.collectAsState()
    // 復健跟讀有兩條路（雲端 Whisper／裝置端 Vosk），rehabSttState 已經把兩邊合流；
    // 用 viewModel.sttState 的話走雲端時按鈕會一直停在「開始」。
    val sttState by viewModel.rehabSttState.collectAsState()
    val recentTargets by viewModel.rehabTargetCandidates.collectAsState()
    val rehabLogs by viewModel.rehabLogs.collectAsState()
    val rehabStats by viewModel.rehabStats.collectAsState()
    val aiSuggestions by viewModel.aiSuggestions.collectAsState()
    val isGenerating by viewModel.isGeneratingSuggestions.collectAsState()
    val eyeControl by viewModel.eyeControlState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val streak by viewModel.rehabStreak.collectAsState()
    val rehabQueue by viewModel.rehabQueue.collectAsState()
    val rehabQueueIndex by viewModel.rehabQueueIndex.collectAsState()

    val p = LocalAppPalette.current
    val darkBg = p.darkBg
    val surfaceColor = p.surface
    val neonCyan = p.neonCyan
    val neonGreen = p.neonGreen
    val auraAmber = p.auraAmber
    val dangerRose = p.dangerRose
    val textLight = p.textLight
    val textMuted = p.textMuted

    DisposableEffect(Unit) {
        viewModel.enterRehabScreen()
        onDispose { viewModel.exitRehabScreen() }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.School, null, tint = neonCyan, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.rehab_title), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = p.textLight)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = darkBg)
            )
        },
        containerColor = darkBg
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            // 連續練習天數（streak）激勵
            if (streak > 0) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(auraAmber.copy(alpha = 0.14f))
                            .border(1.dp, auraAmber.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text("🔥", fontSize = 22.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(I18n.tr("連續練習 "), fontSize = 14.sp, color = textLight)
                        Text("$streak", fontSize = 20.sp, fontWeight = FontWeight.Black, color = auraAmber)
                        Text(I18n.tr(" 天，繼續保持！"), fontSize = 14.sp, color = textLight)
                    }
                }
            }

            // 整段練習：佇列進度 + 下一句
            if (rehab.isActive && rehabQueue.size > 1) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(neonCyan.copy(alpha = 0.10f))
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(I18n.tr("📖 整段練習 %d/%d").format(rehabQueueIndex + 1, rehabQueue.size), fontSize = 12.sp, color = textLight, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            if (!viewModel.nextRehabSentence()) viewModel.clearRehab()
                        }) {
                            Text(if (rehabQueueIndex + 1 < rehabQueue.size) I18n.tr("下一句 ▶") else I18n.tr("完成整段 ✓"),
                                 fontSize = 13.sp, color = neonCyan, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 故事訓練入口：只在「開發者模式」開啟時顯示。
            // 注意：實際進入畫面後還會再依 deviceTier == HIGH_END 二次把關（弱機 RAM 撐不住 LLM 評論）。
            if (settings.developerMode && !rehab.isActive) {
                item {
                    StoryTrainingEntryCard(
                        onClick = onTraining,
                        surfaceColor = surfaceColor,
                        neonCyan = neonCyan,
                        textLight = textLight,
                        textMuted = textMuted
                    )
                }
            }

            if (rehab.isActive) {
                item {
                    PracticeCard(
                        rehab = rehab,
                        sttState = sttState,
                        eyeControl = eyeControl,
                        viewModel = viewModel,
                        surfaceColor = surfaceColor,
                        neonCyan = neonCyan, neonGreen = neonGreen,
                        auraAmber = auraAmber, dangerRose = dangerRose,
                        textLight = textLight, textMuted = textMuted
                    )
                }
            } else {
                item {
                    TargetPicker(
                        recent = recentTargets,
                        aiSuggestions = aiSuggestions,
                        isGenerating = isGenerating,
                        multilingualStt = viewModel.rehabCanUseCloudStt(),
                        onPick = { viewModel.startRehab(it) },
                        onPickParagraph = { viewModel.startRehabParagraph(it) },
                        onRequestAi = { viewModel.requestAiSuggestions() },
                        onClearAi = { viewModel.clearAiSuggestions() },
                        surfaceColor = surfaceColor,
                        neonCyan = neonCyan,
                        auraAmber = auraAmber,
                        textLight = textLight, textMuted = textMuted
                    )
                }
            }

            // 場景平均分統計
            if (rehabStats.isNotEmpty()) {
                item {
                    StatsCard(
                        stats = rehabStats,
                        surfaceColor = surfaceColor,
                        neonGreen = neonGreen, auraAmber = auraAmber, dangerRose = dangerRose,
                        textLight = textLight, textMuted = textMuted
                    )
                }
            }

            // 最近練習紀錄
            if (rehabLogs.isNotEmpty()) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) {
                        Text(stringResource(R.string.rehab_recent_practice), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = textLight)
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = { viewModel.clearRehabLogs() },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) { Text(stringResource(R.string.rehab_clear_all), fontSize = 11.sp, color = dangerRose) }
                    }
                }
                items(rehabLogs.take(20)) { log ->
                    LogRow(
                        targetSentence = log.targetSentence,
                        locationTag = log.locationTag,
                        score = log.score,
                        timestamp = log.timestamp,
                        surfaceColor = surfaceColor,
                        neonGreen = neonGreen, auraAmber = auraAmber, dangerRose = dangerRose,
                        textLight = textLight, textMuted = textMuted
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ─── 目標句子挑選 ─────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetPicker(
    recent: List<String>,
    aiSuggestions: List<String>,
    isGenerating: Boolean,
    /** 辨識器認不認得非中文（雲端 Whisper 可用時為 true）；決定題庫要不要換語言 */
    multilingualStt: Boolean,
    onPick: (String) -> Unit,
    onPickParagraph: (String) -> Unit,
    onRequestAi: () -> Unit,
    onClearAi: () -> Unit,
    surfaceColor: Color,
    neonCyan: Color,
    auraAmber: Color,
    textLight: Color,
    textMuted: Color
) {
    val isLightTheme = surfaceColor.luminance() > 0.7f
    val inputContainer = if (isLightTheme) Color.White else Color.Black.copy(alpha = 0.25f)
    val inactiveBorder = textMuted.copy(alpha = 0.45f)

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        border = BorderStroke(1.dp, inactiveBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.rehab_choose_sentence), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = textLight)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.rehab_choose_hint), fontSize = 11.sp, color = textMuted)
            Spacer(Modifier.height(12.dp))

            var customInput by remember { mutableStateOf("") }
            OutlinedTextField(
                value = customInput,
                onValueChange = { customInput = it },
                placeholder = { Text(stringResource(R.string.rehab_placeholder), fontSize = 13.sp, color = textMuted) },
                singleLine = false,
                maxLines = 3,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = inputContainer,
                    unfocusedContainerColor = inputContainer,
                    focusedBorderColor = neonCyan,
                    unfocusedBorderColor = inactiveBorder,
                    focusedTextColor = textLight,
                    unfocusedTextColor = textLight,
                    focusedPlaceholderColor = textMuted,
                    unfocusedPlaceholderColor = textMuted
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { if (customInput.isNotBlank()) onPick(customInput.trim()) },
                enabled = customInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = neonCyan,
                    disabledContainerColor = textMuted.copy(alpha = 0.20f),
                    disabledContentColor = textMuted
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.rehab_start_with_this), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            // 整段練習：貼一段文章 → 自動分句逐句練
            if (customInput.trim().length > 12) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { onPickParagraph(customInput.trim()) },
                    border = BorderStroke(1.dp, auraAmber),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = auraAmber),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.MenuBook, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(I18n.tr("整段練習（自動分句逐句練）"), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            // 臨床常用題庫（漢語標準失語症檢查表通用題）：一鍵開始，離線也能用
            Spacer(Modifier.height(16.dp))
            Text(I18n.tr("臨床常用題庫（點一下開始練）"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = neonCyan)
            // 有雲端 Whisper（多語）就整題換成介面語言；只有裝置端 Vosk（中文單一模型）
            // 時題目必須維持中文，改成在下面補一行語意註解。
            val multiStt = multilingualStt
            // 四級題庫：單詞 → 詞組 → 短句 → 情境長句（中英日韓同一份分級，見 PracticeLevel）
            com.example.data.PracticeLevel.values().forEach { level ->
                ClinicalBankRow(
                    I18n.tr(level.displayName),
                    com.example.data.AphasiaPractice.levelPreview(level),
                    multiStt, onPick, neonCyan, textLight
                )
            }

            // AI 智慧推薦（依使用者程度）
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.AutoAwesome, null, tint = auraAmber, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.rehab_ai_section), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = auraAmber)
                Spacer(Modifier.weight(1f))
                if (aiSuggestions.isNotEmpty()) {
                    TextButton(
                        onClick = onClearAi,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) { Text(stringResource(R.string.rehab_collapse), fontSize = 10.sp, color = textMuted) }
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = onRequestAi,
                enabled = !isGenerating,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, auraAmber.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = auraAmber, strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.rehab_generating), fontSize = 12.sp, color = auraAmber)
                } else {
                    Icon(Icons.Default.AutoAwesome, null, tint = auraAmber, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (aiSuggestions.isEmpty()) stringResource(R.string.rehab_ai_request)
                        else stringResource(R.string.rehab_regenerate),
                        fontSize = 12.sp, color = auraAmber, fontWeight = FontWeight.Bold
                    )
                }
            }
            if (aiSuggestions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    aiSuggestions.forEach { sentence ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(auraAmber.copy(alpha = 0.08f))
                                .border(0.5.dp, auraAmber.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                .clickable { onPick(sentence) }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, null, tint = auraAmber, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(sentence, fontSize = 13.sp, color = textLight, modifier = Modifier.weight(1f), maxLines = 2)
                            Icon(Icons.Default.ChevronRight, null, tint = auraAmber, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            if (recent.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.rehab_pick_from_history), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = textMuted)
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    recent.forEach { sentence ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.25f))
                                .clickable { onPick(sentence) }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.History, null, tint = textMuted, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                sentence,
                                fontSize = 13.sp,
                                color = textLight,
                                modifier = Modifier.weight(1f),
                                maxLines = 2
                            )
                            Icon(Icons.Default.ChevronRight, null, tint = neonCyan, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 臨床題庫一列：難度標籤 + 該級題目 chips（點一下進入練習）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClinicalBankRow(
    label: String,
    items: List<String>,
    /** 辨識器認不認得非中文；true＝整題換成介面語言，false＝中文題目＋語意註解 */
    multilingualStt: Boolean,
    onPick: (String) -> Unit,
    accent: Color,
    textLight: Color
) {
    Spacer(Modifier.height(8.dp))
    Text(label, fontSize = 11.sp, color = textLight.copy(alpha = 0.7f))
    Spacer(Modifier.height(4.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items.forEach { zh ->
            // 畫面上的字＝點下去會被拿去比對的字，兩者必須是同一串，
            // 否則使用者唸螢幕上的英文、系統拿中文去比，分數永遠是 0。
            val item = com.example.data.AphasiaPracticeI18n.target(zh, multilingualStt)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .clickable { onPick(item) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                // 題目本身一律維持中文——它是要唸出來、而且由中文語音辨識評分的目標，
                // 換成譯文會讓分數永遠是 0（見 AphasiaPracticeI18n 的說明）。
                // 非中文介面時在下面補一行語意註解，讓照護者看得懂在練什麼。
                val gloss = if (multilingualStt) null else com.example.data.AphasiaPracticeI18n.gloss(zh)
                // lineHeight 一定要跟著這裡的 fontSize 明確給。只寫 fontSize 的話會沿用
                // 主題 bodyLarge 的絕對行高（24sp × 字體倍率），倍率放大時一個 12sp 的字
                // 會被塞進 37sp 的行框裡，卡片被拉成內容的三倍高、兩行之間一片空白。
                // （主題那張表不能改成 em，會讓所有帶 label 的輸入框 crash，見 Type.kt。）
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(item, fontSize = 12.sp, lineHeight = 15.sp, color = textLight)
                    if (gloss != null) {
                        Text(gloss, fontSize = 9.sp, lineHeight = 12.sp,
                            color = textLight.copy(alpha = 0.55f))
                    }
                }
            }
        }
    }
}

// ─── 練習卡片（目標句 + 整句播放 + 字卡 + 跟讀錄音 + 評分）────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PracticeCard(
    rehab: RehabUiState,
    sttState: SttState,
    eyeControl: EyeControlUiState,
    viewModel: VoiceWeaverViewModel,
    surfaceColor: Color,
    neonCyan: Color,
    neonGreen: Color,
    auraAmber: Color,
    dangerRose: Color,
    textLight: Color,
    textMuted: Color
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        border = BorderStroke(1.dp, neonCyan.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.rehab_target_sentence), fontSize = 11.sp, color = textMuted, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { viewModel.clearRehab() },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) { Text(stringResource(R.string.rehab_change), fontSize = 11.sp, color = textMuted) }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                rehab.target,
                fontSize = 20.sp, fontWeight = FontWeight.Bold,
                // 不可以寫死 Color.White：簡約風是白底卡片，寫死就是白字白底＝整句看不見。
                color = textLight, lineHeight = 28.sp
            )
            // 目標句維持中文（要唸的就是它）；非中文介面補一行語意註解給照護者看。
            com.example.data.AphasiaPracticeI18n.gloss(rehab.target)?.let { gloss ->
                Spacer(Modifier.height(4.dp))
                Text(gloss, fontSize = 12.sp, color = textMuted, lineHeight = 16.sp)
            }

            // 整句播放
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.rehab_listen_step), fontSize = 11.sp, color = textMuted)
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = { viewModel.speakRehabSentence() },
                colors = ButtonDefaults.buttonColors(containerColor = neonCyan.copy(alpha = 0.9f)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.rehab_play_sentence), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            // 字卡
            Spacer(Modifier.height(10.dp))
            if (eyeControl.enabled) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(neonCyan.copy(alpha = 0.10f))
                        .border(1.dp, neonCyan.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Visibility, null, tint = neonCyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (eyeControl.mode == EyeInteractionMode.GAZE_DWELL) I18n.tr("凝視停留模式") else I18n.tr("眨眼掃描模式"),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = textLight
                            )
                            Text(eyeControl.message, fontSize = 10.sp, color = textMuted, lineHeight = 14.sp)
                        }
                    }
                    if (eyeControl.mode == EyeInteractionMode.GAZE_DWELL) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(I18n.tr("視線"), fontSize = 10.sp, color = textMuted)
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(16.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(eyeControl.gazeX.coerceIn(0.02f, 1f))
                                        .background(neonCyan.copy(alpha = 0.30f))
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("${(eyeControl.dwellProgress * 100).toInt()}%", fontSize = 10.sp, color = neonCyan)
                        }
                        LinearProgressIndicator(
                            progress = { eyeControl.dwellProgress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(999.dp)),
                            color = neonCyan,
                            trackColor = Color.White.copy(alpha = 0.10f)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            var tapped by remember { mutableStateOf(-1) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                rehab.chips.forEachIndexed { idx, chip ->
                    val scale by animateFloatAsState(if (tapped == idx) 1.3f else 1f, label = "chip")
                    val scanned = eyeControl.enabled && idx == eyeControl.scanIndex
                    // 錯誤字高亮：評分後，字卡內任一字落在 wrongChars → 紅框提示重點練
                    val isWrong = rehab.wrongChars.isNotEmpty() && chip.any { c -> rehab.wrongChars.contains(c.toString()) }
                    Box(
                        modifier = Modifier
                            .padding(bottom = 6.dp)
                            .graphicsLayer {
                                val eyeScale = if (scanned) 1.18f else 1f
                                scaleX = scale * eyeScale
                                scaleY = scale * eyeScale
                            }
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                when {
                                    tapped == idx || scanned -> neonCyan.copy(alpha = 0.35f)
                                    isWrong -> dangerRose.copy(alpha = 0.18f)
                                    else -> Color.White.copy(alpha = 0.06f)
                                }
                            )
                            .border(
                                if (scanned || isWrong) 2.dp else 1.dp,
                                when {
                                    tapped == idx || scanned -> neonCyan
                                    isWrong -> dangerRose
                                    else -> neonCyan.copy(alpha = 0.35f)
                                },
                                RoundedCornerShape(10.dp)
                            )
                            .semantics {
                                role = Role.Button
                                contentDescription = if (isWrong) I18n.tr("復健字卡（需加強）：%s").format(chip) else I18n.tr("復健字卡：%s").format(chip)
                            }
                            .clickable {
                                tapped = idx
                                viewModel.speakRehabChip(idx)
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(chip, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                             color = if (isWrong) dangerRose else textLight)
                    }
                }
            }
            if (rehab.wrongChars.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(I18n.tr("🔴 紅字是這次說得不夠準的字，點字卡再聽一次重點練。"),
                     fontSize = 11.sp, color = dangerRose, lineHeight = 15.sp)
            }

            // 跟讀錄音
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.rehab_speak_step), fontSize = 11.sp, color = textMuted)
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = {
                    if (sttState is SttState.Listening) viewModel.stopRehabRecording()
                    else viewModel.startRehabRecording()
                },
                enabled = sttState !is SttState.Processing && sttState !is SttState.Preparing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = when {
                        sttState is SttState.Listening -> dangerRose
                        sttState is SttState.Error -> auraAmber
                        else -> neonGreen
                    },
                    disabledContainerColor = textMuted.copy(alpha = 0.20f),
                    disabledContentColor = textMuted
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    if (sttState is SttState.Listening) Icons.Default.Stop else Icons.Default.Mic,
                    null, modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        sttState is SttState.Listening -> stringResource(R.string.rehab_stop)
                        sttState is SttState.Processing -> stringResource(R.string.rehab_scoring)
                        sttState is SttState.Preparing -> (sttState as SttState.Preparing).message
                        sttState is SttState.Error -> (sttState as SttState.Error).message
                        else -> stringResource(R.string.rehab_start)
                    },
                    fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
            }
            // 隱私承諾要讓使用者看得到，不是只寫在程式註解裡
            Spacer(Modifier.height(4.dp))
            Text(
                I18n.tr("🔒 跟讀錄音只用來評分，評分完成後立即從手機刪除。"),
                fontSize = 10.sp, color = textMuted, lineHeight = 14.sp
            )

            // 評分結果
            AnimatedVisibility(visible = rehab.lastScore != null) {
                rehab.lastScore?.let { score ->
                    val scoreColor = when {
                        score >= 80 -> neonGreen
                        score >= 50 -> auraAmber
                        else -> dangerRose
                    }
                    Column(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(scoreColor.copy(alpha = 0.12f))
                            .border(1.dp, scoreColor.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("$score", fontSize = 36.sp, fontWeight = FontWeight.Black, color = scoreColor)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.rehab_score_suffix), fontSize = 14.sp, color = scoreColor, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text(
                                when {
                                    score >= 80 -> stringResource(R.string.rehab_score_excellent)
                                    score >= 50 -> stringResource(R.string.rehab_score_good)
                                    else -> stringResource(R.string.rehab_score_again)
                                },
                                fontSize = 11.sp, color = scoreColor
                            )
                        }
                        if (rehab.aiFeedback.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text("💬 ${rehab.aiFeedback}", fontSize = 12.sp, color = scoreColor, fontWeight = FontWeight.Medium)
                        }
                        // AI 分項回饋：流暢度、語調、錯誤內容。只有引擎真的給了才顯示——
                        // 備援的字元比對沒有這些分項，用總分冒充等於給治療師假資料。
                        val fluency = rehab.lastFluency
                        val prosody = rehab.lastProsody
                        if (fluency != null || prosody != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                listOfNotNull(
                                    fluency?.let { I18n.tr("流暢度 %d").format(it) },
                                    prosody?.let { I18n.tr("語調 %d").format(it) }
                                ).joinToString("  ·  "),
                                fontSize = 11.sp, color = textLight
                            )
                        }
                        if (rehab.wrongChars.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                I18n.tr("錯誤內容：%s").format(rehab.wrongChars.take(8).joinToString("、")),
                                fontSize = 11.sp, color = dangerRose, lineHeight = 15.sp
                            )
                        }
                        if (rehab.lastRecognized.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.rehab_heard, rehab.lastRecognized), fontSize = 11.sp, color = textMuted)
                        }
                    }
                }
            }
        }
    }
}

// ─── 場景統計 ─────────────────────────────────────────────────────────

@Composable
private fun StatsCard(
    stats: List<com.example.data.LocationScoreStat>,
    surfaceColor: Color,
    neonGreen: Color,
    auraAmber: Color,
    dangerRose: Color,
    textLight: Color,
    textMuted: Color
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(stringResource(R.string.rehab_location_stats), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = textLight)
            Spacer(Modifier.height(8.dp))
            stats.forEach { s ->
                val color = when {
                    s.avgScore >= 80 -> neonGreen
                    s.avgScore >= 50 -> auraAmber
                    else -> dangerRose
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Text(
                        s.locationTag.ifBlank { stringResource(R.string.rehab_unmarked) },
                        fontSize = 12.sp, color = textLight, modifier = Modifier.weight(1f)
                    )
                    LinearProgressIndicator(
                        progress = { (s.avgScore / 100.0).toFloat().coerceIn(0f, 1f) },
                        color = color, trackColor = Color.DarkGray,
                        modifier = Modifier.weight(1.5f).height(6.dp).clip(RoundedCornerShape(3.dp))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${s.avgScore.toInt()}", fontSize = 12.sp, color = color, fontWeight = FontWeight.Bold)
                    Text(" ·${s.count} ${stringResource(R.string.rehab_times_unit)}", fontSize = 10.sp, color = textMuted)
                }
            }
        }
    }
}

// ─── 單筆練習紀錄 ─────────────────────────────────────────────────────

@Composable
private fun LogRow(
    targetSentence: String,
    locationTag: String,
    score: Int,
    timestamp: Long,
    surfaceColor: Color,
    neonGreen: Color,
    auraAmber: Color,
    dangerRose: Color,
    textLight: Color,
    textMuted: Color
) {
    val scoreColor = when {
        score >= 80 -> neonGreen
        score >= 50 -> auraAmber
        else -> dangerRose
    }
    // Locale.getDefault() 由 LocaleProvider 隨介面語言設定，日期呈現跟著 App 語言走
    val dateFmt = remember(Locale.getDefault()) { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor.copy(alpha = 0.7f)),
        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.06f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(scoreColor.copy(alpha = 0.15f))
                    .border(1.dp, scoreColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("$score", fontSize = 16.sp, fontWeight = FontWeight.Black, color = scoreColor)
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(targetSentence, fontSize = 12.sp, color = textLight, maxLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (locationTag.isNotBlank()) {
                        Icon(Icons.Default.Place, null, tint = textMuted, modifier = Modifier.size(10.dp))
                        Text(" $locationTag · ", fontSize = 10.sp, color = textMuted)
                    }
                    Text(dateFmt.format(Date(timestamp)), fontSize = 10.sp, color = textMuted)
                }
            }
        }
    }
}

/**
 * 「故事訓練」入口卡。專門給開發者模式用：4 格圖卡引導使用者編一段故事，
 * 進去後仍會依 deviceTier 二次把關（HIGH_END 才允許載 LLM 評論模型）。
 */
@Composable
private fun StoryTrainingEntryCard(
    onClick: () -> Unit,
    surfaceColor: androidx.compose.ui.graphics.Color,
    neonCyan: androidx.compose.ui.graphics.Color,
    textLight: androidx.compose.ui.graphics.Color,
    textMuted: androidx.compose.ui.graphics.Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(surfaceColor)
            .border(1.dp, neonCyan.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Icon(Icons.Default.AutoStories, null, tint = neonCyan, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                I18n.tr("故事訓練模式 · 開發者"),
                color = textLight, fontSize = 14.sp, fontWeight = FontWeight.Bold
            )
            Text(
                I18n.tr("看 4 格圖卡編一段故事，AI 老師會即時評論並打分"),
                color = textMuted, fontSize = 11.sp
            )
        }
        Text("›", color = neonCyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}
