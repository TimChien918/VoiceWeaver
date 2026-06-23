package com.example.ai

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AacCategory
import com.example.data.AacItem
import com.example.data.AppDatabase
import com.example.data.Currency
import com.example.data.customAmountItem
import com.example.data.AppLanguage
import com.example.data.AppSettings
import com.example.data.AppTheme
import com.example.data.CameraLens
import com.example.data.CloudProvider
import com.example.data.EyeControlMode
import com.example.data.GptSovitVoiceMode
import com.example.data.ImageApiEntry
import com.example.data.ImageApiStore
import com.example.data.ImageProvider
import com.example.data.LlmApiEntry
import com.example.data.LlmApiStore
import com.example.data.LlmProvider
import com.example.data.LocationScoreStat
import com.example.data.PowerMode
import com.example.data.RehabLog
import com.example.data.RehabLogRepository
import com.example.data.SettingsRepository
import com.example.data.SpeechRecord
import com.example.data.SpeechRecordRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Task 2.1: 定義全域情境資料結構 (GlobalTagsVault)
 * 用於封裝使用者碎詞、環境GPS與相機辨識特徵。
 */
data class GlobalTagsVault(
    val rawText: String = "",
    val locationTag: String = "",
    val visionTags: List<String> = emptyList(),
    /** 來自 Vosk 的逐字信心度（0~1），供重組 AI 判斷哪些字可能聽錯 */
    val sttWords: List<WordConf> = emptyList()
)

/** 單字（或詞）的辨識信心度 */
data class WordConf(val word: String, val conf: Float)

/**
 * Task 2.2: 依記憶體大小動態調整的效能評級
 */
enum class DeviceTier {
    HIGH_END,  // RAM >= 8GB: 載入完整本地 LLM (如 Gemini Nano)
    MID_TIER,  // RAM >= 4GB: 載入量化版 LLM (如 Phi-3 Mini 4-bit)
    LOW_END    // RAM < 4GB: 嚴禁載入 LLM，僅允許傳統規則模板匹配 (Rule-based Template)
}

/**
 * 語料重組後的資料結構
 */
data class ReconstructedData(
    val originalText: String,
    val reconstructedText: String,
    val confidence: Double,
    val engineUsed: String,
    val inferenceTimeMs: Long,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Task 2.2: 硬體效能檢測器 (HardwareAssessor)
 */
class HardwareAssessor(private val context: Context) {

    /**
     * 讀取裝置的實際可用 Total RAM (以 GB 為單位)
     * 內置錯誤處理與安全邊際容錯值。
     */
    fun getTotalRamGb(): Double {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (activityManager != null) {
                val memoryInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memoryInfo)
                val totalBytes = memoryInfo.totalMem
                // 1 GB = 1024^3 bytes
                totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            } else {
                Log.w("HardwareAssessor", "ActivityManager is null, fallback to 4.0GB")
                4.0
            }
        } catch (e: Exception) {
            Log.e("HardwareAssessor", "Error reading MemoryInfo, returning fallback", e)
            4.0
        }
    }

    /**
     * 判定 DeviceTier
     * 考量到 Android 核心各家廠商對於記憶體總容量報告的些微溢出或系統預留開銷 (OS overhead)，
     * 設計安全容差值（例如：實際 8GB 模組可能回傳 ~7.6GB，實際 4GB 模組可能回傳 ~3.6GB）。
     */
    fun assessDeviceTier(): DeviceTier {
        val actualRamGb = getTotalRamGb()
        return when {
            actualRamGb >= 7.5 -> DeviceTier.HIGH_END
            actualRamGb >= 3.5 -> DeviceTier.MID_TIER
            else -> DeviceTier.LOW_END
        }
    }
}

/**
 * Task 2.3: 依賴注入與介面定義 (InferenceEngine Interface)
 */
interface InferenceEngine {
    /**
     * 重建不連貫的碎詞
     */
    suspend fun reconstructSentence(vault: GlobalTagsVault): Result<ReconstructedData>

    /**
     * 依患者狀況產生 3 句適合「跟讀練習」的繁體中文句子。
     * 預設規則 fallback（無 LLM 時用）；MediaPipe Qwen 引擎會覆寫為真正 AI 生成。
     *
     * @param avgScore 近期跟讀平均分（0~100，未練過給 70）
     * @param location 當前場景（用於情境化句子）
     * @param recent   近期已練過的句子（避免重複）
     */
    suspend fun suggestPractice(
        avgScore: Double,
        location: String,
        recent: List<String>
    ): List<String> = defaultPracticeFallback(avgScore, location)

    /**
     * 把 AAC 圖卡序列組成一句自然的繁體中文口語。
     * 預設只做純串接（fallback for RegexTemplateEngine 或網路掛了）；
     * Hybrid / MediaPipe 引擎會覆寫為真正 LLM 重組。
     */
    suspend fun composeAacSentence(
        items: List<AacItem>,
        location: String
    ): Result<String> = Result.success(items.joinToString("") { it.label })
}

/**
 * 預設句庫 fallback：無 LLM 時依分數與場景挑選。
 * 改用臨床標準失語症題庫（[com.example.data.AphasiaPractice]，CRRCAE 通用復述題），
 * 難度自適應且每次有變化，離線也能給臨床有效的跟讀題。
 */
private fun defaultPracticeFallback(avgScore: Double, location: String): List<String> =
    com.example.data.AphasiaPractice.pick(avgScore, location)


/**
 * LOW-END 安全引擎：純 Kotlin 字典正則比對，絕對零 OOM 風險。
 *
 * 設計守則：
 * - 嚴禁載入任何神經網路模型（不論大小）。
 * - 使用優先權排序的 [RuleEntry] 表，以 [Regex] 匹配碎詞 + 地點 + 視覺標籤。
 * - 所有字串操作均為 O(n·k) 複雜度（n = 規則數，k = 輸入長度），可保證毫秒級回應。
 */
class RegexTemplateEngine : InferenceEngine {

    private data class RuleEntry(
        val keywords: Regex,
        val template: (raw: String, loc: String, vision: String) -> String
    )

    private val rules = listOf(
        RuleEntry(Regex("渴|水|喝|飲|杯|茶")) { _, loc, _ ->
            "在${loc}，我現在感到口渴，可以幫我倒一杯溫水嗎？"
        },
        RuleEntry(Regex("餓|飯|麵|吃|食|餐|便|飽")) { _, loc, _ ->
            "在${loc}，我肚子有些餓了，能幫我準備一些食物嗎？"
        },
        RuleEntry(Regex("痛|不舒服|難受|暈|吐|藥|醫|病|傷")) { _, loc, _ ->
            "在${loc}，我身體感到不適，請幫我拿藥或聯絡醫護人員。"
        },
        RuleEntry(Regex("廁所|尿|便|洗手間|馬桶")) { _, loc, _ ->
            "在${loc}，我需要去廁所，請問廁所在哪裡或幫我扶一下？"
        },
        RuleEntry(Regex("睡|累|床|躺|瞇|睏")) { _, loc, _ ->
            "在${loc}，我感到很疲倦，想要休息一下。"
        },
        RuleEntry(Regex("救|急|跌|倒|扶|幫忙|護士|醫生")) { _, loc, _ ->
            "緊急！在${loc}，我需要立刻幫忙，請快來。"
        },
        RuleEntry(Regex("熱|冷|悶|寒")) { raw, loc, _ ->
            val sensation = if (raw.contains("熱") || raw.contains("悶")) "覺得很熱悶" else "覺得很冷"
            "在${loc}，我${sensation}，能調整一下環境溫度嗎？"
        }
    )

    override suspend fun reconstructSentence(vault: GlobalTagsVault): Result<ReconstructedData> =
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val raw = vault.rawText.trim()
            val loc = vault.locationTag
            val vision = vault.visionTags.joinToString("、")

            val combined = "$raw $loc $vision"   // 合併所有輸入作為比對母體

            val matched = rules.firstOrNull { it.keywords.containsMatchIn(combined) }
            val resultText = matched?.template?.invoke(raw, loc, vision)
                ?: "我想告訴您：「${raw}」，當前位於${loc}。"

            Result.success(
                ReconstructedData(
                    originalText      = raw,
                    reconstructedText = resultText,
                    confidence        = if (matched != null) 0.72 else 0.45,
                    engineUsed        = "RegexTemplateEngine (Zero-LLM Safety Mode)",
                    inferenceTimeMs   = System.currentTimeMillis() - startTime
                )
            )
        }
}

/**
 * 依硬體分級決定推理引擎實例的 Factory。
 *
 * 分流決策樹：
 * ```
 * HIGH_END
 *   └─ AICore 可用？ ─ Yes → GeminiNanoEngine  (NPU 加速本機 Gemini Nano)
 *                   └─ No  → MediaPipeLLMEngine (降級至 Phi-3 INT4)
 * MID_TIER
 *   └── MediaPipeLLMEngine                     (MediaPipe Phi-3 Mini INT4)
 * LOW_END
 *   └── RegexTemplateEngine                    (純字典規則，零 LLM)
 * ```
 */
object InferenceEngineFactory {

    private const val ENABLE_GEMINI_NANO = false
    private const val ENABLE_MEDIAPIPE_LLM = false

    fun createEngine(tier: DeviceTier, context: android.content.Context): InferenceEngine =
        when (tier) {
            DeviceTier.HIGH_END -> {
                when {
                    ENABLE_GEMINI_NANO && GeminiNanoEngine.isAvailable(context) -> {
                        android.util.Log.i("InferenceEngineFactory", "HIGH_END → GeminiNanoEngine")
                        GeminiNanoEngine(context)
                    }
                    ENABLE_MEDIAPIPE_LLM && MediaPipeLLMEngine.isModelReady(context) -> {
                        android.util.Log.w("InferenceEngineFactory", "HIGH_END AICore unavailable → MediaPipeLLMEngine")
                        MediaPipeLLMEngine(context)
                    }
                    else -> {
                        android.util.Log.w("InferenceEngineFactory", "HIGH_END no model available → RegexTemplateEngine")
                        RegexTemplateEngine()
                    }
                }
            }
            DeviceTier.MID_TIER -> {
                if (ENABLE_MEDIAPIPE_LLM && MediaPipeLLMEngine.isModelReady(context)) {
                    android.util.Log.i("InferenceEngineFactory", "MID_TIER → MediaPipeLLMEngine")
                    MediaPipeLLMEngine(context)
                } else {
                    android.util.Log.w("InferenceEngineFactory", "MID_TIER model not present → RegexTemplateEngine")
                    RegexTemplateEngine()
                }
            }
            DeviceTier.LOW_END -> {
                android.util.Log.i("InferenceEngineFactory", "LOW_END → RegexTemplateEngine")
                RegexTemplateEngine()
            }
        }
}

/**
 * StateFlow 狀態管理的對應 ViewModel
 */
class VoiceWeaverViewModel(application: Application) : AndroidViewModel(application) {

    private val assessor = HardwareAssessor(application)
    private val downloader = ModelDownloader(application)
    private var downloadJob: Job? = null
    private val telemetryMonitor = SystemTelemetryMonitor(application)
    private val localAiMaintenance = LocalAiMaintenanceManager(application)
    private var telemetryJob: Job? = null
    private var eyeScanJob: Job? = null
    private var autoModelRepairStarted = false
    private val eyeControlManager = EyeControlManager(
        context = application,
        onBlink = { selectCurrentEyeScanChip() },
        onGaze = { x, y ->
            _eyeControlState.value = _eyeControlState.value.copy(gazeX = x, gazeY = y)
        },
        onStatus = { msg -> _eyeControlState.value = _eyeControlState.value.copy(message = msg) }
    )

    // 給 LocationTracker 用的住家錨點。在 init {} 訂閱 settings flow 之後寫入；
    // homeProvider lambda 透過讀 @Volatile 變數，避免 LocationTracker 拿到舊值。
    @Volatile private var currentHomeAnchor: com.example.ai.HomeAnchor? = null

    // Phase 2 hardware modules
    private val locationTracker = LocationTracker(
        context = application,
        homeProvider = { currentHomeAnchor }
    )
    private val visionCaptureManager = VisionCaptureManager(application)

    // 可用的 Total RAM 狀態
    private val _deviceRam = MutableStateFlow(0.0)
    val deviceRam: StateFlow<Double> = _deviceRam.asStateFlow()

    // 電腦效能分級
    private val _deviceTier = MutableStateFlow(DeviceTier.LOW_END)
    val deviceTier: StateFlow<DeviceTier> = _deviceTier.asStateFlow()

    // 全域情境與對話 StateFlow
    private val _globalTagsVault = MutableStateFlow(GlobalTagsVault("", "客廳 (家)", listOf("水杯", "報紙")))
    val globalTagsVault: StateFlow<GlobalTagsVault> = _globalTagsVault.asStateFlow()

    // 句意重組重構 UI 狀態
    private val _reconstructionState = MutableStateFlow<ReconstructionUiState>(ReconstructionUiState.Idle)
    val reconstructionState: StateFlow<ReconstructionUiState> = _reconstructionState.asStateFlow()

