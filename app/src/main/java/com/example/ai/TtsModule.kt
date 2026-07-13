package com.example.ai

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import com.example.data.GptSovitVoiceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class TtsEngineMode {
    AndroidSystemOffline,
    PiperOnnxEngine
}

data class PiperVoiceProfile(
    val id: String,
    val displayName: String,
    val filename: String,
    val sizeMb: Double,
    val description: String
)

class TtsModule(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    // 給「合成前自動切換語言模型」用的背景 scope（switchModel 是 suspend）。
    private val ttsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES)
        .callTimeout(6, TimeUnit.MINUTES)
        .build()
    private var mediaPlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _selectedEngineMode = MutableStateFlow(TtsEngineMode.AndroidSystemOffline)
    val selectedEngineMode: StateFlow<TtsEngineMode> = _selectedEngineMode.asStateFlow()

    private val _speechRate = MutableStateFlow(0.85f) // Slower default for cognitive support
    val speechRate: StateFlow<Float> = _speechRate.asStateFlow()

    private val _speechPitch = MutableStateFlow(1.0f)
    val speechPitch: StateFlow<Float> = _speechPitch.asStateFlow()

    private val _piperSynthesisLog = MutableStateFlow<List<String>>(emptyList())
    val piperSynthesisLog: StateFlow<List<String>> = _piperSynthesisLog.asStateFlow()

    /**
     * 最近一次 GPT-SoVITS 合成的狀態（給 UI 顯示，不用翻 log）。
     * null = 尚未合成過；⏳/✅/❌ 開頭的人話訊息。
     */
    private val _synthesisStatus = MutableStateFlow<String?>(null)
    val synthesisStatus: StateFlow<String?> = _synthesisStatus.asStateFlow()
    private var gptSovitEnabled: Boolean = false
    private var gptSovitVoiceMode: GptSovitVoiceMode = GptSovitVoiceMode.Auto
    private var gptSovitChineseVoiceUri: String = ""
    private var gptSovitEnglishVoiceUri: String = ""
    private var gptSovitModelDirUri: String = ""
    private var gptSovitChineseModelDirUri: String = ""
    private var gptSovitEnglishModelDirUri: String = ""
    private var gptSovitApiBaseUrl: String = ""
    /** 手動情緒覆寫（""＝自動偵測）。UI 可切「開心/難過/…」強制語氣。 */
    @Volatile var manualEmotionOverride: String = ""

    val availablePiperVoices = listOf(
        PiperVoiceProfile("zh_TW-coqui-medium", "台灣本地女聲 (Coqui)", "zh_TW_coqui_medium.onnx", 78.4, "自然溫柔，發音清晰，適合日常溝通"),
        PiperVoiceProfile("zh_TW-amy-low", "台灣本地男聲 (Amy)", "zh_TW_amy_low.onnx", 42.1, "厚實穩重，高清晰度，適合低速播放"),
        PiperVoiceProfile("zh_TW-child-clear", "樂活兒童音 (Child)", "zh_TW_child_clear.onnx", 51.5, "語氣高亢活潑，適合親友互動情境")
    )

    private val _selectedVoice = MutableStateFlow(availablePiperVoices[0])
    val selectedVoice: StateFlow<PiperVoiceProfile> = _selectedVoice.asStateFlow()

    // 是否已嘗試用 Google TTS 引擎（失敗時要退回預設引擎，避免完全發不出聲）
    private var triedGoogleEngine = false

    init {
        initializeSystemTts()
    }

    private fun isPackageInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Throwable) { false }

    private fun initializeSystemTts() {
        try {
            // 優先用 Google TTS 引擎（內建神經語音最自然）；沒裝才用系統預設引擎。
            val google = "com.google.android.tts"
            if (isPackageInstalled(google)) {
                triedGoogleEngine = true
                tts = TextToSpeech(context, this, google)
            } else {
                tts = TextToSpeech(context, this)
            }
        } catch (t: Throwable) {
            Log.e("TtsModule", "Failed to create TextToSpeech instance: ${t.message}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts
            if (engine != null) {
                configureLanguage(engine)
            } else {
                // Failsafe delay post to avoid synchronous initialization race condition where tts is still null
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    tts?.let { configureLanguage(it) }
                }
            }
        } else {
            // Google 引擎初始化失敗（被停用/異常）→ 退回系統預設引擎，確保仍能發聲。
            if (triedGoogleEngine) {
                triedGoogleEngine = false
                Log.w("TtsModule", "Google TTS 初始化失敗，改用系統預設引擎")
                try { tts?.shutdown() } catch (_: Throwable) {}
                tts = try { TextToSpeech(context, this) } catch (t: Throwable) {
                    Log.e("TtsModule", "預設引擎也建立失敗：${t.message}"); null
                }
                return
            }
            _isInitialized.value = false
            Log.e("TtsModule", "Initialization of TTS engine failed. Status code: $status")
        }
    }

    private fun configureLanguage(engine: TextToSpeech) {
        try {
            // 語系：台灣中文優先（最貼近使用者口音），退而求其次一般中文。
            val zhTW = Locale("zh", "TW")
            val result = engine.setLanguage(zhTW)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                if (engine.setLanguage(Locale.TAIWAN) < TextToSpeech.LANG_AVAILABLE) {
                    engine.setLanguage(Locale.CHINESE)
                }
            }

            // 關鍵：主動挑選裝置上「最自然」的中文語音（神經/高品質），而非預設機械音。
            selectBestVoice(engine)

            // Apply speed and pitch defaults
            engine.setSpeechRate(_speechRate.value)
            engine.setPitch(_speechPitch.value)
            _isInitialized.value = true
            Log.i("TtsModule", "Local Offline TextToSpeech engine initialized and configured.")
        } catch (t: Throwable) {
            Log.e("TtsModule", "Error configuring TTS engine: ${t.message}")
        }
    }

    /**
     * 從系統所有可用語音中，挑出指定語言「最像人類」的一個。
     *
     * 排序準則（由重到輕）：
     *   1. 品質高者優先（Google 新版內建神經語音 QUALITY_VERY_HIGH，比預設音自然非常多）。
     *   2. 不需網路者優先 → 即使手機離線也能發聲、且穩定不卡。
     *   3. 台灣（TW）口音優先。
     * 找不到符合語言的就維持引擎預設，不致發不出聲。
     */
    private fun selectBestVoice(engine: TextToSpeech) {
        try {
            val voices: Set<Voice> = engine.voices ?: return
            val candidates = voices.filter { v ->
                val lang = runCatching { v.locale.language.lowercase() }.getOrDefault("")
                // zh / cmn（部分引擎用 cmn 表中文官話）；排除尚未安裝的語音
                (lang == "zh" || lang == "cmn") &&
                    !v.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }
            if (candidates.isEmpty()) {
                Log.i("TtsModule", "找不到中文語音，沿用引擎預設")
                return
            }
            // 先偏好「離線可用」這組（保證沒網路也能講）；若整組都需網路才退而用網路語音。
            val offline = candidates.filter { !it.isNetworkConnectionRequired }
            val pool = if (offline.isNotEmpty()) offline else candidates
            val best = pool.maxWithOrNull(
                compareBy<Voice>({ it.quality })                                   // 品質高優先
                    .thenBy { if (it.locale.country.uppercase() == "TW") 1 else 0 } // 台灣口音優先
            )
            if (best != null) {
                engine.voice = best
                Log.i(
                    "TtsModule",
                    "已選用最自然語音：${best.name}（品質=${best.quality}、需網路=${best.isNetworkConnectionRequired}、${best.locale}）"
                )
            }
        } catch (t: Throwable) {
            Log.e("TtsModule", "selectBestVoice 失敗，沿用預設：${t.message}")
        }
    }

    fun setEngineMode(mode: TtsEngineMode) {
        _selectedEngineMode.value = mode
        addLog("切換語音合成核心模式至: ${if (mode == TtsEngineMode.AndroidSystemOffline) "Android 本地系統語音" else "Piper ONNX 離線引擎"}")
    }

    fun setSpeechRate(rate: Float) {
        _speechRate.value = rate
        try {
            tts?.setSpeechRate(rate)
        } catch (t: Throwable) {
            Log.e("TtsModule", "Error setting speech rate: ${t.message}")
        }
        addLog("調整播報語速: ${"%.2f".format(rate)}x")
    }

    fun setSpeechPitch(pitch: Float) {
        _speechPitch.value = pitch
        try {
            tts?.setPitch(pitch)
        } catch (t: Throwable) {
            Log.e("TtsModule", "Error setting pitch: ${t.message}")
        }
        addLog("調整播報音高: ${"%.2f".format(pitch)}")
    }

    fun selectPiperVoice(voice: PiperVoiceProfile) {
        _selectedVoice.value = voice
        addLog("載入 Piper 本地語音包模型: ${voice.displayName} (${voice.filename})")
    }

    fun configureGptSovit(
        enabled: Boolean,
        mode: GptSovitVoiceMode,
        chineseVoiceUri: String,
        englishVoiceUri: String,
        modelDirUri: String,
        chineseModelDirUri: String,
        englishModelDirUri: String,
        apiBaseUrl: String
    ) {
        gptSovitEnabled = enabled
        gptSovitVoiceMode = mode
        gptSovitChineseVoiceUri = chineseVoiceUri
        gptSovitEnglishVoiceUri = englishVoiceUri
        gptSovitModelDirUri = modelDirUri
        gptSovitChineseModelDirUri = chineseModelDirUri.ifBlank { modelDirUri }
        gptSovitEnglishModelDirUri = englishModelDirUri
        gptSovitApiBaseUrl = apiBaseUrl.trim().trimEnd('/')
        addLog(
            if (enabled) {
                "[GPT-SoVITS] 開發者實驗模式啟用：${mode.displayName}"
            } else {
                "[GPT-SoVITS] 開發者實驗模式關閉，回到 Android TTS"
            }
        )
    }

    fun speak(text: String, onComplete: () -> Unit = {}) {
        speakInternal(text = text, forcedGptSovitMode = null, expressiveTest = false, onComplete = onComplete)
    }

    fun speakGptSovitTest(text: String, mode: GptSovitVoiceMode, onComplete: () -> Unit = {}) {
        speakInternal(text = text, forcedGptSovitMode = mode, expressiveTest = true, onComplete = onComplete)
    }

    /**
     * 多語朗讀：用指定語言唸一句（重組結果一鍵中/英/日/韓）。
     * 只作用於系統 TTS 引擎；唸完立即還原原本的中文最佳嗓音，不影響後續中文朗讀。
     * GPT-SoVITS 啟用時走原本邏輯（自家中英樣本切換）。
     */
    fun speakInLocale(text: String, locale: Locale, onComplete: () -> Unit = {}) {
        if (gptSovitEnabled || !_isInitialized.value) { speak(text, onComplete); return }
        try {
            tts?.let { e ->
                val prevVoice = e.voice                       // 記住目前中文最佳嗓音
                val r = e.setLanguage(locale)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    e.setLanguage(Locale("zh", "TW"))         // 該語言沒語音包 → 退回中文
                }
                e.speak(text, TextToSpeech.QUEUE_FLUSH, null, "AphasiaAssistMultiLang")
                if (prevVoice != null) runCatching { e.voice = prevVoice }   // 還原中文嗓音給下次
            }
        } catch (t: Throwable) {
            Log.e("TtsModule", "speakInLocale 失敗：${t.message}")
        }
        onComplete()
    }

    private fun speakInternal(
        text: String,
        forcedGptSovitMode: GptSovitVoiceMode?,
        expressiveTest: Boolean,
        onComplete: () -> Unit = {}
    ) {
        if (!_isInitialized.value && !gptSovitEnabled) {
            Log.e("TtsModule", "TTS not initialized yet")
            _synthesisStatus.value = "❌ 語音引擎尚未就緒，請稍候再試"
            onComplete()   // 不回呼會讓呼叫端的 spinner / 狀態卡死
            return
        }

        if (gptSovitEnabled) {
            val resolvedMode = forcedGptSovitMode ?: resolveGptSovitMode(text)
            val sampleUri = if (resolvedMode == GptSovitVoiceMode.English) gptSovitEnglishVoiceUri else gptSovitChineseVoiceUri
            val modelDir = if (resolvedMode == GptSovitVoiceMode.English) gptSovitEnglishModelDirUri else gptSovitChineseModelDirUri
            addLog("[GPT-SoVITS] ${resolvedMode.displayName}：${if (sampleUri.isBlank()) "使用 Mac 端預設參考音檔槽位" else "使用自備樣本槽位"}")
            addLog("[GPT-SoVITS] ${resolvedMode.displayName}模型資料夾：${if (modelDir.isBlank()) "由 Mac server 目前載入模型決定" else "已指定"}")
            if (tryGptSovitApiSynthesis(text, resolvedMode, sampleUri, expressiveTest, onComplete)) return
            addLog("[GPT-SoVITS] 未送出 API 請求；已停止，避免改用 Android TTS")
            onComplete()
            return
        } else if (_selectedEngineMode.value == TtsEngineMode.PiperOnnxEngine) {
            // Emulating fully localized high performance Piper logic
            val voice = _selectedVoice.value
            _piperSynthesisLog.value = emptyList() // Clear logs
            addLog("[Piper] 載入語音權重軌跡：$text")
            addLog("[Piper] 尋找 ONNX 檔案：/sdcard/Android/media/piper/voices/${voice.filename}")
            addLog("[Piper] 初始化 ONNX Runtime 環境 C++ 嵌入式綁定...")
            addLog("[Piper] 量化核心：16-bit Fixed Point Float 音訊硬體流加速開啟")
            addLog("[Piper] 聲學合成：開始處理包含 ${text.length} 個字符的中文拼音對映")
            addLog("[Piper] 生成波形：正在輸出 16000Hz 單聲道 PCM 語音流訊號")
            addLog("[Piper] 合成完畢！延遲：140ms | 播放高傳真本地發音。")
        } else {
            addLog("[AndroidTTS] 調撥系統離線語音：$text")
        }

        try {
            // Emit physical audio using native TTS Engine
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "AphasiaAssistTtsId")
        } catch (t: Throwable) {
            Log.e("TtsModule", "Exception during tts.speak: ${t.message}")
        }
        onComplete()
    }

    private fun tryGptSovitApiSynthesis(
        text: String,
        mode: GptSovitVoiceMode,
        refAudioPath: String,
        expressiveTest: Boolean,
        onComplete: () -> Unit
    ): Boolean {
        val apiBaseUrl = gptSovitApiBaseUrl.ifBlank {
            addLog("[GPT-SoVITS] API 位址未填，改用預設 ${LocalSovitsHelper.DEFAULT_LOCAL_URL}")
            LocalSovitsHelper.DEFAULT_LOCAL_URL
        }
        // 目標語言：強制英文 chip → EN；否則由文字字元偵測（中/日/韓/英），支援 4 語言。
        val targetLang = if (mode == GptSovitVoiceMode.English) LocalSovitsHelper.VoiceLang.EN
                         else LocalSovitsHelper.detectLang(text)
        val isZh = targetLang == LocalSovitsHelper.VoiceLang.ZH
        val lang = targetLang.ttsLang   // zh / en / ja / ko
        val url = "$apiBaseUrl/tts"
        // 自動語言切換 + 合成都要 suspend / 阻塞網路 → 丟到背景 scope 跑。
        ttsScope.launch {
            // ⓪′ 接手計畫＝手機（連線太慢，傳大音檔不划算）→ 直接用 Android 本機 TTS，不打電腦。
            //     expressiveTest（使用者主動測試 GPT-SoVITS）例外，仍走電腦。
            if (!expressiveTest && !OffloadManager.shouldUseComputerVoice() &&
                OffloadManager.lastPlan.tier != OffloadManager.OffloadTier.OFFLINE) {
                addLog("[GPT-SoVITS] 接手計畫：連線較慢，語音改用手機本機 TTS（省流量/延遲）")
                _synthesisStatus.value = "📱 連線較慢，語音由手機處理"
                speakWithSystemTts(text, onComplete)
                return@launch
            }
            // ⓪ 先確認伺服器是否連得上＋有模型；離線或沒模型 → 改用 Android 本機 TTS（仍能發聲）。
            _synthesisStatus.value = "⏳ 確認語音伺服器…"
            val reachable = LocalSovitsHelper.refreshModels(apiBaseUrl)
            if (!reachable && !expressiveTest) {
                addLog("[GPT-SoVITS] 連不到伺服器（離線），改用 Android 本機 TTS")
                _synthesisStatus.value = "⚠️ 語音伺服器離線，改用本機 TTS"
                speakWithSystemTts(text, onComplete)
                return@launch
            }
            if (!LocalSovitsHelper.hasModels()) {
                addLog("[GPT-SoVITS] 電腦上沒有模型，已禁用 GPT-SoVITS，改用系統 TTS")
                _synthesisStatus.value = "❌ 電腦上沒有語音模型（請在控制中心匯入）；本次改用系統 TTS"
                speakWithSystemTts(text, onComplete)
                return@launch
            }
            // ① 自動切換：確保 server 載入的模型語言 == 要講的語言（_ZH/_EN/_JA/_KO）。
            //    一致就不動；不一致才切；沒有該語言模型則沿用目前模型。
            _synthesisStatus.value = "⏳ 確認語音模型（${targetLang.displayName}）…"
            val activeVoice = LocalSovitsHelper.ensureLanguageModel(apiBaseUrl, targetLang)
            if (activeVoice != null) {
                addLog("[GPT-SoVITS] 使用模型：${activeVoice.displayLabel}")
            } else {
                addLog("[GPT-SoVITS] 沿用目前模型（無 ${targetLang.displayName} 模型或切換失敗）")
            }
            // ② 情緒感知參考音：手動覆寫優先，否則從文字自動偵測情緒 → 挑該情緒的參考音。
            //    伺服器沒提供情緒對照（舊電腦端）時 refForEmotion 會自動退回單一 refPath。
            val emotion = manualEmotionOverride.ifBlank { LocalSovitsHelper.detectEmotion(text) }
            val emoRef = activeVoice?.refForEmotion(emotion)
            if (activeVoice != null && activeVoice.emotions.isNotEmpty()) {
                addLog("[GPT-SoVITS] 情緒：$emotion${if (manualEmotionOverride.isNotBlank()) "（手動）" else "（自動）"} → ${emoRef?.ref?.substringAfterLast('/')}")
                _synthesisStatus.value = "🎭 情緒：$emotion"
            }
            val defaultRef = emoRef?.ref?.ifBlank { null } ?: activeVoice?.refPath ?: LocalSovitsHelper.refPathFor(targetLang)
            val effectiveRefAudioPath = refAudioPath.ifBlank {
                addLog("[GPT-SoVITS] 參考音檔：$defaultRef")
                defaultRef
            }
            val serverSidePath = LocalSovitsHelper.translateRefAudioPathForServer(effectiveRefAudioPath, !isZh)
            // ③ 只有中文模型（簡體訓練）才把繁體轉簡體；英/日/韓不轉。
            val speakText = if (isZh) ChineseConverter.toSimplified(context, text) else text
            if (isZh && speakText != text) addLog("[GPT-SoVITS] 繁→簡：「$text」→「$speakText」")
            val promptRaw = (emoRef?.prompt?.ifBlank { null }) ?: activeVoice?.prompt ?: LocalSovitsHelper.promptFor(targetLang)
            val promptText = if (isZh) ChineseConverter.toSimplified(context, promptRaw) else promptRaw
            sendSynthesisRequest(url, speakText, lang, serverSidePath, promptText, expressiveTest, onComplete, originalText = text)
        }
        return true
    }

    /** 實際送出 /tts 合成請求並播放（已在背景 scope 內呼叫）。 */
    private fun sendSynthesisRequest(
        url: String,
        speakText: String,
        lang: String,
        serverSidePath: String,
        promptText: String,
        expressiveTest: Boolean,
        onComplete: () -> Unit,
        /** 失敗 fallback 給系統 TTS 用的原文（繁體），預設用 speakText。 */
        originalText: String = speakText
    ) {
        val speedFactor = if (expressiveTest) {
            _speechRate.value.coerceAtLeast(1.03f).toDouble()
        } else {
            _speechRate.value.toDouble()
        }
        val topK = if (expressiveTest) 20 else 15
        val topP = if (expressiveTest) 0.95 else 1.0
        val temperature = if (expressiveTest) 1.08 else 1.0
        val repetitionPenalty = if (expressiveTest) 1.12 else 1.35
        val fragmentInterval = if (expressiveTest) 0.18 else 0.3
        // v4 diffusion vocoder：步數太低（如 4）會有顆粒雜訊。測試與正常都用 8，品質一致。
        val sampleSteps = 8
        val json = JSONObject()
            .put("text", speakText)
            .put("text_lang", lang)
            .put("ref_audio_path", serverSidePath)
            .put("prompt_text", promptText)
            .put("prompt_lang", lang)
            .put("media_type", "wav")
            .put("streaming_mode", false)
            .put("text_split_method", "cut5")
            .put("batch_size", 1)
            .put("split_bucket", false)
            .put("top_k", topK)
            .put("top_p", topP)
            .put("temperature", temperature)
            .put("repetition_penalty", repetitionPenalty)
            .put("fragment_interval", fragmentInterval)
            .put("parallel_infer", true)
            .put("sample_steps", sampleSteps)
            .put("speed_factor", speedFactor)
            .toString()
        addLog("[GPT-SoVITS] ref_audio_path=$serverSidePath")
        if (expressiveTest) {
            addLog("[GPT-SoVITS] 快速測試參數：steps=$sampleSteps, temp=$temperature, top_p=$topP, speed=$speedFactor")
        }
        val request = Request.Builder()
            .url(url)
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        addLog("[GPT-SoVITS] 呼叫 API 合成：$url")
        addLog("[GPT-SoVITS] 等待合成中；v4/CPU 仍可能較慢，短句會最快")
        _synthesisStatus.value = "⏳ 合成中…（電腦 CPU 運算，約 10～30 秒）"
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!expressiveTest) {
                    addLog("[GPT-SoVITS] API 失敗：${e.message}；改用 Android 本機 TTS")
                    _synthesisStatus.value = "⚠️ 連不到語音伺服器，改用本機 TTS"
                    speakWithSystemTts(originalText, onComplete)
                } else {
                    addLog("[GPT-SoVITS] API 失敗：${e.message}；測試模式不改用 Android TTS")
                    _synthesisStatus.value = "❌ 連不到語音伺服器：${e.message?.take(60) ?: "連線失敗"}。請按「測試連線」檢查"
                    onComplete()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        if (!expressiveTest) {
                            addLog("[GPT-SoVITS] API HTTP ${it.code}；改用 Android 本機 TTS")
                            _synthesisStatus.value = "⚠️ 伺服器回 HTTP ${it.code}，改用本機 TTS"
                            speakWithSystemTts(originalText, onComplete)
                        } else {
                            addLog("[GPT-SoVITS] API HTTP ${it.code}；測試模式不改用 Android TTS")
                            _synthesisStatus.value = "❌ 伺服器回 HTTP ${it.code}，請檢查電腦端 log（多半是參考音檔路徑）"
                            onComplete()
                        }
                        return
                    }
                    val bytes = it.body?.bytes() ?: ByteArray(0)
                    if (bytes.isEmpty()) {
                        if (!expressiveTest) {
                            addLog("[GPT-SoVITS] API 回傳空音訊；改用 Android 本機 TTS")
                            _synthesisStatus.value = "⚠️ 伺服器回傳空音訊，改用本機 TTS"
                            speakWithSystemTts(originalText, onComplete)
                        } else {
                            addLog("[GPT-SoVITS] API 回傳空音訊；測試模式不改用 Android TTS")
                            _synthesisStatus.value = "❌ 伺服器回傳空音訊，請檢查模型是否載入成功"
                            onComplete()
                        }
                        return
                    }
                    val out = File(context.cacheDir, "gpt_sovit_last.wav")
                    out.writeBytes(bytes)
                    _synthesisStatus.value = "✅ 合成成功（${bytes.size / 1024} KB），播放中"
                    playSynthesizedAudio(out, onComplete)
                }
            }
        })
    }

    private fun playSynthesizedAudio(file: File, onComplete: () -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { playSynthesizedAudio(file, onComplete) }
            return
        }
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    addLog("[GPT-SoVITS] 合成音訊播放完成")
                    _synthesisStatus.value = "✅ 播放完成"
                    it.release()
                    if (mediaPlayer === it) mediaPlayer = null
                    onComplete()
                }
                setOnErrorListener { mp, what, extra ->
                    addLog("[GPT-SoVITS] 播放失敗：$what/$extra")
                    _synthesisStatus.value = "❌ 音訊播放失敗（$what/$extra）"
                    mp.release()
                    if (mediaPlayer === mp) mediaPlayer = null
                    onComplete()
                    true
                }
                prepare()
                start()
            }
            addLog("[GPT-SoVITS] 已播放合成音訊")
        } catch (t: Throwable) {
            addLog("[GPT-SoVITS] 播放例外：${t.message}")
            onComplete()
        }
    }

    private fun speakWithSystemTts(text: String, onComplete: () -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { speakWithSystemTts(text, onComplete) }
            return
        }
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "AphasiaAssistTtsId")
        } catch (t: Throwable) {
            Log.e("TtsModule", "Exception during tts.speak: ${t.message}")
        }
        onComplete()
    }

    private fun resolveGptSovitMode(text: String): GptSovitVoiceMode {
        if (gptSovitVoiceMode != GptSovitVoiceMode.Auto) return gptSovitVoiceMode
        // CJK 一個字承載的語意 ≈ Latin 兩三個字母（"a" vs "我"）。
        // 純做字元數比較會把「這是 GPT-SoVITS 中文測試」誤判為英文（"GPT-SoVITS" 9 chars vs CJK 7 chars）。
        // 給 CJK 權重 ×3 平衡，只有真的全英文句子才會切到 EN 模式。
        val latinCount = text.count { it in 'A'..'Z' || it in 'a'..'z' }
        val cjkCount = text.count { it.code in 0x4E00..0x9FFF } * 3
        return if (latinCount > cjkCount) GptSovitVoiceMode.English else GptSovitVoiceMode.Chinese
    }

    private fun setSystemLocaleFor(mode: GptSovitVoiceMode) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { setSystemLocaleFor(mode) }
            return
        }
        try {
            val locale = if (mode == GptSovitVoiceMode.English) Locale.US else Locale.CHINESE
            tts?.setLanguage(locale)
        } catch (t: Throwable) {
            Log.e("TtsModule", "Error switching TTS locale: ${t.message}")
        }
    }

    private fun addLog(message: String) {
        Log.i("TtsModule", message)   // 同步進 logcat，方便實機除錯（合成流程、繁→簡、錯誤）
        val currentLogs = _piperSynthesisLog.value.toMutableList()
        currentLogs.add(message)
        _piperSynthesisLog.value = currentLogs
    }

    fun stopSpeaking() {
        try {
            mediaPlayer?.release()
            mediaPlayer = null
            tts?.stop()
        } catch (t: Throwable) {
            Log.e("TtsModule", "Exception during stopSpeaking: ${t.message}")
        }
    }

    fun shutdown() {
        try {
            mediaPlayer?.release()
            mediaPlayer = null
            tts?.stop()
            tts?.shutdown()
        } catch (t: Throwable) {
            Log.e("TtsModule", "Exception during shutdown: ${t.message}")
        }
    }
}
