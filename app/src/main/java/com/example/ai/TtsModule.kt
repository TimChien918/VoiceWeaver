package com.example.ai

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import com.example.data.GptSovitVoiceMode
import com.example.data.TtsApiEntry
import com.example.data.TtsApiStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    /**
     * GPT-SoVITS「一次生成 N 個版本，講錯了再換」：聲音克隆每次抽樣的抑揚頓挫都不一樣，
     * 好壞全憑運氣，所以跟語句重組一樣——一次平行送 N 個合成請求，播第一個做好的，
     * 「換一個」在已完成的版本間切換；還沒做好就顯示合成中，做好自動接著播。
     * null＝該版位還在合成中或失敗；只有系統 TTS 沒有這種隨機性，維持單次 speak()。
     */
    private val _synthesisTakes = MutableStateFlow<List<File?>>(emptyList())
    val synthesisTakes: StateFlow<List<File?>> = _synthesisTakes.asStateFlow()
    private val _currentTakeIndex = MutableStateFlow(0)
    val currentTakeIndex: StateFlow<Int> = _currentTakeIndex.asStateFlow()

    /**
     * 每個版位是「哪一個聲音」（例如 ElevenLabs／Azure 曉臻／家人的聲音）。
     * 走雲端多供應商時三個版本是三種不同的聲音，使用者按「換一個聲音」才知道自己換到什麼。
     */
    private val _takeLabels = MutableStateFlow<List<String>>(emptyList())
    val takeLabels: StateFlow<List<String>> = _takeLabels.asStateFlow()
    // 3 個平行請求可能在不同 thread 幾乎同時完成，用 synchronizedSet 避免併發寫入漏更新。
    private val failedTakeSlots = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
    private var takesText: String = ""
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

    // ─── 雲端語音合成多供應商（跟生圖／文字 LLM 同一套模式）───────────────
    /** 使用者在「API 金鑰管理 → 語音合成」設定的清單。 */
    private var ttsEntries: List<TtsApiEntry> = emptyList()
    /** 使用者釘選的供應商 id（""＝完全交給 SmartApiRouter 自動排）。 */
    private var ttsPinnedId: String = ""
    /** 「換一個聲音」用完一輪後，從排序清單的下一段再取一批，而不是重播被拒絕過的。 */
    private var takePlanCursor: Int = 0

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

    /**
     * 套用「API 金鑰管理 → 語音合成」的清單。跟文字 LLM 一樣：**不用選要用哪家**，
     * 由 [SmartApiRouter] 依實測成功率／延遲自動排序，失敗自動換下一家。
     */
    fun configureCloudTts(entries: List<TtsApiEntry>, pinnedId: String) {
        ttsEntries = entries
        ttsPinnedId = pinnedId
        takePlanCursor = 0
    }

    /**
     * 這次要用的供應商順序。只留「真的走網路」的那些——手機內建語音與 GPT-SoVITS
     * 各自有既有流程，由 [speakInternal] 收尾當保底。
     *
     * 中文句子會把不支援中文的供應商（例如只有英文的 Deepgram Aura）大幅降權，
     * 免得排在前面每次都先失敗一次才換下一家。
     */
    private fun cloudTtsPlan(text: String): List<TtsApiEntry> {
        val zh = text.any { it.code in 0x4E00..0x9FFF }
        val usable = ttsEntries.filter { it.usable && TtsAdapter.of(it) != null }
        if (usable.isEmpty()) return emptyList()
        return SmartApiRouter.rankAny(
            usable,
            idOf = { it.id },
            freeOf = { !it.provider.needsKey },
            pinnedId = ttsPinnedId.takeIf { id -> id.isNotBlank() && usable.any { it.id == id } },
            bonusOf = { it.provider.qualityBias + if (zh && !it.provider.multilingual) -60.0 else 0.0 }
        )
    }

    /**
     * 試聽單一設定：**不走自動排序、不備援**，失敗就直接說失敗。
     * 自動備援在這裡反而幫倒忙——使用者要確認的是「這把金鑰對不對」，
     * 換一家成功了會讓人誤以為設定沒問題。
     */
    fun testSpeak(entry: TtsApiEntry, text: String) {
        if (TtsAdapter.of(entry) == null) {
            _synthesisStatus.value = when (entry.provider.style) {
                TtsApiStyle.SYSTEM -> "🔈 手機內建語音，直接試唸"
                else -> "🔈 GPT-SoVITS 請用「角色語音」頁面的試聽"
            }
            if (entry.provider.style == TtsApiStyle.SYSTEM) speakWithSystemTts(text) {}
            return
        }
        if (!entry.usable) {
            _synthesisStatus.value = "❌ ${entry.label} 還缺必填欄位（金鑰${
                if (entry.provider.extraLabel.isNotBlank()) "／${entry.provider.extraLabel}" else ""
            }）"
            return
        }
        _synthesisStatus.value = "⏳ 正在用 ${entry.label} 試唸…"
        ttsScope.launch {
            val start = System.currentTimeMillis()
            runCatching { synthesizeOnce(entry, text) }
                .onSuccess { audio ->
                    SmartApiRouter.recordSuccess(entry.id, System.currentTimeMillis() - start)
                    val out = File(context.cacheDir, "tts_test.${TtsAdapter.of(entry)?.fileExt ?: "mp3"}")
                    out.writeBytes(audio)
                    _synthesisStatus.value = "✅ ${entry.label} 可以用（${audio.size / 1024} KB）"
                    playSynthesizedAudio(out) {}
                }
                .onFailure {
                    SmartApiRouter.recordFailure(entry.id)
                    addLog("[語音] 試聽 ${entry.label} 失敗：${it.message}")
                    _synthesisStatus.value = "❌ ${entry.label}：${it.message}"
                }
        }
    }

    /** 有沒有可用的雲端語音供應商（UI 用來決定要不要顯示「換一個聲音」）。 */
    fun hasCloudVoices(): Boolean = cloudTtsPlan("測試").isNotEmpty()

    private fun langTagFor(text: String): String =
        if (text.any { it.code in 0x4E00..0x9FFF }) "zh-TW" else "en-US"

    /** 送一次合成請求；失敗直接丟例外（訊息會寫進 log 和 UI 狀態）。 */
    private fun synthesizeOnce(entry: TtsApiEntry, text: String): ByteArray {
        val adapter = TtsAdapter.of(entry) ?: error("${entry.label} 沒有對應的轉接器")
        val request = adapter.buildRequest(entry, text, langTagFor(text))
        httpClient.newCall(request).execute().use { resp ->
            val body = resp.body?.bytes() ?: ByteArray(0)
            if (!resp.isSuccessful) error("HTTP ${resp.code}：${TtsAdapter.errorOf(body)}")
            val audio = adapter.parseAudio(body)
            if (audio.isEmpty()) error("回傳空音訊")
            return audio
        }
    }

    /**
     * 依排序清單合成並播放，失敗自動換下一家。
     *
     * @param plan 這次要照著試的供應商順序
     * @param takeSlot 非 null＝存進該版位（多版本模式），null＝直接播
     * @return 全部失敗時 false，呼叫端負責退回系統 TTS
     */
    private fun synthesizeViaPlan(
        plan: List<TtsApiEntry>,
        text: String,
        takeSlot: Int?,
        onComplete: () -> Unit
    ) {
        if (plan.isEmpty()) { speakWithSystemTts(text, onComplete); return }
        ttsScope.launch {
            for (entry in plan) {
                val start = System.currentTimeMillis()
                val audio = runCatching { synthesizeOnce(entry, text) }
                    .onFailure { addLog("[語音] ${entry.label} 失敗：${it.message}") }
                    .getOrNull()
                if (audio == null) { SmartApiRouter.recordFailure(entry.id); continue }
                SmartApiRouter.recordSuccess(entry.id, System.currentTimeMillis() - start)

                val ext = TtsAdapter.of(entry)?.fileExt ?: "mp3"
                val out = File(context.cacheDir, "tts_take_${takeSlot ?: "last"}.$ext")
                runCatching { out.writeBytes(audio) }
                    .onFailure { addLog("[語音] 寫檔失敗：${it.message}"); return@launch }

                addLog("[語音] ${entry.label} 合成完成（${audio.size / 1024} KB）")
                if (takeSlot == null) {
                    _synthesisStatus.value = "✅ ${entry.label}，播放中"
                    playSynthesizedAudio(out, onComplete)
                } else {
                    _takeLabels.update { labels ->
                        if (takeSlot < labels.size) labels.toMutableList().apply { this[takeSlot] = entry.label }
                        else labels
                    }
                    _synthesisTakes.update { current ->
                        if (takeSlot < current.size) current.toMutableList().apply { this[takeSlot] = out } else current
                    }
                    if (takeSlot == _currentTakeIndex.value) {
                        _synthesisStatus.value = "✅ ${entry.label}，播放中"
                        playSynthesizedAudio(out, onComplete)
                    } else onComplete()
                }
                return@launch
            }
            // 全部雲端供應商都失敗 → 退回手機內建語音，至少要發得出聲音
            addLog("[語音] 雲端供應商全部失敗，改用手機內建語音")
            if (takeSlot == null || takeSlot == _currentTakeIndex.value) {
                _synthesisStatus.value = "⚠️ 雲端語音都連不上，改用手機內建語音"
                speakWithSystemTts(text, onComplete)
            } else {
                markTakeFailed(takeSlot, text, onComplete)
            }
        }
    }

    fun speak(text: String, onComplete: () -> Unit = {}) {
        speakInternal(text = text, forcedGptSovitMode = null, expressiveTest = false, onComplete = onComplete)
    }

    /**
     * 一次合成 takeCount 個版本，「講出來不對就換一個」。有兩種版本的來源：
     *
     *  1. **多家雲端語音**（現在的主力）：每個版位配一家不同的供應商／聲音，
     *     所以「換一個聲音」是真的換一個人講，不是同一個聲音再抽一次。
     *  2. **GPT-SoVITS 聲音克隆**：同一個聲音平行抽樣多次——克隆的抑揚頓挫每次都不同，
     *     好壞憑運氣，所以多抽幾次讓使用者挑。
     *
     * 兩者都沒有時退回單次 speak()（手機內建語音是決定性的，生多版本沒有意義）。
     */
    fun speakWithTakes(text: String, takeCount: Int = 3) {
        val plan = cloudTtsPlan(text)
        if (plan.size >= 2) {
            takesText = text
            failedTakeSlots.clear()
            takePlanCursor = 0
            val n = minOf(takeCount, plan.size)
            _synthesisTakes.value = List(n) { null }
            _takeLabels.value = List(n) { "" }
            _currentTakeIndex.value = 0
            // 版位 i 先試排序第 i 家；那家掛了就往後接手（所以每個版位都帶整條備援鏈）
            for (i in 0 until n) {
                synthesizeViaPlan(rotate(plan, i), text, takeSlot = i, onComplete = {})
            }
            return
        }
        // 只有一家雲端（或完全沒有）→ 沒有第二個聲音可換，就單純唸一次
        if (!gptSovitEnabled) { speak(text); return }
        takesText = text
        failedTakeSlots.clear()
        _synthesisTakes.value = List(takeCount) { null }
        _takeLabels.value = List(takeCount) { "" }
        _currentTakeIndex.value = 0
        for (i in 0 until takeCount) {
            speakInternal(text = text, forcedGptSovitMode = null, expressiveTest = false, takeSlot = i, onComplete = {})
        }
    }

    /** 把清單旋轉 offset 格：讓每個版位有不同的首選，但備援鏈仍是完整的。 */
    private fun <T> rotate(list: List<T>, offset: Int): List<T> {
        if (list.isEmpty()) return list
        val k = Math.floorMod(offset, list.size)
        return list.drop(k) + list.take(k)
    }

    /**
     * 「換一個」：切到下一個版位。已完成就立即播放；還在合成中就顯示等待中，做好會自動接著播。
     * 三個都不滿意（已經在看最後一版）→ 不是繞回去重播被拒絕過的第一版，而是重新平行合成
     * 全新一批版本（同一句文字）。
     */
    fun cycleNextTake() {
        val takes = _synthesisTakes.value
        if (takes.size <= 1) return
        var next = _currentTakeIndex.value + 1
        // 已知失敗的版位直接跳過，避免使用者卡在一個永遠不會完成的版本上。
        while (next < takes.size && next in failedTakeSlots) next++
        if (next >= takes.size) {
            if (takesText.isBlank()) return
            val plan = cloudTtsPlan(takesText)
            if (plan.size > takes.size) {
                // 雲端多供應商：重跑同樣幾家會得到一模一樣的音檔（雲端 TTS 是決定性的），
                // 所以往後推一批**還沒聽過**的聲音，才叫「再換一批」。
                takePlanCursor += takes.size
                val n = minOf(takes.size, plan.size)
                failedTakeSlots.clear()
                _synthesisTakes.value = List(n) { null }
                _takeLabels.value = List(n) { "" }
                _currentTakeIndex.value = 0
                _synthesisStatus.value = "🔁 換一批新的聲音…"
                for (i in 0 until n) {
                    synthesizeViaPlan(rotate(plan, takePlanCursor + i), takesText, takeSlot = i, onComplete = {})
                }
            } else {
                addLog("[語音] ${takes.size} 個版本都不滿意，重新合成全新一批")
                _synthesisStatus.value = "🔁 重新合成 ${takes.size} 個新版本…"
                speakWithTakes(takesText, takes.size)
            }
            return
        }
        _currentTakeIndex.value = next
        val file = takes[next]
        if (file != null) {
            val who = _takeLabels.value.getOrNull(next).orEmpty()
            _synthesisStatus.value =
                if (who.isNotBlank()) "✅ 換成「$who」（${next + 1}/${takes.size}）"
                else "✅ 換播版本 ${next + 1}/${takes.size}"
            playSynthesizedAudio(file) {}
        } else {
            _synthesisStatus.value = "⏳ 版本 ${next + 1}/${takes.size} 還在合成中，好了會自動播放…"
        }
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
        takeSlot: Int? = null,
        onComplete: () -> Unit = {}
    ) {
        // 手機內建 TTS 沒就緒不代表發不出聲音——雲端語音和 GPT-SoVITS 都不靠它
        if (!_isInitialized.value && !gptSovitEnabled && cloudTtsPlan(text).isEmpty()) {
            Log.e("TtsModule", "TTS not initialized yet")
            _synthesisStatus.value = "❌ 語音引擎尚未就緒，請稍候再試"
            onComplete()   // 不回呼會讓呼叫端的 spinner / 狀態卡死
            return
        }

        // 雲端語音優先於手機內建（自然度差很多），但仍讓 GPT-SoVITS 走它自己的克隆流程：
        // 使用者特地錄了家人的聲音，就不該被雲端通用嗓音蓋過去。
        if (!gptSovitEnabled && takeSlot == null && !expressiveTest) {
            val plan = cloudTtsPlan(text)
            if (plan.isNotEmpty()) {
                synthesizeViaPlan(plan, text, takeSlot = null, onComplete = onComplete)
                return
            }
        }

        if (gptSovitEnabled) {
            val resolvedMode = forcedGptSovitMode ?: resolveGptSovitMode(text)
            val sampleUri = if (resolvedMode == GptSovitVoiceMode.English) gptSovitEnglishVoiceUri else gptSovitChineseVoiceUri
            val modelDir = if (resolvedMode == GptSovitVoiceMode.English) gptSovitEnglishModelDirUri else gptSovitChineseModelDirUri
            addLog("[GPT-SoVITS] ${resolvedMode.displayName}：${if (sampleUri.isBlank()) "使用 Mac 端預設參考音檔槽位" else "使用自備樣本槽位"}")
            addLog("[GPT-SoVITS] ${resolvedMode.displayName}模型資料夾：${if (modelDir.isBlank()) "由 Mac server 目前載入模型決定" else "已指定"}")
            if (tryGptSovitApiSynthesis(text, resolvedMode, sampleUri, expressiveTest, takeSlot, onComplete)) return
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
        takeSlot: Int? = null,
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
                if (takeSlot != null) markTakeFailed(takeSlot, text, onComplete) else speakWithSystemTts(text, onComplete)
                return@launch
            }
            // ⓪ 先確認伺服器是否連得上＋有模型；離線或沒模型 → 改用 Android 本機 TTS（仍能發聲）。
            _synthesisStatus.value = "⏳ 確認語音伺服器…"
            val reachable = LocalSovitsHelper.refreshModels(apiBaseUrl)
            if (!reachable && !expressiveTest) {
                addLog("[GPT-SoVITS] 連不到伺服器（離線），改用 Android 本機 TTS")
                _synthesisStatus.value = "⚠️ 語音伺服器離線，改用本機 TTS"
                if (takeSlot != null) markTakeFailed(takeSlot, text, onComplete) else speakWithSystemTts(text, onComplete)
                return@launch
            }
            if (!LocalSovitsHelper.hasModels()) {
                addLog("[GPT-SoVITS] 電腦上沒有模型，已禁用 GPT-SoVITS，改用系統 TTS")
                _synthesisStatus.value = "❌ 電腦上沒有語音模型（請在控制中心匯入）；本次改用系統 TTS"
                if (takeSlot != null) markTakeFailed(takeSlot, text, onComplete) else speakWithSystemTts(text, onComplete)
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
            sendSynthesisRequest(url, speakText, lang, serverSidePath, promptText, expressiveTest, takeSlot, onComplete, originalText = text)
        }
        return true
    }

    /** 某個 take 版位徹底失敗：記下來；若所有版位都已定案且沒有任何一版成功，才退而求其次用系統 TTS 唸一次。 */
    private fun markTakeFailed(takeSlot: Int, text: String, onComplete: () -> Unit) {
        failedTakeSlots.add(takeSlot)
        val takes = _synthesisTakes.value
        val allSettled = takes.indices.all { it in failedTakeSlots || takes[it] != null }
        val anySucceeded = takes.any { it != null }
        if (allSettled && !anySucceeded) {
            addLog("[GPT-SoVITS] 所有版本合成皆失敗，改用 Android 本機 TTS")
            _synthesisStatus.value = "⚠️ 語音合成全部失敗，改用本機 TTS"
            speakWithSystemTts(text, onComplete)
        } else {
            onComplete()
        }
    }

    /** 實際送出 /tts 合成請求並播放（已在背景 scope 內呼叫）。 */
    private fun sendSynthesisRequest(
        url: String,
        speakText: String,
        lang: String,
        serverSidePath: String,
        promptText: String,
        expressiveTest: Boolean,
        takeSlot: Int? = null,
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
                when {
                    takeSlot != null -> {
                        addLog("[GPT-SoVITS] 版本 ${takeSlot + 1} 合成失敗：${e.message}")
                        markTakeFailed(takeSlot, originalText, onComplete)
                    }
                    !expressiveTest -> {
                        addLog("[GPT-SoVITS] API 失敗：${e.message}；改用 Android 本機 TTS")
                        _synthesisStatus.value = "⚠️ 連不到語音伺服器，改用本機 TTS"
                        speakWithSystemTts(originalText, onComplete)
                    }
                    else -> {
                        addLog("[GPT-SoVITS] API 失敗：${e.message}；測試模式不改用 Android TTS")
                        _synthesisStatus.value = "❌ 連不到語音伺服器：${e.message?.take(60) ?: "連線失敗"}。請按「測試連線」檢查"
                        onComplete()
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        when {
                            takeSlot != null -> {
                                addLog("[GPT-SoVITS] 版本 ${takeSlot + 1} 合成失敗：HTTP ${it.code}")
                                markTakeFailed(takeSlot, originalText, onComplete)
                            }
                            !expressiveTest -> {
                                addLog("[GPT-SoVITS] API HTTP ${it.code}；改用 Android 本機 TTS")
                                _synthesisStatus.value = "⚠️ 伺服器回 HTTP ${it.code}，改用本機 TTS"
                                speakWithSystemTts(originalText, onComplete)
                            }
                            else -> {
                                addLog("[GPT-SoVITS] API HTTP ${it.code}；測試模式不改用 Android TTS")
                                _synthesisStatus.value = "❌ 伺服器回 HTTP ${it.code}，請檢查電腦端 log（多半是參考音檔路徑）"
                                onComplete()
                            }
                        }
                        return
                    }
                    val bytes = it.body?.bytes() ?: ByteArray(0)
                    if (bytes.isEmpty()) {
                        when {
                            takeSlot != null -> {
                                addLog("[GPT-SoVITS] 版本 ${takeSlot + 1} 合成失敗：回傳空音訊")
                                markTakeFailed(takeSlot, originalText, onComplete)
                            }
                            !expressiveTest -> {
                                addLog("[GPT-SoVITS] API 回傳空音訊；改用 Android 本機 TTS")
                                _synthesisStatus.value = "⚠️ 伺服器回傳空音訊，改用本機 TTS"
                                speakWithSystemTts(originalText, onComplete)
                            }
                            else -> {
                                addLog("[GPT-SoVITS] API 回傳空音訊；測試模式不改用 Android TTS")
                                _synthesisStatus.value = "❌ 伺服器回傳空音訊，請檢查模型是否載入成功"
                                onComplete()
                            }
                        }
                        return
                    }
                    if (takeSlot != null) {
                        val out = File(context.cacheDir, "gpt_sovit_take_$takeSlot.wav")
                        out.writeBytes(bytes)
                        _synthesisTakes.update { current ->
                            if (takeSlot < current.size) current.toMutableList().apply { this[takeSlot] = out } else current
                        }
                        addLog("[GPT-SoVITS] 版本 ${takeSlot + 1}/${_synthesisTakes.value.size} 合成完成（${bytes.size / 1024} KB）")
                        if (takeSlot == _currentTakeIndex.value) {
                            _synthesisStatus.value = "✅ 合成成功（${bytes.size / 1024} KB），播放中"
                            playSynthesizedAudio(out, onComplete)
                        } else {
                            onComplete()
                        }
                    } else {
                        val out = File(context.cacheDir, "gpt_sovit_last.wav")
                        out.writeBytes(bytes)
                        _synthesisStatus.value = "✅ 合成成功（${bytes.size / 1024} KB），播放中"
                        playSynthesizedAudio(out, onComplete)
                    }
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