    // 感測運作監控狀態
    private val _isSensingLocation = MutableStateFlow(false)
    val isSensingLocation: StateFlow<Boolean> = _isSensingLocation.asStateFlow()

    private val _isSensingVision = MutableStateFlow(false)
    val isSensingVision: StateFlow<Boolean> = _isSensingVision.asStateFlow()

    // GPS 完整結果（含地址與座標）
    private val _locationResult = MutableStateFlow<LocationResult?>(null)
    val locationResult: StateFlow<LocationResult?> = _locationResult.asStateFlow()

    // STT 用途模式：一般輸入 vs 復健跟讀評分
    private enum class SttMode { NORMAL, REHAB }
    private var sttMode = SttMode.NORMAL
    private var rehabTarget = ""

    // STT（Vosk）— 依 sttMode 將辨識結果導向「碎詞輸入」或「復健評分」
    private val sttManager = SpeechToTextManager(application) { text, words ->
        when (sttMode) {
            SttMode.NORMAL -> {
                val current = _globalTagsVault.value.rawText
                val appended = if (current.isEmpty()) text else "$current $text"
                _globalTagsVault.value = _globalTagsVault.value.copy(rawText = appended, sttWords = words)
            }
            SttMode.REHAB -> {
                sttMode = SttMode.NORMAL
                scoreRehabAttempt(text)
            }
        }
    }
    val sttState: StateFlow<SttState> = sttManager.sttState

    // TTS（TtsModule 在 onInit 內設定語言，無競態問題）
    private val ttsModule = TtsModule(application)
    val ttsInitialized: StateFlow<Boolean> = ttsModule.isInitialized

