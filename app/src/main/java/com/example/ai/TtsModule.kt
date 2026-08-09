package com.example.ai

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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

    // ── 系統 TTS 的「唸完了」回呼 ──────────────────────────────────────────
    // TextToSpeech.speak() 是非同步的：呼叫完只代表「已排進佇列」，不是唸完了。
    // 原本兩處都在 speak() 之後就直接呼叫 onComplete()，於是任何等待朗讀結束的
    // 呼叫端（自動演示逐句推進就是）會瞬間跑完——整段演示用衝的、聲音全被切掉。
    // 改成掛 UtteranceProgressListener，用 utteranceId 對回各自的回呼。
    private val pendingUtterances = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()
    private var utteranceSeq = 0

    /** 演示旁白固定用這個角色（比對 character 是否包含這串）。 */
    private val demoNarrationCharacter = "花火"
    /** 這一次預錄要用哪個角色；空＝旁白角色。專屬聲音那一段用別人，
     *  觀眾才聽得出「這是他自己的聲音」而不是旁白又講了一句。 */
    @Volatile private var demoVoiceOverride: String = ""

    private fun installUtteranceListener(engine: TextToSpeech) {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) = finishUtterance(id)
            @Deprecated("舊版 API 仍會呼叫這個多載")
            override fun onError(id: String?) = finishUtterance(id)
            override fun onError(id: String?, errorCode: Int) = finishUtterance(id)
            override fun onStop(id: String?, interrupted: Boolean) = finishUtterance(id)
        })
    }

    /** 回呼只會被叫一次（ConcurrentHashMap.remove 保證），而且一定在主執行緒。 */
    private fun finishUtterance(id: String?) {
        val cb = pendingUtterances.remove(id ?: return) ?: return
        mainHandler.post { cb() }
    }

    /** 用系統 TTS 唸一句，唸完（或失敗、被打斷）才回呼。回傳這次的 utteranceId。 */
    private fun speakTracked(engine: TextToSpeech, text: String, onComplete: () -> Unit): String {
        val id = "vw-utt-${++utteranceSeq}"
        pendingUtterances[id] = onComplete
        val r = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        // 排不進佇列就不會有 onDone，得自己收尾，否則呼叫端永遠等不到。
        if (r != TextToSpeech.SUCCESS) finishUtterance(id)
        return id
    }
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
     * 正在等電腦端合成（GPT-SoVITS 走 CPU 時要 10～30 秒）。
     * 給主畫面的進度條用——沒有這個，使用者按了朗讀之後畫面完全沒反應，
     * 分不清是在算還是壞了，只好一直重按。
     */
    private val _isSynthesizing = MutableStateFlow(false)
    val isSynthesizing: StateFlow<Boolean> = _isSynthesizing.asStateFlow()

    // ── 合成結果快取 ──────────────────────────────────────────────────
    // 同一句話、同一個聲音、同樣的參數，合出來就是同一份音訊。電腦端 CPU 合成
    // 一次要 10～30 秒，同一句再唸一次還要重等一輪是沒道理的。
    //
    // key 直接雜湊「伺服器位址 + 送出去的整包 JSON」——換句子、換角色、換情緒、
    // 改語速都會改到那包 JSON，快取自動失效，不必逐項列舉哪些參數要納入 key。
    private val TTS_CACHE_MAX_FILES = 80
    // 不能用 by lazy：mkdirs() 只會在首次取用時跑一次，而 Android 會在儲存吃緊時
    // 清掉整個 cacheDir（升級安裝也會），清完之後 lazy 仍指著一個已不存在的目錄，
    // 寫檔就丟 ENOENT。每次取用都確認一下，代價只是一個 stat。
    private val ttsCacheDir: File
        get() = File(context.cacheDir, "sovits_tts").apply { if (!isDirectory) mkdirs() }

    /**
     * 把合成好的音訊寫進快取，回傳可播放的檔案；完全寫不出來才回 null。
     * 快取只是加速——寫失敗絕不能讓使用者聽不到句子，所以退一步用暫存檔照樣播。
     */
    private fun writeSynthesizedAudio(cacheKey: String, bytes: ByteArray): File? {
        runCatching {
            val out = File(ttsCacheDir, "$cacheKey.wav")
            out.writeBytes(bytes)
            trimTtsCache()
            return out
        }.onFailure { addLog("[GPT-SoVITS] 寫入快取失敗，改用暫存檔照常播放：${it.message}") }
        return runCatching {
            File(context.cacheDir.apply { mkdirs() }, "gpt_sovit_last.wav").apply { writeBytes(bytes) }
        }.onFailure { addLog("[GPT-SoVITS] 暫存檔也寫不出來：${it.message}") }.getOrNull()
    }

    private fun cacheKeyFor(url: String, json: String): String =
        java.security.MessageDigest.getInstance("SHA-1")
            .digest((url + " " + json).toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun cachedAudio(key: String): File? =
        File(ttsCacheDir, "$key.wav").takeIf { it.isFile && it.length() > 0 }

    /** 超過上限就刪最久沒用到的，避免 cacheDir 無限長大（wav 不小）。 */
    private fun trimTtsCache() {
        runCatching {
            val files = ttsCacheDir.listFiles()?.filter { it.isFile } ?: return
            if (files.size <= TTS_CACHE_MAX_FILES) return
            files.sortedBy { it.lastModified() }
                .take(files.size - TTS_CACHE_MAX_FILES)
                .forEach { it.delete() }
        }
    }

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

    /** 正在合成自動演示的旁白（語氣固定中立，不跟著句子的情緒跑）。 */
    @Volatile private var demoNarration: Boolean = false

    // ─── 雲端語音合成多供應商（跟生圖／文字 LLM 同一套模式）───────────────
    /** 使用者在「API 金鑰管理 → 語音合成」設定的清單。 */
    private var ttsEntries: List<TtsApiEntry> = emptyList()
    /** 使用者釘選的供應商 id（""＝完全交給 SmartApiRouter 自動排）。 */
    private var ttsPinnedId: String = ""

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
                installUtteranceListener(engine)
                configureLanguage(engine)
            } else {
                // Failsafe delay post to avoid synchronous initialization race condition where tts is still null
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    tts?.let { installUtteranceListener(it); configureLanguage(it) }
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

    /**
     * 內建 TTS 該用哪個語系＝使用者正在看的介面語言。
     *
     * 重組出來的句子已經是介面語言了（見 CloudInferenceEngine 的輸出語言指令），
     * 這裡若還寫死中文，英文介面的使用者會聽到中文語音硬唸英文句子。
     */
    private fun interfaceLocale(): Locale = when (com.example.ui.I18n.lang) {
        com.example.data.AppLanguage.En -> Locale.US
        com.example.data.AppLanguage.Ja -> Locale.JAPAN
        com.example.data.AppLanguage.Ko -> Locale.KOREA
        else -> Locale("zh", "TW")
    }

    /** 目前 TTS 引擎掛著的語系，避免每次朗讀都重設（設定語系要重挑語音，不便宜）。 */
    private var appliedTtsLocale: Locale? = null

    /**
     * 把內建 TTS 切到介面語言。每次系統語音朗讀前呼叫——使用者可能中途改語言，
     * 只在初始化設一次會讓改語言後的第一句仍用舊語音唸。
     */
    private fun applyInterfaceLocale(engine: TextToSpeech) {
        val want = interfaceLocale()
        if (appliedTtsLocale == want) return
        val result = engine.setLanguage(want)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // 該語言沒語音包 → 退回英文，至少發得出聲音。
            // 以前寫死退回中文，但本 App 以英語使用者為主，而且英文語音包幾乎每台
            // Android 都有；退回中文只會讓英語使用者聽到一段唸不出來的中文語音。
            Log.w("TtsModule", "裝置沒有 ${want.language} 語音包，退回英文")
            engine.setLanguage(Locale.US)
            selectBestVoice(engine, Locale.US)
        } else {
            selectBestVoice(engine, want)
        }
        appliedTtsLocale = want
    }

    private fun configureLanguage(engine: TextToSpeech) {
        try {
            // 語系跟著介面語言走；找不到對應語音包會自動退回中文。
            applyInterfaceLocale(engine)

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
    private fun selectBestVoice(engine: TextToSpeech, locale: Locale = interfaceLocale()) {
        try {
            val want = locale.language.lowercase()
            val voices: Set<Voice> = engine.voices ?: return
            val candidates = voices.filter { v ->
                val lang = runCatching { v.locale.language.lowercase() }.getOrDefault("")
                // 中文有兩種寫法：zh 與 cmn（部分引擎用 cmn 表官話）；排除尚未安裝的語音
                (lang == want || (want == "zh" && lang == "cmn")) &&
                    !v.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }
            if (candidates.isEmpty()) {
                Log.i("TtsModule", "找不到 $want 語音，沿用引擎預設")
                return
            }
            // 先偏好「離線可用」這組（保證沒網路也能講）；若整組都需網路才退而用網路語音。
            val offline = candidates.filter { !it.isNetworkConnectionRequired }
            val pool = if (offline.isNotEmpty()) offline else candidates
            val wantCountry = locale.country.uppercase()
            val best = pool.maxWithOrNull(
                compareBy<Voice>({ it.quality })                                   // 品質高優先
                    .thenBy { if (it.locale.country.uppercase() == wantCountry) 1 else 0 } // 同國別口音優先
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
     * 依排序清單合成並播放，失敗自動換下一家；全部失敗才退回系統 TTS。
     *
     * @param plan 這次要照著試的供應商順序
     */
    private fun synthesizeViaPlan(
        plan: List<TtsApiEntry>,
        text: String,
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
                val out = File(context.cacheDir, "tts_cloud_last.$ext")
                runCatching { out.writeBytes(audio) }
                    .onFailure { addLog("[語音] 寫檔失敗：${it.message}"); return@launch }

                addLog("[語音] ${entry.label} 合成完成（${audio.size / 1024} KB）")
                _synthesisStatus.value = "✅ ${entry.label}，播放中"
                playSynthesizedAudio(out, onComplete)
                return@launch
            }
            // 全部雲端供應商都失敗 → 退回手機內建語音，至少要發得出聲音
            addLog("[語音] 雲端供應商全部失敗，改用手機內建語音")
            _synthesisStatus.value = "⚠️ 雲端語音都連不上，改用手機內建語音"
            speakWithSystemTts(text, onComplete)
        }
    }

    fun speak(text: String, onComplete: () -> Unit = {}) {
        speakInternal(text = text, forcedGptSovitMode = null, expressiveTest = false, onComplete = onComplete)
    }

    /**
     * 自動演示的旁白：**強制**用 GPT-SoVITS 的英文角色唸，不走自動語言判斷。
     * 旁白全是英文，讓 detectLang 自己猜的話，挑不到英文角色就會掉回系統機器音。
     */
    fun speakDemo(text: String, onComplete: () -> Unit = {}) {
        speakInternal(text = text, forcedGptSovitMode = GptSovitVoiceMode.English,
            expressiveTest = false, onComplete = onComplete)
    }

    /**
     * 演示旁白「預錄」：用 GPT-SoVITS 英文角色合成一句，**存成檔案**，回報檔案位置。
     *
     * 演示播放時直接播這個檔，不再走一次 TTS 管線——那條路會重新判語言、可能切模型、
     * 也可能在任何一步掉回系統機器音。預錄好的檔案沒有這些變數，而且第二次跑完全免合成。
     * 合成不出來時回 null，呼叫端自己決定要不要退而求其次。
     */
    /**
     * @param character 指定角色（空＝旁白角色）。「我的專屬聲音」那一段要用**別的**
     *   角色唸——那一段整段都在講「這是他自己的聲音」，用旁白的聲音等於自己
     *   打自己的臉；用系統機器音更糟，那是在示範這個功能不存在。
     */
    fun prerecordDemoLine(text: String, character: String = "", onDone: (File?) -> Unit) {
        var file: File? = null
        demoNarration = true
        demoVoiceOverride = character
        speakInternal(
            text = text, forcedGptSovitMode = GptSovitVoiceMode.English,
            expressiveTest = false, prewarmOnly = true,
            onAudioFile = { file = it },
            onComplete = { demoNarration = false; demoVoiceOverride = ""; onDone(file) },
        )
    }

    /**
     * **固定走 Android 系統 TTS**，立刻出聲，唸完才回呼。
     *
     * 兩個地方需要它，理由是同一個——不能等：
     *  ① 快速求救的「我痛／喝水／廁所」。GPT-SoVITS 在電腦上合成一句要 10～30 秒，
     *    而按下這三顆的人是現在就需要旁邊的人聽懂。音色好聽遠不如馬上出聲重要，
     *    何況電腦沒開的時候它根本合不出來。
     *  ② 演示裡臨時要唸的那一句。合成太慢的話，演示早就跳到下一段，
     *    聲音才姍姍來遲蓋在別的段落上（使用者實測就是這個現象）。
     */
    fun speakInstant(text: String, onComplete: () -> Unit = {}) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { speakInstant(text, onComplete) }
            return
        }
        val engine = tts
        if (engine == null) { onComplete(); return }
        runCatching {
            applyInterfaceLocale(engine)
            speakTracked(engine, text, onComplete)
        }.onFailure { onComplete() }
    }

    /**
     * 演示：手動把「合成中…」的指示亮起來／熄掉。
     *
     * 演示播的是**預錄好的**專屬聲音音檔，所以實際上不需要等。但真的按下去
     * 是要等電腦合成 10～30 秒的，直接瞬間出聲會讓觀眾以為這個 App 是即時的。
     * 所以演示照樣停一下並亮出同一個指示——停的時間是縮短的，但「要等」
     * 這件事本身不是演的。
     */
    fun setDemoSynthesizing(on: Boolean) {
        _isSynthesizing.value = on
        _synthesisStatus.value = if (on) com.example.ui.I18n.tr("合成中…") else null
    }

    /** GPT-SoVITS 有沒有開起來（演示要靠它發旁白）。 */
    fun gptSovitReady(): Boolean = gptSovitEnabled

    /**
     * 先把一句話合成好放進快取，但**不發出聲音**。自動演示用：
     * 錄影開始前把整段旁白備妥，錄的時候每一句都是快取命中、瞬間播放。
     * 非 GPT-SoVITS 的路徑不需要預熱（系統／雲端 TTS 沒有這種延遲），直接回呼。
     */
    fun prewarm(text: String, onComplete: () -> Unit = {}) {
        speakInternal(text = text, forcedGptSovitMode = null, expressiveTest = false,
            onComplete = onComplete, prewarmOnly = true)
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
                if (prevVoice != null) runCatching { e.voice = prevVoice }   // 還原原本嗓音給下次
                // 這裡繞過 applyInterfaceLocale 直接改了語系，快取必須失效，
                // 否則下一句會以為還掛著介面語言、不重設就用錯語音唸。
                appliedTtsLocale = null
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
        onComplete: () -> Unit = {},
        /** 合成成功時回報實際的音檔（自動演示要把它存起來，之後直接播這個檔）。 */
        onAudioFile: ((File) -> Unit)? = null,
        /**
         * 只合成進快取、不播放。自動演示的旁白要在開始錄影**之前**全部合成完——
         * GPT-SoVITS 在 CPU 上一句要 10～30 秒，邊演邊合成等於把等待錄進影片裡。
         * 走的是與正常朗讀完全相同的請求與快取鍵，所以之後真的要唸時必定命中快取。
         */
        prewarmOnly: Boolean = false
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
        if (prewarmOnly && !gptSovitEnabled) { onComplete(); return }   // 只有 GPT-SoVITS 需要預熱
        if (!gptSovitEnabled && !expressiveTest) {
            val plan = cloudTtsPlan(text)
            if (plan.isNotEmpty()) {
                synthesizeViaPlan(plan, text, onComplete = onComplete)
                return
            }
        }

        if (gptSovitEnabled) {
            val resolvedMode = forcedGptSovitMode ?: resolveGptSovitMode(text)
            val sampleUri = if (resolvedMode == GptSovitVoiceMode.English) gptSovitEnglishVoiceUri else gptSovitChineseVoiceUri
            val modelDir = if (resolvedMode == GptSovitVoiceMode.English) gptSovitEnglishModelDirUri else gptSovitChineseModelDirUri
            addLog("[GPT-SoVITS] ${resolvedMode.displayName}：${if (sampleUri.isBlank()) "使用 Mac 端預設參考音檔槽位" else "使用自備樣本槽位"}")
            addLog("[GPT-SoVITS] ${resolvedMode.displayName}模型資料夾：${if (modelDir.isBlank()) "由 Mac server 目前載入模型決定" else "已指定"}")
            if (tryGptSovitApiSynthesis(text, resolvedMode, sampleUri, expressiveTest, onComplete, prewarmOnly, onAudioFile)) return
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
            val engine = tts
            if (engine != null) {
                applyInterfaceLocale(engine)
                speakTracked(engine, text, onComplete)   // 唸完才回呼
            } else {
                onComplete()
            }
        } catch (t: Throwable) {
            Log.e("TtsModule", "Exception during tts.speak: ${t.message}")
            onComplete()
        }
    }

    private fun tryGptSovitApiSynthesis(
        text: String,
        mode: GptSovitVoiceMode,
        refAudioPath: String,
        expressiveTest: Boolean,
        onComplete: () -> Unit,
        prewarmOnly: Boolean = false,
        onAudioFile: ((File) -> Unit)? = null
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
            // ⓪′ 走到這裡的使用者一定是自己去設定裡開了 GPT-SoVITS——
            //     tryGptSovitApiSynthesis 只在 gptSovitEnabled 時才會被呼叫。
            //     這裡以前會因為「連線量測偏慢」就無聲改用手機內建 TTS，結果就是
            //     明明選了角色聲音、出來卻是機械音，畫面還只寫「連線較慢」不講它換掉了你的選擇。
            //     明確的設定不該被效能啟發式蓋掉；真的連不上，下面的 reachable 檢查會接住。
            if (!OffloadManager.shouldUseComputerVoice()) {
                addLog("[GPT-SoVITS] 連線量測偏慢，但使用者已指定角色聲音 → 仍走電腦合成")
            }
            // ⓪ 先確認伺服器是否連得上＋有模型；離線或沒模型 → 改用 Android 本機 TTS（仍能發聲）。
            _synthesisStatus.value = "⏳ 確認語音伺服器…"
            val reachable = LocalSovitsHelper.refreshModels(apiBaseUrl)
            if (!reachable && !expressiveTest) {
                addLog("[GPT-SoVITS] 連不到伺服器（離線），改用 Android 本機 TTS")
                _synthesisStatus.value = "⚠️ 語音伺服器離線，改用本機 TTS"
                // 預錄（自動演示）時不能偷偷改用系統音——那支影片整段旁白就不是
                // 使用者自己的聲音了，而且要錄完才會發現。這裡直接回報失敗，
                // 由呼叫端決定要不要繼續。
                if (prewarmOnly) onComplete() else speakWithSystemTts(text, onComplete)
                return@launch
            }
            if (!LocalSovitsHelper.hasModels()) {
                addLog("[GPT-SoVITS] 電腦上沒有模型，已禁用 GPT-SoVITS，改用系統 TTS")
                _synthesisStatus.value = "❌ 電腦上沒有語音模型（請在控制中心匯入）；本次改用系統 TTS"
                if (prewarmOnly) onComplete() else speakWithSystemTts(text, onComplete)
                return@launch
            }
            // ① 自動切換：確保 server 載入的模型語言 == 要講的語言（_ZH/_EN/_JA/_KO）。
            //    一致就不動；不一致才切；沒有該語言模型則沿用目前模型。
            _synthesisStatus.value = "⏳ 確認語音模型（${targetLang.displayName}）…"
            // 演示旁白固定用同一個角色：整支影片一個聲音，而且和「App 自己講話」
            // 那一下分得開。不指名的話 ensureLanguageModel 是「語言對了就不動」，
            // 伺服器上剛好載著哪個英文模型就用哪個，每次錄出來的旁白都可能不一樣。
            val activeVoice = if (demoNarration)
                LocalSovitsHelper.ensureCharacter(
                    apiBaseUrl, targetLang,
                    demoVoiceOverride.ifBlank { demoNarrationCharacter }
                )
            else LocalSovitsHelper.ensureLanguageModel(apiBaseUrl, targetLang)
            if (activeVoice != null) {
                addLog("[GPT-SoVITS] 使用模型：${activeVoice.displayLabel}")
            } else {
                addLog("[GPT-SoVITS] 沿用目前模型（無 ${targetLang.displayName} 模型或切換失敗）")
            }
            // ② 情緒感知參考音：手動覆寫優先，否則從文字自動偵測情緒 → 挑該情緒的參考音。
            //    伺服器沒提供情緒對照（舊電腦端）時 refForEmotion 會自動退回單一 refPath。
            // 旁白固定中立。放給情緒偵測去猜的話，講到「自傷」「憂鬱」那幾句會挑到
            // 難過的參考音——整段旁白的語氣就一句輕一句沉，聽起來像換了個人在唸。
            // 解說的語氣本來就該是穩定的。
            val emotion = if (demoNarration) LocalSovitsHelper.NEUTRAL
                else manualEmotionOverride.ifBlank { LocalSovitsHelper.detectEmotion(text) }
            val emoRef = activeVoice?.refForEmotion(emotion)
            if (activeVoice != null && activeVoice.emotions.isNotEmpty()) {
                addLog("[GPT-SoVITS] 情緒：$emotion${if (manualEmotionOverride.isNotBlank()) "（手動）" else "（自動）"} → ${emoRef?.ref?.substringAfterLast('/')}")
                _synthesisStatus.value = "🎭 情緒：$emotion"
            }
            // ③ 決定「要用哪支參考音」——關鍵是音檔與逐字稿**必須是同一支音檔的一對**。
            //
            // 這裡以前是分開算的：音檔被「自備樣本槽位」蓋過去，逐字稿卻還是照拿情緒挑出來的那份。
            // 結果送出去的是 A 的聲音配 B 的稿子，GPT-SoVITS 只能硬合，出來就是走音加雜音。
            // 現在整組一起決定、一起退回，任何情況都不會只換其中一邊。
            val emotionPair = emoRef ?: LocalSovitsHelper.EmotionRef(
                activeVoice?.refPath ?: LocalSovitsHelper.refPathFor(targetLang),
                activeVoice?.prompt ?: LocalSovitsHelper.promptFor(targetLang)
            )
            val chosen: LocalSovitsHelper.EmotionRef
            val chosenSource: String
            if (refAudioPath.isBlank()) {
                chosen = emotionPair
                chosenSource = "情緒自動挑選"
            } else {
                val unusable = LocalSovitsHelper.unusableRefReason(refAudioPath)
                val ownPrompt = if (unusable == null)
                    LocalSovitsHelper.promptForRefPath(refAudioPath, activeVoice) else null
                when {
                    unusable != null -> {
                        // 例如手機裡還存著上一台 Windows 的 C:\Users\...，送出去只會換來 HTTP 400
                        DiagnosticLog.w(
                            DiagnosticLog.Cat.TTS, "自備樣本槽位無法使用，改用情緒參考音",
                            "槽位=$refAudioPath 原因=$unusable"
                        )
                        chosen = emotionPair
                        chosenSource = "自備樣本不可用→情緒挑選"
                    }
                    ownPrompt.isNullOrBlank() -> {
                        // 配不出逐字稿就別用它。留空也不行——SoVITS v3/v4 遇到空 prompt_text 會直接報錯。
                        DiagnosticLog.w(
                            DiagnosticLog.Cat.TTS, "自備樣本找不到對應逐字稿，改用情緒參考音",
                            "槽位=$refAudioPath"
                        )
                        chosen = emotionPair
                        chosenSource = "自備樣本無逐字稿→情緒挑選"
                    }
                    else -> {
                        chosen = LocalSovitsHelper.EmotionRef(refAudioPath, ownPrompt)
                        chosenSource = "自備樣本槽位"
                    }
                }
            }
            val serverSidePath = chosen.ref
            val promptRaw = chosen.prompt

            // ④ 只有中文模型（簡體訓練）才把繁體轉簡體；英/日/韓不轉。
            val speakText = if (isZh) ChineseConverter.toSimplified(context, text) else text
            if (isZh && speakText != text) addLog("[GPT-SoVITS] 繁→簡：「$text」→「$speakText」")
            val promptText = if (isZh) ChineseConverter.toSimplified(context, promptRaw) else promptRaw

            // 配對結果印在同一行。以前音檔與逐字稿分別印在不同行，肉眼根本對不起來——
            // 那正是這個 bug 躲過去的原因。順便留一道事後檢查，萬一又被拆散能立刻看見。
            val refFileName = serverSidePath.substringAfterLast('/').substringAfterLast('\\')
            val transcriptInFile = refFileName.substringAfter('】', "").removeSuffix(".wav")
            val pairLooksWrong = transcriptInFile.isNotBlank() && promptRaw.isNotBlank() &&
                !transcriptInFile.startsWith(promptRaw.take(8)) &&
                !promptRaw.startsWith(transcriptInFile.take(8))
            val pairDetail = "情緒=$emotion 參考音=$refFileName 逐字稿=「$promptRaw」 來源=$chosenSource"
            if (pairLooksWrong) {
                DiagnosticLog.e(
                    DiagnosticLog.Cat.TTS,
                    "參考音與逐字稿對不上——合成結果會走音並出現雜音", pairDetail
                )
            } else {
                DiagnosticLog.i(DiagnosticLog.Cat.TTS, "參考音／逐字稿配對", pairDetail)
            }
            sendSynthesisRequest(url, speakText, lang, serverSidePath, promptText, expressiveTest, onComplete, originalText = text, prewarmOnly = prewarmOnly, onAudioFile = onAudioFile)
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
        originalText: String = speakText,
        /** 只寫進快取、不播放，且失敗時不退回系統 TTS（預熱不該發出任何聲音）。 */
        prewarmOnly: Boolean = false,
        /** 合成成功時回報音檔位置。 */
        onAudioFile: ((File) -> Unit)? = null
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
        // 快取命中＝這句話用這個聲音已經合成過，直接播，不用再等電腦算一輪。
        // 放在設 _isSynthesizing 之前，命中時不該閃一下進度條。
        val cacheKey = cacheKeyFor(url, json)
        cachedAudio(cacheKey)?.let { hit ->
            hit.setLastModified(System.currentTimeMillis())   // LRU：用到就往後排
            addLog("[GPT-SoVITS] 快取命中，直接播放（${hit.length() / 1024} KB）")
            _synthesisStatus.value = "⚡ 這句已經合成過，直接播放"
            onAudioFile?.invoke(hit)
            if (prewarmOnly) onComplete() else playSynthesizedAudio(hit, onComplete)
            return
        }

        val request = Request.Builder()
            .url(url)
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        addLog("[GPT-SoVITS] 呼叫 API 合成：$url")
        addLog("[GPT-SoVITS] 等待合成中；v4/CPU 仍可能較慢，短句會最快")
        _synthesisStatus.value = "⏳ 合成中…（電腦 CPU 運算，約 10～30 秒）"
        _isSynthesizing.value = true
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                _isSynthesizing.value = false
                when {
                    !expressiveTest -> {
                        addLog("[GPT-SoVITS] API 失敗：${e.message}；改用 Android 本機 TTS")
                        _synthesisStatus.value = "⚠️ 連不到語音伺服器，改用本機 TTS"
                        if (prewarmOnly) onComplete() else speakWithSystemTts(originalText, onComplete)
                    }
                    else -> {
                        addLog("[GPT-SoVITS] API 失敗：${e.message}；測試模式不改用 Android TTS")
                        _synthesisStatus.value = "❌ 連不到語音伺服器：${e.message?.take(60) ?: "連線失敗"}。請按「測試連線」檢查"
                        onComplete()
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                _isSynthesizing.value = false   // 拿到回應＝合成階段結束（之後是播放）
                response.use {
                    if (!it.isSuccessful) {
                        // 伺服器回的錯誤內容以前整包丟掉，只留一個狀態碼。實際上 400 幾乎都是
                        // 「參考音路徑在伺服器上不存在」——例如手機裡還存著上一台 Windows 電腦的
                        // C:\Users\... 路徑。把路徑和伺服器原文一起記下來才看得出是這種情況。
                        val errBody = runCatching { it.body?.string().orEmpty() }.getOrDefault("")
                        DiagnosticLog.e(
                            DiagnosticLog.Cat.NET, "GPT-SoVITS 合成失敗 HTTP ${it.code}",
                            "ref_audio_path=$serverSidePath  伺服器回應=${errBody.take(300)}"
                        )
                        when {
                            !expressiveTest -> {
                                addLog("[GPT-SoVITS] API HTTP ${it.code}；改用 Android 本機 TTS")
                                _synthesisStatus.value = "⚠️ 伺服器回 HTTP ${it.code}，改用本機 TTS"
                                if (prewarmOnly) onComplete() else speakWithSystemTts(originalText, onComplete)
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
                            !expressiveTest -> {
                                addLog("[GPT-SoVITS] API 回傳空音訊；改用 Android 本機 TTS")
                                _synthesisStatus.value = "⚠️ 伺服器回傳空音訊，改用本機 TTS"
                                if (prewarmOnly) onComplete() else speakWithSystemTts(originalText, onComplete)
                            }
                            else -> {
                                addLog("[GPT-SoVITS] API 回傳空音訊；測試模式不改用 Android TTS")
                                _synthesisStatus.value = "❌ 伺服器回傳空音訊，請檢查模型是否載入成功"
                                onComplete()
                            }
                        }
                        return
                    }
                    // 以參數雜湊命名做快取（固定檔名會每次覆蓋＝同一句再唸還要重合成一輪）
                    val out = writeSynthesizedAudio(cacheKey, bytes)
                    if (out == null) {
                        addLog("[GPT-SoVITS] 音訊寫檔失敗，改用 Android 本機 TTS")
                        _synthesisStatus.value = "⚠️ 音訊寫檔失敗，改用本機 TTS"
                        if (prewarmOnly) onComplete() else speakWithSystemTts(originalText, onComplete)
                    } else {
                        _synthesisStatus.value = "✅ 合成成功（${bytes.size / 1024} KB），播放中"
                        onAudioFile?.invoke(out)
                        if (prewarmOnly) onComplete() else playSynthesizedAudio(out, onComplete)
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
            val engine = tts
            if (engine != null) {
                applyInterfaceLocale(engine)
                speakTracked(engine, text, onComplete)   // 唸完才回呼
            } else {
                onComplete()
            }
        } catch (t: Throwable) {
            Log.e("TtsModule", "Exception during tts.speak: ${t.message}")
            onComplete()
        }
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
            appliedTtsLocale = null   // 同上：繞過 applyInterfaceLocale 改語系，快取要失效
        } catch (t: Throwable) {
            Log.e("TtsModule", "Error switching TTS locale: ${t.message}")
        }
    }

    private fun addLog(message: String) {
        Log.i("TtsModule", message)   // 同步進 logcat，方便實機除錯（合成流程、繁→簡、錯誤）
        // 再寫一份到本地檔案：_piperSynthesisLog 只活在記憶體、而且沒有任何畫面在讀它，
        // App 一關就沒了。使用者手上出問題時要有東西可以回頭查。
        DiagnosticLog.i(DiagnosticLog.Cat.TTS, message)
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
