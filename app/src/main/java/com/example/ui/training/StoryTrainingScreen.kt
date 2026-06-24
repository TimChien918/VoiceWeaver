package com.example.ui.training

import android.Manifest
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.DeviceTier
import com.example.ai.InferenceEngine
import com.example.ai.countPositiveWords
import com.example.data.DefaultStoryPrompts
import com.example.data.StoryPrompt
import com.example.data.StoryTrainingLog
import com.example.data.StoryTrainingLogDao
import com.example.ui.theme.LocalAppPalette
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 「圖片說故事」復健模式。
 *
 * 開啟條件（Spec 要求）：
 *   `developerMode == true` && `deviceTier == DeviceTier.HighEnd`
 * （HighEnd 對應 RAM ≥ 8GB；中低階機跳出 Lock 卡片，避免在弱機載 LLM）
 *
 * 流程：
 *   1. 顯示 [StoryPrompt] 的 4 個 [com.example.data.StoryFrame]（emoji + hint）
 *   2. 使用者在 OutlinedTextField 用文字 / STT 輸入故事描述（STT 在 ViewModel 端接，這層不管）
 *   3. 按「請 AI 評論」→ 呼叫 [InferenceEngine.suggestPractice]（重用既有 API）給回饋
 *   4. 落地一筆 [StoryTrainingLog]（含 score / aiFeedback / positiveWords）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun StoryTrainingScreen(
    developerMode: Boolean,
    deviceTier: DeviceTier,
    engine: InferenceEngine,
    dao: StoryTrainingLogDao,
    modifier: Modifier = Modifier
) {
    val p = LocalAppPalette.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val micPerm = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    var isListening by remember { mutableStateOf(false) }
    var userText by remember { mutableStateOf("") }

    // STT：用系統 RecognizerIntent（免金鑰、免模型）
    val sttLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isListening = false
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull() ?: return@rememberLauncherForActivityResult
        userText = if (userText.isBlank()) text else "$userText $text"
    }

    fun launchStt() {
        if (!micPerm.status.isGranted) { micPerm.launchPermissionRequest(); return }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        isListening = true
        sttLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "請說出你的故事…")
        })
    }

    if (!developerMode) {
        LockedNotice(
            title = "復健訓練模式 · 未啟用",
            body = "請到「設定 → 開發者選項」打開後再回來。"
        )
        return
    }
    if (deviceTier != DeviceTier.HIGH_END) {
        LockedNotice(
            title = "設備 RAM 不足",
            body = "故事訓練會載入 LLM 評論模型，需要 8GB 以上 RAM。\n您的裝置目前判定為：${deviceTier.name}。"
        )
        return
    }

    var promptIdx by remember { mutableIntStateOf(0) }
    val prompt = remember(promptIdx) { DefaultStoryPrompts[promptIdx % DefaultStoryPrompts.size] }
    var aiFeedback by remember { mutableStateOf<String?>(null) }
    var loadingAi by remember { mutableStateOf(false) }
    var lastScore by remember { mutableIntStateOf(0) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        containerColor = p.darkBg,
        topBar = {
            TopAppBar(
                title = {
                    Text("▌ 圖片說故事", color = p.textLight,
                         fontSize = 17.sp, fontWeight = FontWeight.Bold)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = p.darkBg),
                actions = {
                    IconButton(onClick = {
                        promptIdx++; userText = ""; aiFeedback = null; lastScore = 0
                    }) {
                        Icon(Icons.Default.Refresh, "換一組", tint = p.neonCyan)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    "請看圖說一段故事：「${prompt.title}」",
                    color = p.textLight, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            item { FourFrameGrid(prompt) }
            item {
                OutlinedTextField(
                    value = userText,
                    onValueChange = { userText = it },
                    placeholder = { Text("用文字或語音說出這個故事…", color = p.textMuted) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = p.textLight,
                        unfocusedTextColor = p.textLight,
                        focusedBorderColor = p.neonCyan,
                        unfocusedBorderColor = p.neonCyan.copy(alpha = 0.4f)
                    ),
                    trailingIcon = {
                        IconButton(onClick = { launchStt() }) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = "語音輸入",
                                tint = if (isListening) p.neonCyan else p.textMuted
                            )
                        }
                    }
                )
            }
            item {
                Button(
                    onClick = {
                        scope.launch {
                            loadingAi = true
                            val hits = prompt.expectedKeywords.count { userText.contains(it) }
                            val baseScore = (hits * 100f / prompt.expectedKeywords.size).toInt().coerceIn(0, 100)
                            val feedback = generateFeedback(engine, prompt, userText, baseScore)
                            aiFeedback = feedback
                            lastScore = baseScore
                            dao.insert(
                                StoryTrainingLog(
                                    storyId = prompt.id,
                                    userResponse = userText,
                                    score = baseScore,
                                    aiFeedback = feedback,
                                    positiveWords = countPositiveWords(userText)
                                )
                            )
                            loadingAi = false
                        }
                    },
                    enabled = userText.isNotBlank() && !loadingAi,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = p.neonGreen, contentColor = p.darkBg
                    )
                ) {
                    Text(if (loadingAi) "AI 評論中…" else "請 AI 評論", fontWeight = FontWeight.Bold)
                }
            }
            aiFeedback?.let { fb ->
                item {
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(p.neonGreen.copy(alpha = 0.12f))
                            .border(1.dp, p.neonGreen, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text("AI 老師", color = p.neonGreen,
                             fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(fb, color = p.textLight, fontSize = 14.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("本次評分：$lastScore / 100", color = p.textMuted, fontSize = 12.sp)
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun FourFrameGrid(prompt: StoryPrompt) {
    val p = LocalAppPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FrameCard(prompt.frames[0], 1, Modifier.weight(1f))
            FrameCard(prompt.frames[1], 2, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FrameCard(prompt.frames[2], 3, Modifier.weight(1f))
            FrameCard(prompt.frames[3], 4, Modifier.weight(1f))
        }
    }
}

@Composable
private fun FrameCard(frame: com.example.data.StoryFrame, idx: Int, modifier: Modifier = Modifier) {
    val p = LocalAppPalette.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(p.surface)
            .border(1.dp, p.neonCyan.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("$idx", color = p.neonCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(frame.emoji, fontSize = 56.sp)
        Spacer(Modifier.height(6.dp))
        Text(frame.hint, color = p.textLight, fontSize = 12.sp)
    }
}

@Composable
private fun LockedNotice(title: String, body: String) {
    val p = LocalAppPalette.current
    Box(
        Modifier.fillMaxSize().background(p.darkBg).padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(p.surface)
                .border(1.dp, p.dangerRose.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Lock, null, tint = p.dangerRose, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(8.dp))
            Text(title, color = p.textLight, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(body, color = p.textMuted, fontSize = 13.sp)
        }
    }
}

/**
 * AI 評論的真正呼叫。重用既有 [InferenceEngine.suggestPractice]，
 * 把 user 故事當成「重組片段」、prompt 標題當成情境送進去；engine 內部會走雲端→本地 fallback。
 * 拿不到時 fallback 成基於分數的固定鼓勵文案。
 */
private suspend fun generateFeedback(
    engine: InferenceEngine,
    prompt: StoryPrompt,
    userText: String,
    baseScore: Int
): String {
    val ctx = "故事主題：${prompt.title}（使用者描述：${userText.take(40)}）"
    val result: String? = runCatching {
        engine.suggestPractice(
            avgScore = baseScore.toDouble(),
            location = ctx,
            recent = emptyList()
        ).firstOrNull()
    }.getOrNull()

    val ai = result?.takeIf { it.isNotBlank() }
    return ai ?: when {
        baseScore >= 80 -> "太棒了！您把這個故事完整說出來了，繼續保持 💪"
        baseScore >= 50 -> "說得不錯！下次可以再多提到「${prompt.expectedKeywords.first()}」等關鍵字 🌱"
        else            -> "您願意開口練習就很棒了，再試一次，慢慢來 🌿"
    }
}