    // Room DB（同一個 AppDatabase singleton；先前重複呼叫 getDatabase 已合併）
    private val db = AppDatabase.getDatabase(application)
    private val repository = SpeechRecordRepository(db.speechRecordDao())
    val historyRecords: StateFlow<List<SpeechRecord>> = repository.allRecords
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** 復健 TargetPicker 用：去重後的最近 8 句重組史（業務規則屬 ViewModel，不該散在 UI）*/
    val rehabTargetCandidates: StateFlow<List<String>> = repository.allRecords
        .map { records -> records.map { it.reconstructedText }.distinct().take(8) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Room DB 復健日誌
    private val rehabRepository = RehabLogRepository(db.rehabLogDao())

    // AAC 圖卡組合工作記憶（含 1-5 星評分，供 ReportAggregator 與 AI 推薦排序）
    private val aacComboLogRepo = com.example.data.AacComboLogRepository(db.aacComboLogDao())
    val aacComboHistory: StateFlow<List<com.example.data.AacComboLog>> =
        aacComboLogRepo.recent().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** 故事訓練 DAO：給 StoryTrainingScreen 直接 insert */
    val storyTrainingDao: com.example.data.StoryTrainingLogDao = db.storyTrainingLogDao()

    /** 給 ReportScreen 用的聚合器（lazy 避免空跑）*/
    val reportAggregator: com.example.ai.ReportAggregator by lazy {
        com.example.ai.ReportAggregator(
            rehabDao = db.rehabLogDao(),
            aacDao = db.aacComboLogDao(),
            storyDao = db.storyTrainingLogDao()
        )
    }

    /** 給 ExtendedSettingsScreen 直接讀寫 DataStore */
    val settingsRepository: com.example.data.SettingsRepository get() = settingsRepo

    /** 緊急通報攔截器（在 aacConfirm 與重組流程前過濾危機字眼）。
     *  chatId 由 DataStore 即時供給，**不**寫進 BuildConfig，避免重新編譯才能換對象。 */
    private val emergencyTrigger = com.example.safety.EmergencyTrigger(
        locationTracker = locationTracker,
        chatIdProvider = { settingsRepo.settings.firstOrNull()?.emergencyChatId }
    )

    /** 匯入的客製語料庫（合併進 DefaultAacItems 顯示在 AAC 板）*/
    private val _customCorpus = MutableStateFlow<List<com.example.data.AacItem>>(emptyList())
    val customCorpus: StateFlow<List<com.example.data.AacItem>> = _customCorpus.asStateFlow()

    /**
     * AAC 情境提示：根據當前 `globalTagsVault.locationTag` 自動推算「在此場景常用的圖卡」。
     * 例：locationTag = "便利商店·全家" → 結冰水 / 香菸 / 多少錢 / 袋子。
     */
    val aacContextHint: StateFlow<AacContextSuggester.ContextHint?> =
        _globalTagsVault
            .map { vault -> AacContextSuggester.suggest(vault.locationTag) }
            .stateIn(viewModelScope, SharingStarted.Lazily, null)
    val rehabLogs: StateFlow<List<RehabLog>> = rehabRepository.allLogs
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val rehabStats: StateFlow<List<LocationScoreStat>> = rehabRepository.locationStats
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // 復健 UI 狀態
    private val _rehabState = MutableStateFlow(RehabUiState())
    val rehabState: StateFlow<RehabUiState> = _rehabState.asStateFlow()

    // AI 推薦練習句（由 Qwen 依使用者程度生成）
    private val _aiSuggestions = MutableStateFlow<List<String>>(emptyList())
    val aiSuggestions: StateFlow<List<String>> = _aiSuggestions.asStateFlow()

    // AAC 溝通板狀態（檳榔攤情境）
    private val _aacState = MutableStateFlow(AacUiState())
    val aacState: StateFlow<AacUiState> = _aacState.asStateFlow()

    // ─── 發聲安全閘門（醫師臨床建議：候選句二次確認 + 風險分級）────────────────

    /** 朗讀前的風險閘門狀態。NeedConfirm = 醫療/疼痛需確認；Locked = 自傷/拒療鎖定。 */
    sealed class SpeakGate {
        object Idle : SpeakGate()
        data class NeedConfirm(val text: String) : SpeakGate()
        data class Locked(val reason: String) : SpeakGate()
    }

    private val _speakGate = MutableStateFlow<SpeakGate>(SpeakGate.Idle)
    val speakGate: StateFlow<SpeakGate> = _speakGate.asStateFlow()

    /** 行為數據彙總（報表頁顯示，治療師評估趨勢用）。lazy：settingsRepo 在下方才初始化。 */
    val behaviorMetrics: StateFlow<com.example.data.BehaviorMetrics> by lazy {
        settingsRepo.behaviorMetrics.stateIn(
            viewModelScope, SharingStarted.Lazily, com.example.data.BehaviorMetrics()
        )
    }

    private val appOpenAtMs = System.currentTimeMillis()
    private var firstSpeakRecorded = false

    /** 所有實際 TTS 出口（含反應時間指標）。不要直接呼 ttsModule.speak。 */
    private fun doSpeak(text: String) {
        if (!firstSpeakRecorded) {
            firstSpeakRecorded = true
            val latency = System.currentTimeMillis() - appOpenAtMs
            viewModelScope.launch { settingsRepo.recordFirstSpeakLatency(latency) }
        }
        ttsModule.speak(text)
    }

    /**
     * 帶風險分級的朗讀請求：一般 → 直接發聲；醫療/疼痛 → 跳確認；
     * 自傷/拒絕治療 → 鎖定語音輸出僅顯示警示。
     */
    fun requestSpeak(text: String) {
        when (com.example.safety.SafetyGuard.classifyRisk(text)) {
            com.example.safety.SafetyGuard.RiskLevel.NORMAL -> doSpeak(text)
            com.example.safety.SafetyGuard.RiskLevel.CONFIRM ->
                _speakGate.value = SpeakGate.NeedConfirm(text)
            com.example.safety.SafetyGuard.RiskLevel.LOCK -> {
                // 自傷類危機字眼：鎖語音 + 立即觸發緊急通報（取 GPS → 發 Telegram）
                if (com.example.safety.SafetyGuard.containsCrisisSignal(text)) {
                    triggerEmergencyReport(text)
                } else {
                    _speakGate.value = SpeakGate.Locked(
                        "內容涉及高風險意圖，語音輸出已鎖定。請照護者協助確認。"
                    )
                }
            }
        }
    }

    /**
     * 危機字眼共用通報：取 GPS → 發 Telegram → 依結果更新鎖定提示。
     * 主重組與朗讀路徑都走這裡，確保「我想自殺」等字眼一定觸發通報。
     * 同時開啟「與家人對話」危機介入視窗（含前後鏡頭 + 即時音檔）。
     */
    fun triggerEmergencyReport(text: String, poiTag: String = "") {
        _speakGate.value = SpeakGate.Locked("偵測到高風險訊息，正在通報緊急聯絡人…")
        viewModelScope.launch {
            val outcome = runCatching {
                emergencyTrigger.interceptAndReport(text, poiTag = poiTag)
            }.getOrNull()
            _speakGate.value = when (outcome) {
                is com.example.safety.EmergencyTrigger.Outcome.Sent ->
                    SpeakGate.Locked("⚠ 已通報緊急聯絡人。語音輸出已鎖定，請照護者協助。\n如有立即危險請撥打 1925 安心專線或 119。")
                is com.example.safety.EmergencyTrigger.Outcome.Failed ->
                    SpeakGate.Locked("⚠ 偵測到高風險訊息，但通報傳送失敗（${outcome.reason.take(40)}）。請直接撥打 1925 安心專線或 119。")
                else ->
                    SpeakGate.Locked("⚠ 偵測到高風險訊息，語音輸出已鎖定。請撥打 1925 安心專線或 119。")
            }
        }
        // 開啟與家人即時對話的危機介入視窗（安撫情緒）
        startCrisisConversation(text)
    }

    // ─── 危機介入：與家人即時對話視窗 + 前後鏡頭 + 即時音檔 ────────────────────
    enum class CrisisSender { Patient, Family, System }
    data class CrisisChatMsg(val from: CrisisSender, val text: String, val ts: Long = System.currentTimeMillis())
    data class CrisisConvState(
        val active: Boolean = false,
        val status: String = "",
        val messages: List<CrisisChatMsg> = emptyList(),
        val photosSent: Int = 0,
        /** 即時視訊通話房間網址（病患與家人開同一個 = 直接視訊）。 */
        val videoCallUrl: String = "",
        /** 家人輸入 /call 時 +1：UI 觀察到變化就強制開啟視訊房（強制使用者加入）。 */
        val forceJoinCall: Int = 0,
        /** 使用者正在錄語音訊息給家人。 */
        val recordingVoice: Boolean = false
    )

    private val crisisMedia = CrisisMediaCapturer(application)
    private val _crisisConv = MutableStateFlow(CrisisConvState())
    val crisisConv: StateFlow<CrisisConvState> = _crisisConv.asStateFlow()
    private var crisisPollJob: kotlinx.coroutines.Job? = null
    private var crisisPollOffset: Long = 0L

    private fun crisisAppend(msg: CrisisChatMsg) {
        _crisisConv.value = _crisisConv.value.copy(messages = _crisisConv.value.messages + msg)
    }
    private suspend fun crisisChatId(): String? = settingsRepo.settings.firstOrNull()?.emergencyChatId

    /** 開啟與家人的危機對話：擷取前後鏡頭、建立即時視訊房並傳給家人、輪詢家人回覆。 */
    fun startCrisisConversation(triggerText: String) {
        if (_crisisConv.value.active) return
        // 即時視訊通話房：病患與家人開同一個網址即可視訊（Jitsi Meet 免帳號）。房名難猜。
        val room = "VoiceWeaver-" + java.util.UUID.randomUUID().toString().take(8)
        // 直接進入通話：略過 prejoin 名字頁 + 預設顯示名稱（免輸入名字）；
        // 用免登入的社群實例（可在設定改），meet.jit.si 建立會議需登入故不用。
        val base = settings.value.videoCallBaseUrl.trimEnd('/').ifBlank { AppSettings.DEFAULT_VIDEO_CALL_BASE }
        val callUrl = "$base/$room#" +
            "config.prejoinPageEnabled=false&" +
            "config.prejoinConfig.enabled=false&" +
            "config.requireDisplayName=false&" +
            "userInfo.displayName=%22VoiceWeaver%22"
        _crisisConv.value = CrisisConvState(
            active = true,
            status = "正在通知家人…",
            videoCallUrl = callUrl,
            messages = listOf(
                CrisisChatMsg(CrisisSender.System, "已通知家人，請稍等。你並不孤單，我們一起慢慢呼吸。"),
                CrisisChatMsg(CrisisSender.System, "可點下方「開始即時視訊通話」與家人面對面。")
            )
        )
        // 前後鏡頭快照 + 視訊房連結送家人
        viewModelScope.launch {
            val chat = crisisChatId()
            val photos = runCatching { crisisMedia.captureBothCameras() }.getOrDefault(emptyList())
            var sent = 0
            photos.forEach { f ->
                val cap = if (f.name.contains("front")) "🤳 使用者目前畫面" else "🏠 使用者周遭環境"
                if (com.example.net.TelegramClient.sendPhoto(f, cap, chat).isSuccess) sent++
                runCatching { f.delete() }
            }
            // 把視訊通話連結 + 家人可用指令傳給家人
            com.example.net.TelegramClient.sendEmergency(
                "📹 <b>請立即點此與使用者視訊通話：</b>\n$callUrl\n\n" +
                "<b>家人可用指令（直接回覆本對話）：</b>\n" +
                "/call　強制使用者加入視訊\n" +
                "/camera　請使用者再拍一次現場\n" +
                "/end　結束對話（使用者無法自行關閉）\n" +
                "其他文字會直接顯示給使用者看。", chat
            )
            _crisisConv.value = _crisisConv.value.copy(
                photosSent = sent,
                status = "已把現場畫面與視訊連結傳給家人"
            )
            crisisAppend(CrisisChatMsg(CrisisSender.System,
                if (sent > 0) "已把現場前後鏡頭畫面傳給家人，並送出視訊通話連結。" else "已把視訊通話連結傳給家人。"))
        }
        // 輪詢家人回覆 + 指令（/call /camera /end）
        crisisPollOffset = 0L
        crisisPollJob = viewModelScope.launch {
            while (_crisisConv.value.active) {
                val chat = crisisChatId()
                runCatching { com.example.net.TelegramClient.pollFamilyReplies(crisisPollOffset, chat) }
                    .getOrNull()?.getOrNull()?.let { (newOffset, replies) ->
                        crisisPollOffset = newOffset
                        replies.forEach { handleFamilyReply(it.text, chat) }
                        if (replies.isNotEmpty()) {
                            _crisisConv.value = _crisisConv.value.copy(status = "家人正在陪你 💙")
                        }
                    }
                kotlinx.coroutines.delay(3_000)
            }
        }
    }

    /** 處理家人的一則回覆：指令 /call /camera /end，否則當成聊天訊息顯示。 */
    private suspend fun handleFamilyReply(raw: String, chat: String?) {
        val cmd = raw.trim().lowercase()
        when {
            cmd == "/call" -> {
                crisisAppend(CrisisChatMsg(CrisisSender.System, "家人發起視訊通話，正在自動加入…"))
                // 強制使用者加入：+1 讓 UI 觀察到並開啟視訊房
                _crisisConv.value = _crisisConv.value.copy(forceJoinCall = _crisisConv.value.forceJoinCall + 1)
                com.example.net.TelegramClient.sendEmergency("✅ 已要求使用者加入視訊。", chat)
            }
            cmd.startsWith("/cam") -> {  // /camera /camra 都接受
                crisisAppend(CrisisChatMsg(CrisisSender.System, "家人要求再次拍攝現場，拍攝中…"))
                val photos = runCatching { crisisMedia.captureBothCameras() }.getOrDefault(emptyList())
                var sent = 0
                photos.forEach { f ->
                    val cap = if (f.name.contains("front")) "🤳 使用者目前畫面（再拍）" else "🏠 使用者周遭環境（再拍）"
                    if (com.example.net.TelegramClient.sendPhoto(f, cap, chat).isSuccess) sent++
                    runCatching { f.delete() }
                }
                crisisAppend(CrisisChatMsg(CrisisSender.System,
                    if (sent > 0) "已再次把現場畫面傳給家人。" else "再次拍攝失敗（請確認相機權限）。"))
            }
            cmd == "/end" -> {
                crisisAppend(CrisisChatMsg(CrisisSender.System, "家人已結束對話，好好照顧自己 💙"))
                com.example.net.TelegramClient.sendEmergency("🟢 對話已結束。", chat)
                kotlinx.coroutines.delay(1_200)
                closeCrisisConversation()
            }
            cmd.startsWith("/") -> {
                crisisAppend(CrisisChatMsg(CrisisSender.System, "（未知指令：$raw）可用 /call /camera /end"))
            }
            else -> crisisAppend(CrisisChatMsg(CrisisSender.Family, raw))
        }
    }

    /** 使用者主動錄一段語音訊息傳給家人（約 6 秒）。 */
    fun recordAndSendCrisisVoice() {
        if (_crisisConv.value.recordingVoice) return
        viewModelScope.launch {
            if (!crisisMedia.startVoice()) {
                crisisAppend(CrisisChatMsg(CrisisSender.System, "（無法錄音，請確認麥克風權限）"))
                return@launch
            }
            _crisisConv.value = _crisisConv.value.copy(recordingVoice = true)
            kotlinx.coroutines.delay(6_000)
            val clip = crisisMedia.stopVoice()
            _crisisConv.value = _crisisConv.value.copy(recordingVoice = false)
            if (clip != null) {
                crisisAppend(CrisisChatMsg(CrisisSender.Patient, "🎤 已傳一段語音給家人"))
                com.example.net.TelegramClient.sendVoiceClip(clip, "🎙 使用者的語音訊息", crisisChatId())
                runCatching { clip.delete() }
            } else {
                crisisAppend(CrisisChatMsg(CrisisSender.System, "語音太短，未送出。"))
            }
        }
    }

    /** 病患在對話視窗送訊息給家人（快捷安撫句或自訂）。 */
    fun sendCrisisMessage(text: String) {
        if (text.isBlank()) return
        crisisAppend(CrisisChatMsg(CrisisSender.Patient, text))
        viewModelScope.launch {
            val chat = crisisChatId()
            com.example.net.TelegramClient.sendEmergency("🗣 使用者說：$text", chat)
        }
    }

    /** 開啟即時視訊通話（病患端用網頁跳轉到視訊房）。 */
    fun crisisVideoCallUrl(): String = _crisisConv.value.videoCallUrl

    /** 關閉危機對話：停止輪詢。 */
    fun closeCrisisConversation() {
        crisisPollJob?.cancel(); crisisPollJob = null
        _crisisConv.value = CrisisConvState(active = false)
    }

    /** 風險確認對話框按「確認朗讀」。 */
    fun confirmGatedSpeak() {
        (_speakGate.value as? SpeakGate.NeedConfirm)?.let { doSpeak(it.text) }
        _speakGate.value = SpeakGate.Idle
    }

    fun dismissSpeakGate() { _speakGate.value = SpeakGate.Idle }

    /** 重組候選句被點選：index 0 = AI 第一候選（記選句成功率），之後過風險閘門。 */
    fun chooseCandidate(index: Int, text: String) {
        viewModelScope.launch { settingsRepo.recordCandidateChoice(index == 0) }
        requestSpeak(text)
    }

    /** 臨床高頻一鍵發聲：固定句、預先核可，不過 LLM 直接朗讀。 */
    fun speakClinicalPhrase(phrase: String) = doSpeak(phrase)
    private val _isGeneratingSuggestions = MutableStateFlow(false)
    val isGeneratingSuggestions: StateFlow<Boolean> = _isGeneratingSuggestions.asStateFlow()

    // App 全域設定（主題/語言/耗能/TTS 語速/字體/復健自動寫日誌）— DataStore 持久化
    private val settingsRepo = SettingsRepository(application)
    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .onEach { s ->
            // 把住家設定餵給 LocationTracker（每次設定變動都會重算）
            currentHomeAnchor = if (s.hasHome) {
                com.example.ai.HomeAnchor(s.homeLatitude, s.homeLongitude, s.homeRadiusMeters)
            } else null
            ttsModule.configureGptSovit(
                enabled = s.gptSovitEnabled && s.developerMode,
                mode = s.gptSovitVoiceMode,
                chineseVoiceUri = s.gptSovitChineseVoiceUri,
                englishVoiceUri = s.gptSovitEnglishVoiceUri,
                modelDirUri = s.gptSovitModelDirUri,
                chineseModelDirUri = s.gptSovitChineseModelDirUri,
                englishModelDirUri = s.gptSovitEnglishModelDirUri,
                apiBaseUrl = s.gptSovitApiBaseUrl
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    // MediaPipe 模型下載狀態
    private val _modelDownloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val modelDownloadState: StateFlow<ModelDownloadState> = _modelDownloadState.asStateFlow()

    private val _modelProbeState = MutableStateFlow<ModelProbeState>(ModelProbeState.Idle)
    val modelProbeState: StateFlow<ModelProbeState> = _modelProbeState.asStateFlow()

    private val _deviceMetrics = MutableStateFlow<List<DeviceMetricSample>>(emptyList())
    val deviceMetrics: StateFlow<List<DeviceMetricSample>> = _deviceMetrics.asStateFlow()

    /** 「電腦接手計畫」：依實測連線速度算出語音/生圖/文字各由誰處理。UI 即時顯示、分派時參考。 */
    private val _offloadPlan = MutableStateFlow(OffloadManager.OffloadPlan.OFFLINE)
    val offloadPlan: StateFlow<OffloadManager.OffloadPlan> = _offloadPlan.asStateFlow()

    /** 重新量測連線並更新接手計畫（設定頁開啟、手動重整、或連線設定變動時呼叫）。 */
    fun refreshOffloadPlan() {
        viewModelScope.launch {
            val base = settings.value.gptSovitApiBaseUrl.ifBlank { LocalSovitsHelper.DEFAULT_LOCAL_URL }
            _offloadPlan.value = OffloadManager.probeAndPlan(base)
        }
    }

    // ── 雲端同步（Firebase，與網頁版共用 users/{uid}）──────────────
    private val _cloudUser = MutableStateFlow(CloudSync.displayName)
    val cloudUser: StateFlow<String?> = _cloudUser.asStateFlow()
    private val _cloudStatus = MutableStateFlow(if (CloudSync.isSignedIn) "已登入" else "未登入")
    val cloudStatus: StateFlow<String> = _cloudStatus.asStateFlow()
    @Volatile private var suppressPushUntil = 0L

    fun cloudSignInWithGoogle(idToken: String?, errorCode: Int? = null) {
        if (idToken.isNullOrBlank()) {
            _cloudStatus.value = when (errorCode) {
                12501, 12502 -> "Google 登入取消"          // 使用者自己取消
                10 -> "登入設定錯誤（10）：SHA-1 未註冊或 google-services.json 過舊，請重新下載設定檔並重建"
                7 -> "網路錯誤（7）：請檢查連線後再試"
                null -> "Google 登入取消"
                else -> "Google 登入失敗（錯誤碼 $errorCode）"
            }
            return
        }
        CloudSync.signInWithGoogle(idToken) { ok, msg ->
            _cloudStatus.value = msg
            if (ok) { _cloudUser.value = CloudSync.displayName; pullCloud() }
        }
    }
    fun cloudSignInAnon() {
        CloudSync.signInAnon { ok, msg ->
            _cloudStatus.value = msg
            if (ok) { _cloudUser.value = CloudSync.displayName; pullCloud() }
        }
    }
    fun cloudSignOut() {
        CloudSync.signOut(getApplication())
        _cloudUser.value = null; _cloudStatus.value = "已登出"
    }
    private fun pullCloud() {
        CloudSync.pull { llm, img, tg, chat ->
            viewModelScope.launch {
                suppressPushUntil = System.currentTimeMillis() + 4000L   // 拉下來後短暫不要又推回去
                if (!llm.isNullOrBlank()) settingsRepo.setLlmApis(llm)
                if (!img.isNullOrBlank()) settingsRepo.setImageApis(img)
                if (!tg.isNullOrBlank()) settingsRepo.setApiKeyTelegramBotToken(tg)
                if (!chat.isNullOrBlank()) settingsRepo.setEmergencyChatId(chat)
                _cloudStatus.value = "已從雲端同步金鑰/供應商"
            }
        }
    }

    /** 一次性硬體資訊（SoC / GPU 名稱 / CPU 核數），給效能監控標題列用。
     *  不放 StateFlow 因為它不會改變；UI 直接讀取即可。 */
    val deviceHardware: DeviceHardwareInfo by lazy { telemetryMonitor.readHardwareInfo() }

    private val _eyeControlState = MutableStateFlow(EyeControlUiState())
    val eyeControlState: StateFlow<EyeControlUiState> = _eyeControlState.asStateFlow()
    private var rehabScreenVisible = false
    private var aacScreenVisible = false
    private var gazeTargetIndex = -1
    private var gazeTargetStartedAt = 0L
    private var lastGazeSelectAt = 0L

    // 當前運作中的推理引擎實例
    private lateinit var activeEngine: InferenceEngine
    /** 給外部畫面（StoryTrainingScreen / 其他）只讀取用的目前生效 LLM 引擎 */
    val currentEngine: InferenceEngine get() = activeEngine

    init {
        detectHardwareAndInitialize()
        // 背景定期量測「電腦接手計畫」：開機先測一次，之後每 25 秒輕量重測（socket+小封包，幾乎不耗電）。
        viewModelScope.launch {
            while (true) {
                runCatching { refreshOffloadPlan() }
                kotlinx.coroutines.delay(25_000)
            }
        }
        // 登入時自動把雲端的金鑰/供應商拉下來
        if (CloudSync.isSignedIn) pullCloud()
        // 金鑰/供應商/Telegram 變動 → 防抖 1.5 秒後自動推上雲（登入時才推）
        viewModelScope.launch {
            var key = ""
            while (true) {
                val s = settings.value
                val cur = s.llmApis + "" + s.imageApis + "" + s.apiKeyTelegramBotToken + "" + s.emergencyChatId
                if (cur != key && CloudSync.isSignedIn && System.currentTimeMillis() > suppressPushUntil) {
                    key = cur
                    runCatching { CloudSync.push(s.llmApis, s.imageApis, s.apiKeyTelegramBotToken, s.emergencyChatId) }
                } else if (cur != key) {
                    key = cur   // 未登入也記住，避免登入後一次大量推送舊狀態
                }
                kotlinx.coroutines.delay(1500)
            }
        }
        // 第一次啟動：設定 SoVITS 預設 API 位址 + 從 .env(BuildConfig) 種入 API 金鑰。
        // 註：App 不內建任何語音/角色資產 — 聲音模型與參考音皆由使用者自行匯入（手機留檔）。
        viewModelScope.launch {
            runCatching {
                val snapshot = settings.firstOrNull() ?: AppSettings()
                // 預設 API URL 指向本機 127.0.0.1:9880 — 配合電腦端 api_v2.py。
                // 沒在跑 → TtsModule 會 fallback 到系統 TTS，不會出錯。
                if (snapshot.gptSovitApiBaseUrl.isBlank()) {
                    settingsRepo.setGptSovitApiBaseUrl(LocalSovitsHelper.DEFAULT_LOCAL_URL)
                }
                // API 金鑰：首次啟動從 BuildConfig（gitignored .env）種入到裝置 DataStore，
                // 之後使用者可在「API 金鑰管理」浮動視窗改。金鑰不會出現在版控原始碼。
                fun seedKey(current: String, build: String?, set: suspend (String) -> Unit) {
                    val v = build.orEmpty()
                    if (current.isBlank() && v.isNotBlank() && !v.startsWith("YOUR_")) {
                        viewModelScope.launch { set(v) }
                    }
                }
                runCatching {
                    seedKey(snapshot.customApiKey, com.example.BuildConfig.GEMINI_IMAGE_API_KEY) { settingsRepo.setCustomApiKey(it) }
                    seedKey(snapshot.apiKeyGroq, com.example.BuildConfig.GROQ_API_KEY) { settingsRepo.setApiKeyGroq(it) }
                    seedKey(snapshot.apiKeyOpenRouter, com.example.BuildConfig.OPENROUTER_API_KEY) { settingsRepo.setApiKeyOpenRouter(it) }
                    seedKey(snapshot.apiKeyCohere, com.example.BuildConfig.COHERE_API_KEY) { settingsRepo.setApiKeyCohere(it) }
                    seedKey(snapshot.apiKeyGemini, com.example.BuildConfig.GEMINI_API_KEY) { settingsRepo.setApiKeyGemini(it) }
                    seedKey(snapshot.apiKeyGooglePlaces, com.example.BuildConfig.GOOGLE_PLACES_API_KEY) { settingsRepo.setApiKeyGooglePlaces(it) }
                    seedKey(snapshot.apiKeyTelegramBotToken, com.example.BuildConfig.TELEGRAM_BOT_TOKEN) { settingsRepo.setApiKeyTelegramBotToken(it) }
                }
            }.onFailure { android.util.Log.w("VoiceWeaverViewModel", "first-launch bootstrap failed: ${it.message}") }
        }
        // 啟動時偵測 server 目前載入的語音模型（detectedZhCharacter 是行程內狀態，
        // App 重啟後會掉回預設值 — 偵測後 ref/prompt 才會對上使用者匯入的模型）。
        viewModelScope.launch {
            val s = settings.firstOrNull() ?: return@launch
            if (s.gptSovitEnabled && s.gptSovitApiBaseUrl.isNotBlank()) {
                runCatching { LocalSovitsHelper.pingServer(s.gptSovitApiBaseUrl) }
            }
        }
        // 讓 API 金鑰覆蓋層跟著設定即時更新（consumer 同步讀取，免改 suspend）
        viewModelScope.launch {
            settings.collect { com.example.data.ApiKeyOverrides.update(it) }
        }
        viewModelScope.launch {
            settings.map { it.cloudProvider }.distinctUntilChanged().collect {
                if (::activeEngine.isInitialized) {
                    rebuildEngineForCurrentTier()
                }
            }
        }
        viewModelScope.launch {
            settings.map { it.developerMode }.distinctUntilChanged().collect { enabled ->
                if (enabled) startTelemetry() else stopTelemetry()
            }
        }
        viewModelScope.launch {
            settings.map { it.eyeControlEnabled }.distinctUntilChanged().collect { enabled ->
                if (enabled) refreshEyeControlLifecycle() else stopEyeControl()
            }
        }
    }

    /**
     * 硬體檢測與引擎分發初始化
     */
    fun detectHardwareAndInitialize() {
        val ram = assessor.getTotalRamGb()
        val tier = assessor.assessDeviceTier()

        _deviceRam.value = ram
        _deviceTier.value = tier
        activeEngine = createConfiguredEngine(tier)

        val localEngine = activeEngine.localFallback()
        when {
            localEngine is MediaPipeLLMEngine -> {
                // 模型檔案已存在，預熱載入
                viewModelScope.launch {
                    _modelDownloadState.value = ModelDownloadState.Downloading(0)
                    val result = localEngine.initialize { pct ->
                        _modelDownloadState.value = ModelDownloadState.Downloading(pct)
                    }
                    _modelDownloadState.value = if (result.isSuccess) ModelDownloadState.Ready
                    else ModelDownloadState.Error(result.exceptionOrNull()?.message ?: "模型載入失敗")
                }
            }
            (tier == DeviceTier.MID_TIER || tier == DeviceTier.HIGH_END) &&
            localEngine is RegexTemplateEngine -> {
                // 硬體支援但模型未放置，顯示安裝說明
                val path = MediaPipeLLMEngine.modelPath(getApplication())
                _modelDownloadState.value = ModelDownloadState.NotAvailable(
                    "MediaPipe 本機 LLM 已暫停載入，避免不相容模型造成啟動閃退。\n\n" +
                    "目前已降級至規則引擎；雲端 LLM 設定仍可使用。\n\n" +
                    "程式會自動補齊缺失模型到：\n$path"
                )
                autoCompleteMissingLocalAiModel()
            }
        }
    }

    private fun createConfiguredEngine(tier: DeviceTier): InferenceEngine {
        val local = InferenceEngineFactory.createEngine(tier, getApplication())
        return HybridInferenceEngine.wrapIfNeeded(getApplication(), settings.value.cloudProvider, local)
    }

    private fun InferenceEngine.localFallback(): InferenceEngine =
        (this as? HybridInferenceEngine)?.fallbackEngine ?: this

    private fun rebuildEngineForCurrentTier() {
        activeEngine = createConfiguredEngine(_deviceTier.value)
        val local = activeEngine.localFallback()
        if (local is MediaPipeLLMEngine && _modelDownloadState.value !is ModelDownloadState.Ready) {
            viewModelScope.launch {
                _modelDownloadState.value = ModelDownloadState.Downloading(0)
                val result = local.initialize { pct ->
                    _modelDownloadState.value = ModelDownloadState.Downloading(pct)
                }
                _modelDownloadState.value = if (result.isSuccess) {
                    ModelDownloadState.Ready
                } else {
                    ModelDownloadState.Error(result.exceptionOrNull()?.message ?: "模型載入失敗")
                }
            }
        }
    }

    /**
     * 提供動態切換硬體分級的功能（開發者診斷功能），模擬在不同硬體上操作的反應與重構文句
     */
    fun toggleDeviceTierOverride(overrideTier: DeviceTier) {
        _deviceTier.value = overrideTier
        activeEngine = createConfiguredEngine(overrideTier)
        _deviceRam.value = when (overrideTier) {
            DeviceTier.HIGH_END -> 12.0
            DeviceTier.MID_TIER -> 6.0
            DeviceTier.LOW_END -> 2.5
        }
    }

    /**
     * 動態單次觸發 GPS 定位與 Geocoder 反向解碼
     */
    fun startSensingLocation() {
        _isSensingLocation.value = true
        viewModelScope.launch {
            try {
                val result = locationTracker.getCurrentLocation()
                if (result != null) {
                    _locationResult.value = result
                    _globalTagsVault.value = _globalTagsVault.value.copy(locationTag = result.shortTag)
                }
            } catch (e: Exception) {
                Log.e("VoiceWeaverViewModel", "Error sensing location", e)
            } finally {
                _isSensingLocation.value = false
            }
        }
    }

    /**
     * 取一次目前 GPS，反查地址後回傳 Triple(地址, 經度, 緯度)。
     * 給 ExtendedSettingsScreen 的「用目前 GPS 當住家」按鈕用。
     * 失敗（沒權限 / GPS 關閉 / Geocoder 沒結果）→ 回 null。
     */
    suspend fun useCurrentGpsAsHome(): Triple<String, Double, Double>? {
        val r = runCatching { locationTracker.getCurrentLocation() }.getOrNull() ?: return null
        return Triple(r.address.ifBlank { "%.4f, %.4f".format(r.latitude, r.longitude) },
                      r.latitude, r.longitude)
    }

    /**
     * 主畫面位置卡的「📍 設為我家」一鍵動作：
     *   1) 取目前 GPS 反查地址
     *   2) 直接寫進 settings（不用跳到進階設定畫面）
     *   3) 立即重新觸發一次定位，下次 result 就是「住家·XXX」
     *
     * 回傳 true = 成功設定；false = 沒抓到 GPS（沒權限 / 室內收不到）。
     * 之所以放在 ViewModel：UI 不該直接碰 SettingsRepository 寫入 + 觸發 reload；
     * 把整個動作收成單一原子方法，主畫面只要 collect 結果就好。
     */
    fun setCurrentGpsAsHomeNow(onDone: (success: Boolean, address: String?) -> Unit) {
        viewModelScope.launch {
            val r = useCurrentGpsAsHome()
            if (r == null) { onDone(false, null); return@launch }
            val (addr, lat, lon) = r
            settingsRepository.setHome(addr, lat, lon)
            // 立刻重抓一次：currentHomeAnchor 透過 settings flow onEach 更新，
            // 但下一次 sense 用到才會看到新值，主動觸發一次比較有感
            sensingTrigger()
            onDone(true, addr)
        }
    }

    /** 主畫面位置卡的「重抓 GPS」：等於使用者再按一次 GPS 按鈕 */
    private fun sensingTrigger() {
        // 重用既有的 startSensingLocation 入口 — 一旦 currentHomeAnchor 在 radius 內，會直接回「住家」
        startSensingLocation()
    }

    /**
     * 開發者測試：用模擬經緯度跑一次完整的 Geocoder + POI 規則分類，
     * 不需要實體 GPS、不需要 mock location app。結果一樣寫進 [_locationResult] 與
     * [_globalTagsVault.locationTag]，主畫面立刻顯示。
     */
    fun simulateLocation(lat: Double, lon: Double) {
        _isSensingLocation.value = true
        viewModelScope.launch {
            try {
                val result = locationTracker.simulateGeocode(lat, lon)
                _locationResult.value = result
                _globalTagsVault.value = _globalTagsVault.value.copy(locationTag = result.shortTag)
                Log.i("VoiceWeaverViewModel",
                    "[SimulateLocation] ($lat,$lon) → shortTag='${result.shortTag}', address='${result.address}'")
            } catch (e: Exception) {
                Log.e("VoiceWeaverViewModel", "simulateLocation failed", e)
            } finally {
                _isSensingLocation.value = false
            }
        }
    }

    /**
     * 動態單次無預覽 CameraX 擷取 + ML Kit 物件分類
     */
    fun startSensingVision(lifecycleOwner: androidx.lifecycle.LifecycleOwner? = null) {
        _isSensingVision.value = true
        viewModelScope.launch {
            try {
                val tags = visionCaptureManager.captureAndAnalyzeSingleFrame(
                    lifecycleOwner = lifecycleOwner,
                    lens = settings.value.cameraLens
                )
                _globalTagsVault.value = _globalTagsVault.value.copy(visionTags = tags)
            } catch (e: Exception) {
                Log.e("VoiceWeaverViewModel", "Error scanning vision objects", e)
            } finally {
                _isSensingVision.value = false
            }
        }
    }

    // ─── STT ─────────────────────────────────────────────────────────────────

    fun toggleStt() {
        viewModelScope.launch(Dispatchers.Main) {
            when (sttManager.sttState.value) {
                is SttState.Listening -> sttManager.stopListening()
                is SttState.Processing -> {}   // 辨識結算中，忽略
                is SttState.Preparing -> {}    // 模型準備中，忽略
                else -> sttManager.startListening()
            }
        }
    }

    // ─── TTS ─────────────────────────────────────────────────────────────────

    fun speak(text: String) = ttsModule.speak(text)
    /** 即時轉發 TtsModule 的合成狀態（⏳/✅/❌ 人話訊息），給設定頁測試按鈕下方顯示。 */
    val sovitsSynthesisStatus: StateFlow<String?> get() = ttsModule.synthesisStatus

    /** 每種語言的測試句（句子帶該語言獨有字元 → TtsModule.detectLang 自動切對應模型）。 */
    fun testSentenceFor(lang: LocalSovitsHelper.VoiceLang): String = when (lang) {
        LocalSovitsHelper.VoiceLang.EN -> "Hello, this is a voice synthesis test. The weather is nice today."
        LocalSovitsHelper.VoiceLang.JA -> "こんにちは、これは音声合成のテストです。今日はいい天気ですね。"
        LocalSovitsHelper.VoiceLang.KO -> "안녕하세요, 이것은 음성 합성 테스트입니다. 오늘 날씨가 좋네요."
        else -> "你好，這是語音合成測試，今天天氣很好。"
    }

    /** 指定語言的合成測試（中/英/日/韓各一顆按鈕用）。 */
    fun testGptSovitLang(lang: LocalSovitsHelper.VoiceLang, onComplete: () -> Unit = {}) {
        ttsModule.speakGptSovitTest(testSentenceFor(lang), GptSovitVoiceMode.Auto, onComplete)
    }

    /** 預設測試：跟隨系統/介面語言（向後相容用）。 */
    fun testGptSovitBilingual(onComplete: () -> Unit = {}) {
        testGptSovitLang(LocalSovitsHelper.langFromAppTag(settings.value.language.tag), onComplete)
    }
    fun stopSpeaking() = ttsModule.stopSpeaking()

    // ─── 語音復健（Task 5.1 / 5.2 / 5.3）────────────────────────────────────────

    /** 進入復健模式：把重組句拆成 2 字一組的字卡（視覺=聽覺，破音字由相鄰字提供上下文）*/
    fun startRehab(sentence: String) {
        _rehabState.value = RehabUiState(
            target = sentence,
            chips = sentence.toRehabChips(),
            lastRecognized = "",
            lastScore = null,
            isRecording = false
        )
        refreshEyeControlLifecycle()
    }

    fun clearRehab() {
        _rehabState.value = RehabUiState()
        refreshEyeControlLifecycle()
    }

    /** 點字卡：單獨播放該字／詞發音 */
    fun speakRehabToken(token: String) = ttsModule.speak(token)

    /**
     * 點字卡：直接播放該字卡文字（chip 本身已是 2 字組，TTS 有上下文，破音字自然解決）。
     */
    fun speakRehabChip(index: Int) {
        val chips = _rehabState.value.chips
        if (index !in chips.indices) return
        Log.i("VoiceWeaverViewModel", "speakRehabChip(idx=$index, chip=\"${chips[index]}\")")
        ttsModule.speak(chips[index])
    }

    fun selectCurrentEyeScanChip() {
        val chips = _rehabState.value.chips
        if (chips.isEmpty()) return
        val index = _eyeControlState.value.scanIndex.coerceIn(0, chips.lastIndex)
        _eyeControlState.value = _eyeControlState.value.copy(lastBlinkAt = System.currentTimeMillis())
        speakRehabChip(index)
    }

    /** 播放整句 */
    fun speakRehabSentence() {
        val t = _rehabState.value.target
        if (t.isNotBlank()) ttsModule.speak(t)
    }

    /** 開始跟讀錄音（走 Vosk，REHAB 模式）*/
    fun startRehabRecording() {
        val target = _rehabState.value.target
        if (target.isBlank()) return
        rehabTarget = target
        sttMode = SttMode.REHAB
        _rehabState.value = _rehabState.value.copy(isRecording = true)
        viewModelScope.launch(Dispatchers.Main) { sttManager.startListening() }
    }

    fun stopRehabRecording() {
        viewModelScope.launch(Dispatchers.Main) { sttManager.stopListening() }
    }

    /** Vosk 跟讀結果 → Levenshtein 評分 → 寫入復健日誌 */
    private fun scoreRehabAttempt(recognized: String) {
        viewModelScope.launch {
            val target = rehabTarget
            val score = PronunciationEvaluator.evaluatePronunciation(target, recognized)
            _rehabState.value = _rehabState.value.copy(
                lastRecognized = recognized,
                lastScore = score,
                isRecording = false
            )
            rehabRepository.insert(
                RehabLog(
                    locationTag = _globalTagsVault.value.locationTag,
                    targetSentence = target,
                    score = score
                )
            )
        }
    }

    fun clearRehabLogs() {
        viewModelScope.launch { rehabRepository.clearAll() }
    }

    /**
     * 請 Qwen 依「目前場景 + 近期跟讀平均分 + 已練過清單」生成 3 句適合的練習句。
     * 完成後寫入 [aiSuggestions]，UI 可挑選一句進入練習模式。
     */
    fun requestAiSuggestions() {
        if (_isGeneratingSuggestions.value) return
        _isGeneratingSuggestions.value = true
        viewModelScope.launch {
            try {
                val location = _globalTagsVault.value.locationTag
                // 取當前場景的平均分；無紀錄則全場景平均；再無則給 70 視為新手
                val stats = rehabStats.value
                val avgScore = stats.firstOrNull { it.locationTag == location }?.avgScore
                    ?: stats.map { it.avgScore }.average().takeIf { !it.isNaN() }
                    ?: 70.0
                val recent = rehabLogs.value.take(5).map { it.targetSentence }
                _aiSuggestions.value = activeEngine.suggestPractice(avgScore, location, recent)
            } catch (e: Exception) {
                Log.e("VoiceWeaverViewModel", "AI suggest failed", e)
            } finally {
                _isGeneratingSuggestions.value = false
            }
        }
    }

    fun clearAiSuggestions() { _aiSuggestions.value = emptyList() }

    fun enterRehabScreen() {
        rehabScreenVisible = true
        refreshEyeControlLifecycle()
    }

    fun exitRehabScreen() {
        rehabScreenVisible = false
        refreshEyeControlLifecycle()
    }

    fun enterAacScreen() {
        aacScreenVisible = true
        refreshEyeControlLifecycle()
    }

    fun exitAacScreen() {
        aacScreenVisible = false
        refreshEyeControlLifecycle()
    }

    // ─── 設定 ───────────────────────────────────────────────────────────────

    fun setTheme(theme: AppTheme) = viewModelScope.launch { settingsRepo.setTheme(theme) }
    fun setLanguage(lang: AppLanguage) = viewModelScope.launch { settingsRepo.setLanguage(lang) }
    fun setPowerMode(mode: PowerMode) = viewModelScope.launch {
        settingsRepo.setPowerMode(mode)
        // 立即把耗能模式對應到 DeviceTier override：省電→LOW、標準→自動、高效能→HIGH
        val tier = when (mode) {
            PowerMode.Saver -> DeviceTier.LOW_END
            PowerMode.Balanced -> assessor.assessDeviceTier()
            PowerMode.Performance -> DeviceTier.HIGH_END
        }
        toggleDeviceTierOverride(tier)
    }
    fun setTtsRate(rate: Float) = viewModelScope.launch {
        settingsRepo.setTtsRate(rate)
        ttsModule.setSpeechRate(rate)   // 立即生效
    }
    fun setFontScale(scale: Float) = viewModelScope.launch { settingsRepo.setFontScale(scale) }
    fun setAutoSaveRehab(on: Boolean) = viewModelScope.launch { settingsRepo.setAutoSaveRehab(on) }
    fun setDeveloperMode(on: Boolean) = viewModelScope.launch { settingsRepo.setDeveloperMode(on) }
    fun setAutoLocalAiCleanup(on: Boolean) = viewModelScope.launch { settingsRepo.setAutoLocalAiCleanup(on) }
    /** 眼控置中校準：請使用者注視螢幕中央後呼叫，把當前視線設為中心。 */
    fun recenterEyeControl() {
        eyeControlManager.recenter()
        _eyeControlState.value = _eyeControlState.value.copy(message = "已置中校準：請從中央開始凝視")
    }
    fun setEyeControlEnabled(on: Boolean) = viewModelScope.launch { settingsRepo.setEyeControlEnabled(on) }
    fun setEyeControlMode(mode: EyeControlMode) = viewModelScope.launch { settingsRepo.setEyeControlMode(mode) }
    fun setEyeScanIntervalMs(ms: Float) = viewModelScope.launch { settingsRepo.setEyeScanIntervalMs(ms) }
    fun setCameraLens(lens: CameraLens) = viewModelScope.launch { settingsRepo.setCameraLens(lens) }
    fun setGptSovitEnabled(on: Boolean) = viewModelScope.launch { settingsRepo.setGptSovitEnabled(on) }
    fun setGptSovitVoiceMode(mode: GptSovitVoiceMode) = viewModelScope.launch { settingsRepo.setGptSovitVoiceMode(mode) }
    fun setGptSovitChineseVoiceUri(uri: String) = viewModelScope.launch { settingsRepo.setGptSovitChineseVoiceUri(uri) }
    fun setGptSovitEnglishVoiceUri(uri: String) = viewModelScope.launch { settingsRepo.setGptSovitEnglishVoiceUri(uri) }
    fun setGptSovitModelDirUri(uri: String) = viewModelScope.launch { settingsRepo.setGptSovitModelDirUri(uri) }
    fun setGptSovitChineseModelDirUri(uri: String) = viewModelScope.launch { settingsRepo.setGptSovitChineseModelDirUri(uri) }
    fun setGptSovitEnglishModelDirUri(uri: String) = viewModelScope.launch { settingsRepo.setGptSovitEnglishModelDirUri(uri) }
    fun setGptSovitAutoImportRootUri(uri: String) = viewModelScope.launch { settingsRepo.setGptSovitAutoImportRootUri(uri) }
    fun setGptSovitApiBaseUrl(url: String) = viewModelScope.launch { settingsRepo.setGptSovitApiBaseUrl(url) }
    fun setGptSovitServerList(list: String) = viewModelScope.launch { settingsRepo.setGptSovitServerList(list) }
    // ─── 自訂 API（懸浮視窗）─────────────────────────────────────────────────
    fun setCustomApiEnabled(on: Boolean) = viewModelScope.launch { settingsRepo.setCustomApiEnabled(on) }
    fun setCustomApiName(v: String) = viewModelScope.launch { settingsRepo.setCustomApiName(v) }
    fun setCustomApiEndpoint(v: String) = viewModelScope.launch { settingsRepo.setCustomApiEndpoint(v) }
    fun setCustomApiModel(v: String) = viewModelScope.launch { settingsRepo.setCustomApiModel(v) }
    fun setCustomApiKey(v: String) = viewModelScope.launch { settingsRepo.setCustomApiKey(v) }
    fun setApiKeyGroq(v: String) = viewModelScope.launch { settingsRepo.setApiKeyGroq(v) }
    fun setApiKeyOpenRouter(v: String) = viewModelScope.launch { settingsRepo.setApiKeyOpenRouter(v) }
    fun setApiKeyCohere(v: String) = viewModelScope.launch { settingsRepo.setApiKeyCohere(v) }
    fun setApiKeyGemini(v: String) = viewModelScope.launch { settingsRepo.setApiKeyGemini(v) }
    fun setApiKeyGooglePlaces(v: String) = viewModelScope.launch { settingsRepo.setApiKeyGooglePlaces(v) }
    fun setApiKeyTelegramBotToken(v: String) = viewModelScope.launch { settingsRepo.setApiKeyTelegramBotToken(v) }
    fun setEmergencyPhoneNumber(v: String) = viewModelScope.launch { settingsRepo.setEmergencyPhoneNumber(v) }
    fun setVideoCallBaseUrl(v: String) = viewModelScope.launch { settingsRepo.setVideoCallBaseUrl(v) }
    fun setLlmGroqEndpoint(v: String) = viewModelScope.launch { settingsRepo.setLlmGroqEndpoint(v) }
    fun setLlmGroqModel(v: String) = viewModelScope.launch { settingsRepo.setLlmGroqModel(v) }
    fun setLlmOpenRouterEndpoint(v: String) = viewModelScope.launch { settingsRepo.setLlmOpenRouterEndpoint(v) }
    fun setLlmOpenRouterModel(v: String) = viewModelScope.launch { settingsRepo.setLlmOpenRouterModel(v) }
    fun setLlmCohereEndpoint(v: String) = viewModelScope.launch { settingsRepo.setLlmCohereEndpoint(v) }
    fun setLlmCohereModel(v: String) = viewModelScope.launch { settingsRepo.setLlmCohereModel(v) }

    // ─── Gemini 生圖 ─────────────────────────────────────────────────────────
    sealed class ImageGenState {
        object Idle : ImageGenState()
        object Loading : ImageGenState()
        data class Success(val pngPath: String) : ImageGenState()
        data class Error(val message: String) : ImageGenState()
    }
    private val _imageGenState = MutableStateFlow<ImageGenState>(ImageGenState.Idle)
    val imageGenState: StateFlow<ImageGenState> = _imageGenState.asStateFlow()

    /** 生圖金鑰自動輪詢游標：每次請求 +1，讓同供應商多把金鑰輪流使用。 */
    private val imageRotation = java.util.concurrent.atomic.AtomicInteger(0)

    /** 生圖 API 清單（多服務商、可多把金鑰）。UI 用來顯示/選擇/管理。 */
    val imageApisFlow: StateFlow<List<ImageApiEntry>> =
        settings.map { ImageApiStore.decode(it.imageApis) }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * 取得目前生圖清單；若清單為空，用舊的 Gemini 設定 + 免費 Pollinations 自動遷移種入。
     * 確保升級後仍有可用設定，且永遠有一個免金鑰的備援。
     */
    private suspend fun currentImageApis(): List<ImageApiEntry> {
        val s = settings.firstOrNull() ?: AppSettings()
        val existing = ImageApiStore.decode(s.imageApis)
        if (existing.isNotEmpty()) {
            // 升級：確保一定有一筆「本機電腦（Mac）」免金鑰選項
            if (existing.none { it.provider == ImageProvider.LOCAL }) {
                val withLocal = listOf(seedLocalEntry()) + existing
                settingsRepo.setImageApis(ImageApiStore.encode(withLocal))
                return withLocal
            }
            return existing
        }
        val geminiKey = com.example.data.ApiKeyOverrides.resolve(
            s.customApiKey,
            runCatching { com.example.BuildConfig.GEMINI_IMAGE_API_KEY }.getOrNull().orEmpty()
        )
        val seeded = listOf(
            seedLocalEntry(),
            ImageApiEntry(
                id = "seed-gemini",
                provider = ImageProvider.GEMINI,
                name = s.customApiName.ifBlank { "Gemini 生圖" },
                endpoint = s.customApiEndpoint,
                model = s.customApiModel,
                key = geminiKey
            ),
            ImageApiEntry(
                id = "seed-pollinations",
                provider = ImageProvider.POLLINATIONS,
                name = "Pollinations（免費）"
            )
        )
        settingsRepo.setImageApis(ImageApiStore.encode(seeded))
        // 預設用本機（免金鑰、不靠雲端 API）
        if (s.imageApiSelectedId.isBlank()) settingsRepo.setImageApiSelectedId("seed-local")
        return seeded
    }

    private fun seedLocalEntry() = ImageApiEntry(id = "seed-local", provider = ImageProvider.LOCAL, name = "")

    /** 本機生圖伺服器(9881)的位址：跟著 GPT-SoVITS 連線設定走（USB=127.0.0.1、Wi-Fi=Mac IP）。 */
    private fun localImageUrl(gptSovitBase: String): String {
        val b = gptSovitBase.trim()
        if (b.isBlank()) return "http://127.0.0.1:9881/generate"
        return runCatching {
            val u = java.net.URI(if (b.contains("://")) b else "http://$b")
            val host = u.host ?: return "http://127.0.0.1:9881/generate"
            "http://$host:9881/generate"
        }.getOrDefault("http://127.0.0.1:9881/generate")
    }

    /**
     * 產生圖片。順序：使用中那筆 → 同服務商的其他金鑰（自動備援，避開額度/429）。
     * 成功把影像存到 cacheDir 並回傳路徑（UI 用 file:// 顯示）。
     */
    fun generateImage(prompt: String) {
        if (_imageGenState.value is ImageGenState.Loading) return
        _imageGenState.value = ImageGenState.Loading
        viewModelScope.launch {
            val (bytes, err) = produceImageBytes(prompt)
            _imageGenState.value = if (bytes != null) {
                val out = java.io.File(getApplication<android.app.Application>().cacheDir, "gen_image_${System.currentTimeMillis()}.png")
                runCatching { out.writeBytes(bytes) }
                ImageGenState.Success(out.absolutePath)
            } else ImageGenState.Error(err)
        }
    }

    /**
     * 共用產圖：使用中那筆 → 同供應商其他金鑰（輪詢備援）→ 跨供應商 → 最後本機電腦。
     * 回傳 (影像bytes, 錯誤訊息)；成功則 bytes 非空。
     */
    private suspend fun produceImageBytes(prompt: String): Pair<ByteArray?, String> {
        val all = currentImageApis()
        if (all.isEmpty()) return null to "尚未設定任何生圖 API"
        val s = settings.firstOrNull() ?: AppSettings()
        val selected = all.firstOrNull { it.id == s.imageApiSelectedId } ?: all.first()
        val cloud = all.filter { it.provider != ImageProvider.LOCAL && (!it.provider.needsKey || it.key.isNotBlank()) }
        val local = all.filter { it.provider == ImageProvider.LOCAL }
        val off = imageRotation.getAndIncrement()
        fun rot(l: List<ImageApiEntry>) = if (l.size <= 1) l else { val o = Math.floorMod(off, l.size); l.drop(o) + l.take(o) }
        // 接手計畫＝電腦時，本機電腦生圖優先（連線夠快、免金鑰、隱私佳）；否則照使用者選的供應商。
        val preferComputer = OffloadManager.shouldPreferComputerImage() && local.isNotEmpty()
        val tryList = when {
            preferComputer -> (local + rot(cloud)).distinctBy { it.id }
            selected.provider == ImageProvider.LOCAL -> (local + rot(cloud)).distinctBy { it.id }
            else -> {
                val selGroup = cloud.filter { it.provider == selected.provider }
                val otherGroup = cloud.filter { it.provider != selected.provider }
                (rot(selGroup) + rot(otherGroup) + local).distinctBy { it.id }
            }
        }
        var lastErr = "生圖失敗"
        for (entry in tryList) {
            val resolved = if (entry.provider == ImageProvider.LOCAL && entry.endpoint.isBlank())
                entry.copy(endpoint = localImageUrl(s.gptSovitApiBaseUrl)) else entry
            val r = ImageGenClient.generate(resolved, prompt)
            val bytes = r.getOrNull()
            if (bytes != null) return bytes to ""
            lastErr = r.exceptionOrNull()?.message ?: "生圖失敗"
        }
        return null to lastErr
    }

    // ─── 動態 AAC 意圖確認卡：用 AI 生圖產生「專屬圖卡」（醫師建議，不識字適用）──
    private val _intentImagePath = MutableStateFlow<String?>(null)
    val intentImagePath: StateFlow<String?> = _intentImagePath.asStateFlow()
    private val _intentImageLoading = MutableStateFlow(false)
    val intentImageLoading: StateFlow<Boolean> = _intentImageLoading.asStateFlow()

    /** 常見意圖中→英關鍵詞（本機 SD-Turbo 吃英文，轉成有意義的圖）。 */
    private val intentKeywordMap = listOf(
        Regex("渴|喝|水|茶") to "a glass of water",
        Regex("餓|飯|吃|麵|食") to "a bowl of food, a meal",
        Regex("廁所|尿|大便|洗手間|馬桶") to "a toilet, bathroom",
        Regex("痛|不舒服|難受") to "a person feeling unwell, in pain",
        Regex("藥|醫|病|護") to "medicine pills and a doctor",
        Regex("睡|累|休息|床") to "a person sleeping in bed, resting",
        Regex("家|回家") to "a cozy home, a house",
        Regex("電話|家人|聯絡") to "a phone call to family",
        Regex("冷") to "feeling cold, a blanket",
        Regex("熱") to "feeling hot, warm",
        Regex("燈|電視|窗|門") to "a room with light, window and door"
    )

    private fun intentImagePrompt(sentence: String): String {
        val hits = intentKeywordMap.filter { it.first.containsMatchIn(sentence) }.map { it.second }
        val subject = if (hits.isNotEmpty()) hits.joinToString(", ") else sentence
        return "simple clear pictogram for communication, flat illustration, $subject"
    }

    /** 意圖卡開啟「AI 圖」時呼叫：背景產圖、不阻擋 ✓/🔁/✗ 確認鈕。 */
    private fun generateIntentImage(sentence: String) {
        _intentImagePath.value = null
        _intentImageLoading.value = true
        viewModelScope.launch {
            val (bytes, _) = produceImageBytes(intentImagePrompt(sentence))
            if (bytes != null) {
                val out = java.io.File(getApplication<android.app.Application>().cacheDir, "intent_${System.currentTimeMillis()}.png")
                runCatching { out.writeBytes(bytes) }
                _intentImagePath.value = out.absolutePath
            }
            _intentImageLoading.value = false
        }
    }

    private fun clearIntentImage() {
        _intentImagePath.value = null
        _intentImageLoading.value = false
    }

    /** 新增一筆生圖 API（挑服務商，名稱自動帶序號避免重複）。 */
    fun addImageApi(provider: ImageProvider) = viewModelScope.launch {
        val list = currentImageApis().toMutableList()
        val sameCount = list.count { it.provider == provider }
        val id = "img-${System.currentTimeMillis()}"
        val name = provider.displayName + if (sameCount > 0) " ${sameCount + 1}" else ""
        list.add(ImageApiEntry(id = id, provider = provider, name = name))
        settingsRepo.setImageApis(ImageApiStore.encode(list))
        if (list.size == 1) settingsRepo.setImageApiSelectedId(id)
    }

    fun updateImageApi(entry: ImageApiEntry) = viewModelScope.launch {
        val list = currentImageApis().map { if (it.id == entry.id) entry else it }
        settingsRepo.setImageApis(ImageApiStore.encode(list))
    }

    fun deleteImageApi(id: String) = viewModelScope.launch {
        val list = currentImageApis().filter { it.id != id }
        settingsRepo.setImageApis(ImageApiStore.encode(list))
        val s = settings.firstOrNull() ?: AppSettings()
        if (s.imageApiSelectedId == id) settingsRepo.setImageApiSelectedId(list.firstOrNull()?.id ?: "")
    }

    fun selectImageApi(id: String) = viewModelScope.launch { settingsRepo.setImageApiSelectedId(id) }

    /** 生圖視窗開啟時呼叫：清單為空就自動遷移種入，避免 UI 沒有任何可選項。 */
    fun ensureImageApisSeeded() = viewModelScope.launch { currentImageApis() }

    // ─── 文字 LLM 多供應商清單（與生圖同模式：可加多筆、自動備援）───
    val llmApisFlow: StateFlow<List<LlmApiEntry>> =
        settings.map { LlmApiStore.decode(it.llmApis) }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** 取得文字 LLM 清單；為空則用舊的 Groq/OpenRouter/Cohere 設定 + 金鑰自動遷移種入。 */
    private suspend fun currentLlmApis(): List<LlmApiEntry> {
        val s = settings.firstOrNull() ?: AppSettings()
        val existing = LlmApiStore.decode(s.llmApis)
        if (existing.isNotEmpty()) return existing
        fun k(override: String, fallback: String) = com.example.data.ApiKeyOverrides.resolve(override, fallback)
        val seeded = listOf(
            LlmApiEntry("seed-groq", LlmProvider.GROQ, "", s.llmGroqEndpoint, s.llmGroqModel,
                k(s.apiKeyGroq, runCatching { com.example.BuildConfig.GROQ_API_KEY }.getOrNull().orEmpty())),
            LlmApiEntry("seed-openrouter", LlmProvider.OPENROUTER, "", s.llmOpenRouterEndpoint, s.llmOpenRouterModel,
                k(s.apiKeyOpenRouter, runCatching { com.example.BuildConfig.OPENROUTER_API_KEY }.getOrNull().orEmpty())),
            LlmApiEntry("seed-cohere", LlmProvider.COHERE, "", s.llmCohereEndpoint, s.llmCohereModel,
                k(s.apiKeyCohere, runCatching { com.example.BuildConfig.COHERE_API_KEY }.getOrNull().orEmpty())),
        )
        settingsRepo.setLlmApis(LlmApiStore.encode(seeded))
        if (s.llmApiSelectedId.isBlank()) {
            settingsRepo.setLlmApiSelectedId(seeded.firstOrNull { it.usable }?.id ?: "seed-groq")
        }
        return seeded
    }

    fun ensureLlmApisSeeded() = viewModelScope.launch { currentLlmApis() }

    fun addLlmApi(provider: LlmProvider) = viewModelScope.launch {
        val list = currentLlmApis().toMutableList()
        list.add(LlmApiEntry(id = "llm-${System.currentTimeMillis()}", provider = provider))
        settingsRepo.setLlmApis(LlmApiStore.encode(list))
        if (list.size == 1) settingsRepo.setLlmApiSelectedId(list.first().id)
    }

    fun updateLlmApi(entry: LlmApiEntry) = viewModelScope.launch {
        val list = currentLlmApis().map { if (it.id == entry.id) entry else it }
        settingsRepo.setLlmApis(LlmApiStore.encode(list))
    }

    fun deleteLlmApi(id: String) = viewModelScope.launch {
        val list = currentLlmApis().filter { it.id != id }
        settingsRepo.setLlmApis(LlmApiStore.encode(list))
        val s = settings.firstOrNull() ?: AppSettings()
        if (s.llmApiSelectedId == id) settingsRepo.setLlmApiSelectedId(list.firstOrNull()?.id ?: "")
    }

    fun selectLlmApi(id: String) = viewModelScope.launch { settingsRepo.setLlmApiSelectedId(id) }

    fun resetImageGen() { _imageGenState.value = ImageGenState.Idle }
    fun setSovitsConsentAccepted(on: Boolean) = viewModelScope.launch { settingsRepo.setSovitsConsentAccepted(on) }
    fun setAacIntentConfirmEnabled(on: Boolean) = viewModelScope.launch { settingsRepo.setAacIntentConfirmEnabled(on) }
    fun setAacIntentImageEnabled(on: Boolean) = viewModelScope.launch { settingsRepo.setAacIntentImageEnabled(on) }

    /**
     * 嚴重程度分級：除了存設定，同步套用對應預設 —
     * 中度 → 字體 1.4×；重度 → 字體 1.8× + AAC 識字卡逐張模式。輕度不動使用者現有設定。
     */
    fun setSeverityMode(mode: com.example.data.SeverityMode) = viewModelScope.launch {
        settingsRepo.setSeverityMode(mode)
        when (mode) {
            com.example.data.SeverityMode.Mild -> Unit
            com.example.data.SeverityMode.Moderate -> settingsRepo.setFontScale(1.4f)
            com.example.data.SeverityMode.Severe -> {
                settingsRepo.setFontScale(1.8f)
                if (!_aacState.value.flashcardMode) {
                    _aacState.value = _aacState.value.copy(flashcardMode = true, flashcardIndex = 0)
                }
            }
        }
    }
    fun setCustomBackgroundUri(uri: String) = viewModelScope.launch { settingsRepo.setCustomBackgroundUri(uri) }
    fun setCloudProvider(provider: CloudProvider) = viewModelScope.launch {
        settingsRepo.setCloudProvider(provider)
        activeEngine = createConfiguredEngine(_deviceTier.value)
    }

    private fun startTelemetry() {
        if (telemetryJob?.isActive == true) return
        telemetryJob = viewModelScope.launch {
            while (true) {
                val sample = telemetryMonitor.sample()
                _deviceMetrics.value = (_deviceMetrics.value + sample).takeLast(60)
                delay(2_500)
            }
        }
    }

    private fun stopTelemetry() {
        telemetryJob?.cancel()
        telemetryJob = null
    }

    private fun refreshEyeControlLifecycle() {
        val shouldRun = settings.value.eyeControlEnabled &&
            ((rehabScreenVisible && _rehabState.value.isActive) || aacScreenVisible)
        if (shouldRun) startEyeControl() else stopEyeControl()
    }

    private fun startEyeControl() {
        if (eyeScanJob?.isActive == true) return
        val mode = settings.value.eyeControlMode.toEyeInteractionMode()
        val msg = if (mode == EyeInteractionMode.GAZE_DWELL) {
            "眼動控制已啟動：凝視字卡停留即可選取"
        } else {
            "眼動控制已啟動：請刻意眨眼確認目前高亮字卡"
        }
        _eyeControlState.value = _eyeControlState.value.copy(enabled = true, mode = mode, message = msg)
        eyeScanJob = viewModelScope.launch {
            launch {
                eyeControlManager.start().onSuccess {
                    _eyeControlState.value = _eyeControlState.value.copy(running = true)
                }.onFailure {
                    _eyeControlState.value = _eyeControlState.value.copy(running = false, message = it.message ?: "眼動控制啟動失敗")
                }
            }
            while (true) {
                if (rehabScreenVisible && _rehabState.value.isActive) {
                    when (settings.value.eyeControlMode) {
                        EyeControlMode.GazeDwell -> updateGazeDwellSelection()
                        EyeControlMode.BlinkScan -> updateBlinkScanSelection()
                    }
                }
                delay(if (settings.value.eyeControlMode == EyeControlMode.GazeDwell) 120L else settings.value.eyeScanIntervalMs.toLong())
            }
        }
    }

    private fun stopEyeControl() {
        eyeScanJob?.cancel()
        eyeScanJob = null
        viewModelScope.launch { eyeControlManager.stop() }
        _eyeControlState.value = EyeControlUiState()
        gazeTargetIndex = -1
        gazeTargetStartedAt = 0L
        lastGazeSelectAt = 0L
    }

    private fun updateBlinkScanSelection() {
        val count = _rehabState.value.chips.size
        if (count <= 0) return
        val next = Math.floorMod(_eyeControlState.value.scanIndex + 1, count)
        _eyeControlState.value = _eyeControlState.value.copy(
            mode = EyeInteractionMode.BLINK_SCAN,
            scanIndex = next,
            dwellProgress = 0f
        )
    }

    private fun updateGazeDwellSelection() {
        val count = _rehabState.value.chips.size
        if (count <= 0) return

        val now = System.currentTimeMillis()
        val target = (_eyeControlState.value.gazeX * count)
            .toInt()
            .coerceIn(0, count - 1)
        if (target != gazeTargetIndex) {
            gazeTargetIndex = target
            gazeTargetStartedAt = now
        }

        val dwellMs = 1_150L
        val progress = ((now - gazeTargetStartedAt).toFloat() / dwellMs).coerceIn(0f, 1f)
        _eyeControlState.value = _eyeControlState.value.copy(
            mode = EyeInteractionMode.GAZE_DWELL,
            scanIndex = target,
            dwellProgress = progress
        )

        if (progress >= 1f && now - lastGazeSelectAt > 1_600L) {
            lastGazeSelectAt = now
            gazeTargetStartedAt = now
            val selected = _rehabState.value.chips.getOrNull(target).orEmpty()
            _eyeControlState.value = _eyeControlState.value.copy(
                dwellProgress = 0f,
                message = "凝視選取：$selected"
            )
            speakRehabChip(target)
        }
    }

    private fun EyeControlMode.toEyeInteractionMode(): EyeInteractionMode = when (this) {
        EyeControlMode.GazeDwell -> EyeInteractionMode.GAZE_DWELL
        EyeControlMode.BlinkScan -> EyeInteractionMode.BLINK_SCAN
    }

    // ─── Room DB 歷史 ─────────────────────────────────────────────────────────

    fun toggleFavorite(id: Int, isFavorite: Boolean) {
        viewModelScope.launch { repository.updateFavorite(id, isFavorite) }
    }

    fun deleteRecord(id: Int) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun clearHistory() {
        viewModelScope.launch { repository.clearAll() }
    }

    fun onRawTextChanged(text: String) {
        // 手動編輯會讓逐字信心度對不上，故清空
        _globalTagsVault.value = _globalTagsVault.value.copy(rawText = text, sttWords = emptyList())
    }

    fun onLocationChanged(location: String) {
        _globalTagsVault.value = _globalTagsVault.value.copy(locationTag = location)
    }

    fun onVisionTagsChanged(tags: List<String>) {
        _globalTagsVault.value = _globalTagsVault.value.copy(visionTags = tags)
    }

    /**
     * 重建對話，採用 suspend 函數由協程執行，防止 main thread 阻塞
     */
    fun triggerSentenceReconstruction() {
        val currentVault = _globalTagsVault.value
        if (currentVault.rawText.isBlank()) {
            _reconstructionState.value = ReconstructionUiState.Error("請輸入不連貫的對話碎詞（如：渴... 水）")
            return
        }

        // 防禦層 1：危機字眼（如「我想自殺」）在送 LLM 前就攔截 → 不重組、不朗讀，立即通報緊急聯絡人。
        if (com.example.safety.SafetyGuard.containsCrisisSignal(currentVault.rawText)) {
            _reconstructionState.value = ReconstructionUiState.Error(
                "偵測到高風險訊息，已停止重組並通報緊急聯絡人。如有立即危險請撥打 1925 安心專線或 119。"
            )
            triggerEmergencyReport(currentVault.rawText, poiTag = currentVault.locationTag)
            return
        }

        _reconstructionState.value = ReconstructionUiState.Loading

        viewModelScope.launch {
            try {
                settingsRepo.recordInput(viaAac = false)
                val useCase = ReconstructionUseCase(activeEngine)
                val data = useCase.execute(currentVault)
                _reconstructionState.value = ReconstructionUiState.Success(data)
                // 動態專屬圖卡：重組句完成後，依語意背景生成對應圖片（開關開啟時）
                if (settings.value.aacIntentImageEnabled) generateIntentImage(data.reconstructedText)
                else clearIntentImage()
                // 自動儲存至歷史紀錄
                repository.insert(
                    SpeechRecord(
                        originalInput     = data.originalText,
                        reconstructedText = data.reconstructedText,
                        confidence        = "${"%.0f".format(data.confidence * 100)}%",
                        locationTag       = currentVault.locationTag,
                        visionTag         = currentVault.visionTags.joinToString("、"),
                        timestamp         = data.timestamp
                    )
                )
            } catch (e: Exception) {
                _reconstructionState.value = ReconstructionUiState.Error(e.localizedMessage ?: "系統拋出未預期異常")
            } finally {
                cleanLocalAiIfEnabled()
            }
        }
    }

    fun resetState() {
        _reconstructionState.value = ReconstructionUiState.Idle
        _globalTagsVault.value = _globalTagsVault.value.copy(rawText = "")
    }

    // ─── 模型下載 ─────────────────────────────────────────────────────────────

    /**
     * 從公開 URL 直接下載 Gemma 3 1B IT INT4 模型。
     * 不需帳號或 API 金鑰，有網路即可下載（同 wget URL）。
     * 支援中斷續傳：臨時檔存在會從上次進度繼續。
     */
    fun startModelDownload(url: String? = null) {
        startModelDownload(url = url, automatic = false)
    }

    private fun startModelDownload(url: String? = null, automatic: Boolean) {
        if (downloadJob?.isActive == true) return

        downloadJob = viewModelScope.launch {
            try {
                val downloadResult = if (url.isNullOrBlank()) downloader.download(
                    onProgress = { progress, dlMb, totalMb ->
                        _modelDownloadState.value = ModelDownloadState.Downloading(progress, dlMb, totalMb)
                    }
                ) else downloader.downloadFromUrl(url) { progress, dlMb, totalMb ->
                    _modelDownloadState.value = ModelDownloadState.Downloading(progress, dlMb, totalMb)
                }

                downloadResult.onSuccess {
                    // 模型就緒，重新選擇推理引擎
                    val tier = _deviceTier.value
                    activeEngine = createConfiguredEngine(tier)
                    val local = activeEngine.localFallback()
                    if (local is MediaPipeLLMEngine) {
                        val initResult = local.initialize()
                        _modelDownloadState.value = if (initResult.isSuccess) {
                            ModelDownloadState.Ready
                        } else {
                            ModelDownloadState.Error(initResult.exceptionOrNull()?.message ?: "模型載入失敗")
                        }
                    } else {
                        val path = MediaPipeLLMEngine.modelPath(getApplication())
                        _modelDownloadState.value = if (automatic) {
                            ModelDownloadState.NotAvailable(
                                "缺失的本機 LLM 模型已自動補齊。\n\n" +
                                "為避免不相容模型造成 native 閃退，目前仍使用規則引擎；雲端 LLM 可正常使用。\n\n" +
                                "模型位置：\n$path"
                            )
                        } else {
                            ModelDownloadState.NotAvailable(
                                "本機 LLM 模型已下載完成。\n\n" +
                                "為避免不相容模型造成 native 閃退，目前暫停自動載入；雲端 LLM 可正常使用。\n\n" +
                                "模型位置：\n$path"
                            )
                        }
                    }
                }.onFailure { e ->
                    _modelDownloadState.value = ModelDownloadState.Error(e.message ?: "下載失敗")
                }
            } catch (e: CancellationException) {
                _modelDownloadState.value = ModelDownloadState.NotAvailable("下載已取消，臨時檔已保留，下次繼續。")
            }
        }
    }

    private fun autoCompleteMissingLocalAiModel() {
        if (autoModelRepairStarted) return
        val app = getApplication<Application>()
        val working = MediaPipeLLMEngine.workingModelFile(app)
        if (working.exists() && working.length() > 100_000_000L) return
        autoModelRepairStarted = true
        _modelDownloadState.value = ModelDownloadState.Downloading(0, 0, 0)
        startModelDownload(url = null, automatic = true)
    }

    fun testModelUrl(url: String) {
        if (url.isBlank()) {
            _modelProbeState.value = ModelProbeState.Error("請先貼上 Hugging Face 模型 URL")
            return
        }
        _modelProbeState.value = ModelProbeState.Testing
        viewModelScope.launch {
            val result = downloader.probeUrl(url)
            _modelProbeState.value = result.fold(
                onSuccess = {
                    ModelProbeState.Success(
                        "連線成功：${it.sizeMb.takeIf { mb -> mb > 0 }?.let { mb -> "$mb MB" } ?: "大小未知"}，" +
                        if (it.isTaskLikely) "看起來可作為 MediaPipe .task 模型。" else "可下載，但目前推理建議使用 .task 格式。"
                    )
                },
                onFailure = { ModelProbeState.Error(it.message ?: "連線測試失敗") }
            )
        }
    }

    fun cancelModelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    private fun cleanLocalAiIfEnabled() {
        if (!settings.value.autoLocalAiCleanup) return
        val local = activeEngine.localFallback()
        val shouldClean = settings.value.cloudProvider == CloudProvider.LocalOnly ||
            local is MediaPipeLLMEngine ||
            local is RegexTemplateEngine
        if (!shouldClean) return

        viewModelScope.launch {
            localAiMaintenance.cleanCachesAndBackground(activeEngine)
        }
    }

    // ─── AAC 溝通板（檳榔攤情境）────────────────────────────────────────

    /** 點類別卡 → 進入該類別子畫面（同時把識字卡指針歸零，否則跨類別會 out of bounds） */
    fun aacSelectCategory(cat: AacCategory?) {
        _aacState.value = _aacState.value.copy(selectedCategory = cat, flashcardIndex = 0)
    }

    /** 切換 grid ↔ flashcard 兩種瀏覽模式。指針歸零，避免越界。 */
    fun aacToggleFlashcardMode() {
        val s = _aacState.value
        _aacState.value = s.copy(flashcardMode = !s.flashcardMode, flashcardIndex = 0)
    }

    /** 識字卡：往下一張（含 wrap-around，永遠不會卡住） */
    fun aacFlashcardNext(totalCards: Int) {
        if (totalCards <= 0) return
        val s = _aacState.value
        _aacState.value = s.copy(flashcardIndex = (s.flashcardIndex + 1) % totalCards)
    }

    /** 識字卡：往上一張 */
    fun aacFlashcardPrev(totalCards: Int) {
        if (totalCards <= 0) return
        val s = _aacState.value
        _aacState.value = s.copy(flashcardIndex = ((s.flashcardIndex - 1) + totalCards) % totalCards)
    }

    /** 點條目 → 累加進句子 */
    fun aacAppendItem(item: AacItem) {
        val s = _aacState.value
        // 強烈意圖防護：若是緊急類，立刻過風險閘門發聲 + 標記
        if (item.isStrongIntent) requestSpeak(item.label)
        appendRawToken(item.label)
        _aacState.value = s.copy(
            sentence = s.sentence + item,
            selectedCategory = null    // 回主類別格供連加
        )
    }

    private fun appendRawToken(token: String) {
        val clean = token.trim()
        if (clean.isBlank()) return
        val current = _globalTagsVault.value.rawText.trim()
        _globalTagsVault.value = _globalTagsVault.value.copy(
            rawText = if (current.isBlank()) clean else "$current $clean",
            sttWords = emptyList()
        )
    }

    fun aacRemoveLast() {
        val s = _aacState.value
        if (s.sentence.isNotEmpty()) {
            viewModelScope.launch { settingsRepo.recordUndo() }
            _aacState.value = s.copy(sentence = s.sentence.dropLast(1))
        }
    }

    fun aacClear() {
        _aacState.value = _aacState.value.copy(sentence = emptyList(), selectedCategory = null)
    }

    /**
     * ✔ 對了：先送 LLM（雲端優先、本地後備）把圖卡序列組成自然口語，再 TTS 朗讀。
     * UI 期間以 `composing = true` 顯示載入狀態。
     */
    fun aacConfirm() {
        val s = _aacState.value
        if (s.sentence.isEmpty() || s.composing) return
        _aacState.value = s.copy(composing = true)
        viewModelScope.launch {
            val items = s.sentence
            val location = _globalTagsVault.value.locationTag
            val rawDraft = items.joinToString("") { it.label }

            // 防禦層 1：危機字眼攔截 → 不送 LLM、不 TTS、改觸發 Telegram 求救
            val crisis = emergencyTrigger.interceptAndReport(rawDraft, poiTag = location)
            if (crisis != null) {
                val emergencyId = aacComboLogRepo.log(
                    finalSentence = "[緊急通報]${rawDraft.take(40)}",
                    items = items, locationTag = location, poiTag = "",
                    triggeredEmergency = true
                )
                _aacState.value = AacUiState(
                    alertTriggered = true,
                    lastConfirmedText = "已通報緊急聯絡人",
                    successCount = s.successCount,
                    lastLogId = emergencyId,
                    flashcardMode = s.flashcardMode   // 重度模式的識字卡瀏覽不因通報被重設
                )
                return@launch
            }

            // LLM 組句（雲端優先 + 本地後備），失敗 fallback 純串接
            val rawNatural = activeEngine.composeAacSentence(items, location)
                .getOrElse { rawDraft }
                .ifBlank { rawDraft }
            // 防禦層 3：輸出消毒（即使 LLM 漏網吐出禁字也替換掉）
            val natural = com.example.safety.SafetyGuard.sanitizeForSpeech(rawNatural)
            settingsRepo.recordInput(viaAac = true)

            // 醫師臨床建議：AI 生成句**禁止直接發聲** — 先進入待確認狀態，
            // 由使用者（或照護者）在意圖確認卡上按 ✓ 才朗讀；✗ 退回重猜/修改。
            _aacState.value = s.copy(composing = false, pendingConfirmText = natural)
            // 意圖確認卡若開啟「AI 圖」：背景生成專屬圖卡（不阻擋確認鈕）
            if (settings.value.aacIntentImageEnabled) generateIntentImage(natural) else clearIntentImage()
        }
    }

    /** 意圖確認卡按 ✓：過風險閘門朗讀 + 寫 Room（這才算一次成功溝通）。 */
    fun aacAcceptPending() {
        val s = _aacState.value
        val natural = s.pendingConfirmText
        if (natural.isBlank()) return
        clearIntentImage()
        viewModelScope.launch {
            settingsRepo.recordCandidateChoice(firstHit = s.regenCount == 0)
            requestSpeak(natural)
            val logId = aacComboLogRepo.log(
                finalSentence = natural,
                items = s.sentence,
                locationTag = _globalTagsVault.value.locationTag,
                poiTag = "",
                triggeredEmergency = false
            )
            _aacState.value = AacUiState(
                successCount = s.successCount + 1,
                lastConfirmedText = natural,
                rejectCount = 0,
                alertTriggered = false,
                lastLogId = logId,
                flashcardMode = s.flashcardMode
            )
        }
    }

    /** 意圖確認卡按 🔁 再猜一次：保留圖卡序列重新組句（記修改次數）。 */
    fun aacRegeneratePending() {
        val s = _aacState.value
        if (s.composing) return
        clearIntentImage()
        viewModelScope.launch { settingsRepo.recordUndo() }
        _aacState.value = s.copy(
            pendingConfirmText = "",
            regenCount = s.regenCount + 1
        )
        aacConfirm()
    }

    /** 意圖確認卡按 ✗ 返回修改：清掉待確認句、保留圖卡讓使用者調整（記修改次數）。 */
    fun aacDismissPending() {
        clearIntentImage()
        viewModelScope.launch { settingsRepo.recordUndo() }
        _aacState.value = _aacState.value.copy(pendingConfirmText = "", regenCount = 0)
    }

    /** AAC 確認後的「這次有傳達到嗎」1-5 星評分。寫回 Room 給 AI 推薦排序權重。 */
    fun aacRateLastConfirmation(rating: Int) {
        val id = _aacState.value.lastLogId ?: return
        viewModelScope.launch { aacComboLogRepo.setRating(id, rating) }
        _aacState.value = _aacState.value.copy(lastRating = rating)
    }

    /** 一鍵清除所有 Room 歷史（重組 / 復健 / AAC / 故事訓練）— ExtendedSettingsScreen 用 */
    suspend fun clearAllHistory() {
        db.speechRecordDao().deleteAll()
        db.rehabLogDao().deleteAll()
        db.aacComboLogDao().clearAll()
        db.storyTrainingLogDao().clearAll()
    }

    /** 匯入語料庫：append 模式合併（id 重複時後者覆蓋）。給 ExtendedSettingsScreen 的 lambda 用 */
    fun appendCorpus(items: List<com.example.data.AacItem>) {
        val merged = (_customCorpus.value + items).associateBy { it.id }.values.toList()
        _customCorpus.value = merged
        // 把使用者語料也納入重組檢索候選，讓「我的語料」直接讓重組更準
        com.example.data.AphasiaPractice.userPhrases = merged.map { it.label }
    }

    /** 自訂金額：用 [Currency] 與數字字串建出 [AacItem] 並追加到句子，記住選用幣別 */
    fun aacAppendCustomAmount(amount: String, currency: Currency) {
        if (amount.isBlank()) return
        viewModelScope.launch { settingsRepo.setDefaultCurrency(currency) }
        aacAppendItem(customAmountItem(amount, currency))
    }

    /** 再播一次上一句完成的內容（不重新跑 LLM；已確認過 → 直接發聲）*/
    fun aacSpeakLast() {
        val last = _aacState.value.lastConfirmedText
        if (last.isNotBlank()) doSpeak(last)
    }

    /** ✖ 不對：清掉本輪句子、計數 +1；連續 3 次 → 觸發家屬通報 */
    fun aacReject() {
        val s = _aacState.value
        viewModelScope.launch { settingsRepo.recordUndo() }
        val newCount = s.rejectCount + 1
        val alert = newCount >= 3
        _aacState.value = s.copy(
            sentence = emptyList(),
            selectedCategory = null,
            rejectCount = newCount,
            alertTriggered = alert
        )
        if (alert) {
            Log.w("VoiceWeaverViewModel", "AAC: 連續 3 次拒絕，背景觸發家屬通報")
            // 真正的 SMS Intent 由 UI 層發起（avoid Activity reference in ViewModel）
        }
    }

    fun aacDismissAlert() {
        _aacState.value = _aacState.value.copy(alertTriggered = false, rejectCount = 0)
    }

    override fun onCleared() {
        super.onCleared()
        downloadJob?.cancel()
        stopTelemetry()
        stopEyeControl()
        ttsModule.shutdown()
        sttManager.destroy()
    }
}

/** AAC 溝通板 UI 狀態（句子建構器 + 拒絕計數 + 通報旗標 + AI 重組中）*/
data class AacUiState(
    val selectedCategory: AacCategory? = null,
    val sentence: List<AacItem> = emptyList(),
    val rejectCount: Int = 0,
    val successCount: Int = 0,
    val lastConfirmedText: String = "",
    val alertTriggered: Boolean = false,
    /** AI 正在把圖卡序列重組成自然句（UI 顯示 spinner、按鈕 disabled）*/
    val composing: Boolean = false,
    /** 最近一次確認的 AacComboLog row id（給 1-5 星評分 UI 寫回）*/
    val lastLogId: Long? = null,
    /** 最近一次確認對應的星等（0 = 還沒打）*/
    val lastRating: Int = 0,
    /**
     * AI 組好、等待使用者確認的句子（醫師臨床建議：禁止直接發聲）。
     * 非空 → UI 顯示意圖確認卡（✓ 朗讀 / 🔁 再猜 / ✗ 返回修改）。
     */
    val pendingConfirmText: String = "",
    /** 本輪「再猜一次」的次數（第一猜被接受 = 選句成功率的 firstHit）*/
    val regenCount: Int = 0,
    /**
     * 識字卡（flashcard）模式：一次只顯示一張大圖卡，配 ←/✓ 是這個/→ 三大按鈕。
     * 為什麼有這個模式：3x3 grid 對部分失語症 / 認知負擔較重的患者太多選擇；
     * 一張一張看 + 大按鈕，命中率比掃 9 張高。
     */
    val flashcardMode: Boolean = false,
    /** 識字卡目前位置（在當前類別的 items list 內的 index） */
    val flashcardIndex: Int = 0
)

/**
 * 句意重構狀態密封類
 */
sealed class ReconstructionUiState {
    object Idle : ReconstructionUiState()
    object Loading : ReconstructionUiState()
    data class Success(val data: ReconstructedData) : ReconstructionUiState()
    data class Error(val message: String) : ReconstructionUiState()
}

/**
 * MediaPipe 模型下載進度狀態
 */
sealed class ModelDownloadState {
    object Idle : ModelDownloadState()
    data class Downloading(val progress: Int, val downloadedMb: Long = 0, val totalMb: Long = 0) : ModelDownloadState()
    object Ready : ModelDownloadState()
    data class Error(val message: String) : ModelDownloadState()
    /** 模型未手動放置或尚未下載，顯示下載入口 */
    data class NotAvailable(val instructions: String) : ModelDownloadState()
}

sealed class ModelProbeState {
    object Idle : ModelProbeState()
    object Testing : ModelProbeState()
    data class Success(val message: String) : ModelProbeState()
    data class Error(val message: String) : ModelProbeState()
}

/**
 * Task 5.1: 語音復健 UI 狀態
 *
 * @param chips 點擊單元（字卡顯示文字 = 點擊播放文字，視覺 = 聽覺）。
 *              用「2 字一組（在標點內）」切分而非單字：解決破音字（單字無上下文 TTS 易誤讀），
 *              又保持字卡精細度，UX 一致。
 */
data class RehabUiState(
    val target: String = "",
    val chips: List<String> = emptyList(),
    val lastRecognized: String = "",
    val lastScore: Int? = null,
    val isRecording: Boolean = false
) {
    val isActive: Boolean get() = target.isNotBlank()
}

/** Task 5.1: 依標點與空格拆成字詞清單（詞/句層級） */
fun String.toRehabTokens(): List<String> =
    split(Regex("[，。！？、,.!?；;：:\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }

/**
 * 拆成字卡清單：先用標點切分子句，再在每個子句內每 2 字一組。
 * 例：「在客廳，可以幫我倒杯水嗎？」→ [在客, 廳, 可以, 幫我, 倒杯, 水嗎]
 *
 * 設計理由：純單字 TTS 會誤讀破音字（倒 dào/dǎo、樂 lè/yuè...），
 * 2 字一組讓 TTS 有上下文判讀，且字卡顯示 = 播放內容（視覺/聽覺一致）。
 */
fun String.toRehabChips(): List<String> = buildList {
    val phrases = this@toRehabChips
        .split(Regex("[，。！？、,.!?；;：:\\s]+"))
        .filter { it.isNotBlank() }
    for (phrase in phrases) {
        val chars = phrase.filter { it.isLetterOrDigit() }
        var i = 0
        while (i < chars.length) {
            val end = (i + 2).coerceAtMost(chars.length)
            add(chars.substring(i, end))
            i = end
        }
    }
}
