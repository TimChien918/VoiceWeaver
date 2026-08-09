package com.example.ai

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.data.ApiKeyOverrides
import com.example.data.CloudProvider
import com.example.data.LlmApiEntry
import com.example.data.LlmApiStyle
import com.example.data.LlmDefaults
import com.example.data.LlmProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 雲端 + 本地混合推理：
 * - 雲端 API 可用時優先拿較大模型重組句子。
 * - API key 缺失、網路逾時、HTTP 錯誤、JSON 解析失敗時，立刻退回本地 Qwen/Regex。
 * - key 只從 BuildConfig 讀取，絕不硬編碼在 repo。
 */
/** 「AI 直接聽」要用誰的算力。 */
enum class AudioUnderstandPreference {
    /** 電腦端就緒就用它，否則走雲端（預設）。 */ Auto,
    /** 只用電腦端本機模型；不可用就直接失敗，不偷偷改走雲端。 */ LocalOnly,
    /** 只用雲端。 */ CloudOnly,
}

class HybridInferenceEngine(
    private val context: Context,
    private val provider: CloudProvider,
    val fallbackEngine: InferenceEngine
) : InferenceEngine {

    /**
     * 電腦端位址。與 GPT-SoVITS 共用同一台電腦、同一組設定，
     * 只是音訊理解走 9883、語音合成走 9880。
     */
    @Volatile var sovitsBaseUrl: String = LocalSovitsHelper.DEFAULT_LOCAL_URL

    @Volatile var audioUnderstandingPreference: AudioUnderstandPreference = AudioUnderstandPreference.Auto

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    override suspend fun reconstructSentence(vault: GlobalTagsVault): Result<ReconstructedData> {
        // 接手計畫＝電腦時，先把重組丟給使用者自己的電腦(9882)，最省手機；它是區網/USB 私有連線，
        // 即使設成「只用本機」也可用（非雲端）。電腦不可用/回空 → 往下走原本的雲端/本機路徑。
        if (OffloadManager.shouldUseComputerText()) {
            val start = System.currentTimeMillis()
            val p = buildReconstructPrompt(vault)
            val txt = OffloadManager.computerReconstruct(p.system, p.user)
                ?.trim()?.replace(Regex("\\s*\\n\\s*"), " ")
            if (!txt.isNullOrBlank()) {
                return Result.success(
                    ReconstructedData(
                        originalText = vault.rawText,
                        reconstructedText = txt,
                        confidence = 0.9,
                        engineUsed = com.example.ui.I18n.tr("電腦接手（本機/雲端備援待命）"),
                        inferenceTimeMs = System.currentTimeMillis() - start
                    )
                )
            }
        }
        if (provider == CloudProvider.LocalOnly) return fallbackEngine.reconstructSentence(vault)
        if (NetworkQualityGate.shouldPreferLocal()) {
            Log.i(TAG, "Skip cloud inference: ${NetworkQualityGate.reason()}")
            return fallbackEngine.reconstructSentence(vault).map { local ->
                local.copy(engineUsed = "${local.engineUsed} (${NetworkQualityGate.reason()})")
            }
        }

        val start = System.currentTimeMillis()
        return try {
            // 自我一致性（self-consistency）：同一份碎詞平行取樣 N 次（略提高溫度求多樣性），
            // 用多數決挑最一致的重組結果，比單次呼叫更抗雲端模型偶發幻覺/失手。
            // 平行送出，總延遲仍受單次 16s 逾時限制，不會疊加成 N 倍等待。
            val basePrompt = buildReconstructPrompt(vault)
            val samplePrompt = basePrompt.copy(temperature = RECONSTRUCT_SAMPLE_TEMPERATURE)
            val samples = coroutineScope {
                (1..RECONSTRUCT_SAMPLES).map {
                    async {
                        runCatching {
                            tryCloudWithFailover(samplePrompt, timeoutMs = 16_000) { raw ->
                                val parsed = parseCoT(raw)
                                if (parsed.reconstructed.isBlank()) error("雲端模型回傳空句子")
                                parsed
                            }
                        }.getOrNull()
                    }
                }.awaitAll()
            }.filterNotNull()

            if (samples.isEmpty()) error("雲端模型多次取樣皆失敗")

            val ranked = rankBySelfConsistency(samples)
            val (selected, top) = ranked.first()
            val consensusNote = if (samples.size > 1) com.example.ui.I18n.tr("自我一致性 %d 取樣").format(samples.size)
                                else com.example.ui.I18n.tr("單次取樣（其餘取樣失敗）")
            Result.success(
                ReconstructedData(
                    originalText = vault.rawText,
                    reconstructedText = top.text,
                    confidence = top.confidence,
                    engineUsed = com.example.ui.I18n.tr("%s（%s／本地備援待命）").format(selected.label, consensusNote),
                    inferenceTimeMs = System.currentTimeMillis() - start,
                    alternatives = ranked.map { it.second }
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Cloud inference failed, fallback to local: ${e.message}")
            fallbackEngine.reconstructSentence(vault).map { local ->
                local.copy(engineUsed = "${local.engineUsed} (Cloud fallback: ${provider.displayName})")
            }
        }
    }

    // ─── 音訊原生理解（不經 STT）─────────────────────────────────────────
    // 只有 Gemini 風格的供應商吃 inlineData 音訊；沒有這種供應商時回報不支援，
    // 由呼叫端退回原本的 Vosk STT → 文字重組流程（離線也還能用）。

    /**
     * 有「原生音訊理解」能力的供應商：模型直接聽音訊，不經轉文字。
     * 依各家宣告的 audioStyle 判斷，不再寫死某一家。
     */
    private fun nativeAudioCandidates(): List<LlmApiEntry> =
        cloudCandidates(SmartApiRouter.Need.NATIVE_AUDIO)

    /** 只有 Whisper 轉寫能力的供應商（中間路線：雲端 STT，比裝置端 Vosk 準）。 */
    private fun transcribeCandidates(): List<LlmApiEntry> =
        cloudCandidates(SmartApiRouter.Need.TRANSCRIBE)

    /**
     * 使用者在「語音辨識」分區自己設的供應商，依清單順序試。
     *
     * 這份清單優先於 LLM 內建的轉寫端點——使用者特地設了就該用它。
     * 空的時候回空清單，呼叫端會退回舊路徑。
     */
    private fun dedicatedSttCandidates(): List<com.example.data.SttApiEntry> =
        ApiKeyOverrides.sttApis.filter { it.usable }

    /**
     * 轉寫結果的在地化。
     *
     * 中文一定要轉繁體：多數轉寫模型輸出簡體，而使用者是台灣人——
     * 碎詞欄、歷史紀錄、復健比對都吃這個字串，混著簡體會讓比對整個失準。
     * 舊的 transcribeWithWhisper 內部本來就有這一步，新路徑不能漏掉。
     */
    private fun localizeTranscript(text: String, languageTag: String): String =
        if (languageTag.startsWith("zh")) ChineseConverter.toTraditional(context, text) else text

    /**
     * 「直接聽」只認真正能聽音訊的供應商。
     *
     * 以前這裡把 Whisper 轉寫也算進來，於是只有 Whisper 時按鈕照樣出現，
     * 按下去卻是「轉成文字再重組」——使用者選的是不轉文字，拿到的是轉過文字的結果。
     * 轉寫能力另有 supportsCloudTranscription()，復健跟讀評分走那條。
     */
    override fun supportsAudioUnderstanding(): Boolean =
        // 電腦端本機模型不需要金鑰、也不算「雲端」，所以連 LocalOnly 模式都能用
        OffloadManager.audioServerReadyCached ||
            (provider != CloudProvider.LocalOnly && nativeAudioCandidates().isNotEmpty())

    override fun supportsCloudTranscription(): Boolean =
        // 專屬清單裡的本機電腦不需要金鑰也不算雲端，所以連 LocalOnly 都能用
        dedicatedSttCandidates().isNotEmpty() ||
            (provider != CloudProvider.LocalOnly && transcribeCandidates().isNotEmpty())

    /**
     * 純轉寫（復健跟讀用）：只要「他到底唸出了什麼」，不做任何重組或推測。
     *
     * 跟 [reconstructFromAudio] 的差別是這裡刻意不讓模型幫忙猜——跟讀評分要
     * 比對的是實際發音，模型若把含糊的發音「修好」成目標句，分數就失真了。
     */
    override suspend fun transcribe(
        audioFile: java.io.File,
        languageTag: String,
        promptHint: String
    ): Result<String> {
        // 使用者自己設的語音辨識供應商優先。特地去設了就該用它，
        // 而不是繼續沿用文字 LLM 附帶的轉寫端點。
        dedicatedSttCandidates().takeIf { it.isNotEmpty() }?.let { list ->
            val bytes = withContext(Dispatchers.IO) { runCatching { audioFile.readBytes() }.getOrNull() }
                ?: return Result.failure(IllegalStateException("錄音檔讀取失敗"))
            val format = audioFile.extension.ifBlank { "aac" }
            val hint = promptHint.takeIf { it.isNotBlank() }
            var lastError: String? = null
            for (entry in list) {
                val out = SttClient.transcribe(client, entry, bytes, format, languageTag, hint)
                if (out.ok) {
                    DiagnosticLog.i(DiagnosticLog.Cat.STT, "語音辨識成功（${entry.label}）",
                        "字數=${out.text!!.length}")
                    return Result.success(localizeTranscript(out.text, languageTag))
                }
                lastError = out.error
            }
            // 全部試完都失敗就直說，不要靜靜退回舊路徑——
            // 使用者設了供應商卻用到別的來源，出問題時完全查不出原因。
            return Result.failure(IllegalStateException(
                com.example.ui.I18n.tr("語音辨識失敗：%s")
                    .format(lastError?.take(120) ?: com.example.ui.I18n.tr("未知錯誤"))))
        }

        val candidates = transcribeCandidates()
        if (provider == CloudProvider.LocalOnly || candidates.isEmpty()) {
            return Result.failure(IllegalStateException(
                com.example.ui.I18n.tr("沒有可用的雲端語音轉寫供應商")))
        }
        val text = transcribeWithWhisper(audioFile, candidates, GlobalTagsVault(), languageTag, promptHint)
        return if (text.isNullOrBlank()) {
            Result.failure(IllegalStateException(com.example.ui.I18n.tr("雲端語音轉寫沒有回傳結果")))
        } else Result.success(text)
    }

    override suspend fun reconstructFromAudio(
        audioFile: java.io.File,
        vault: GlobalTagsVault
    ): Result<ReconstructedData> {
        // 每一個「還沒開始就退出」的原因都要留紀錄。以前這幾條 return 全部無聲，
        // 使用者只會看到畫面默默切回語音轉文字，完全不知道是沒金鑰還是錄音是空的。
        if (provider == CloudProvider.LocalOnly) {
            DiagnosticLog.w(DiagnosticLog.Cat.STT, "音訊理解未啟動：已設為只用本機")
            return Result.failure(IllegalStateException("已設為只用本機，音訊理解需要雲端模型"))
        }
        // 「直接聽」只走原生音訊。沒有這種供應商就直說，不拿 Whisper 轉寫來頂替——
        // 使用者按的是「不轉文字」，偷偷轉一次文字再重組不是同一件事。
        //
        // 但「沒有雲端供應商」不等於做不到：電腦端本機模型完全不需要金鑰，
        // 所以這道檢查只在「連本機也不可用」時才擋，否則沒設金鑰的使用者會被
        // 擋在門外，白白浪費已經跑在自己電腦上的模型。
        val native = nativeAudioCandidates()
        val localUsable = audioUnderstandingPreference != AudioUnderstandPreference.CloudOnly &&
            OffloadManager.audioServerReady(sovitsBaseUrl)
        if (native.isEmpty() && !localUsable) {
            DiagnosticLog.e(
                DiagnosticLog.Cat.STT, "音訊理解未啟動：雲端與電腦端都沒有可用的音訊模型",
                "已設定的 LLM=${cloudCandidates().joinToString("/") { it.label }.ifBlank { "無" }} " +
                    "電腦端(9883)=未就緒"
            )
            return Result.failure(IllegalStateException(
                "沒有可用的音訊理解：請啟動電腦端的音訊伺服器，或在設定裡加入 Gemini／OpenAI gpt-audio／Mistral Voxtral 任一"))
        }
        if (!audioFile.exists() || audioFile.length() == 0L) {
            DiagnosticLog.e(
                DiagnosticLog.Cat.STT, "音訊理解未啟動：錄音檔是空的",
                "path=${audioFile.absolutePath} exists=${audioFile.exists()} bytes=${audioFile.length()}"
            )
            return Result.failure(IllegalStateException("錄音檔是空的"))
        }
        // Gemini inline 上限 20MB；16kHz/32kbps 大約 4KB/秒，正常一句話遠低於上限。
        if (audioFile.length() > MAX_INLINE_AUDIO_BYTES) {
            DiagnosticLog.e(
                DiagnosticLog.Cat.STT, "音訊理解未啟動：錄音超過 inline 上限",
                "bytes=${audioFile.length()} limit=$MAX_INLINE_AUDIO_BYTES"
            )
            return Result.failure(IllegalStateException("錄音太長（${audioFile.length() / 1024} KB），請講短一點"))
        }

        val start = System.currentTimeMillis()
        return try {
            val b64 = withContext(Dispatchers.IO) {
                android.util.Base64.encodeToString(audioFile.readBytes(), android.util.Base64.NO_WRAP)
            }
            // mime 由副檔名決定。聲學捷徑上線後收音改成 PCM／WAV，
            // 寫死 aac 會讓部分供應商回 400，而錯誤訊息不會說是格式問題。
            val audioMime = when (audioFile.extension.lowercase()) {
                "wav" -> "audio/wav"
                "mp3" -> "audio/mpeg"
                "m4a", "mp4" -> "audio/mp4"
                else -> "audio/aac"
            }
            val prompt = buildAudioReconstructPrompt(vault, b64, audioMime = audioMime)

            // ── 先試電腦端本機模型（免金鑰、語音不出區網）──
            // 雲端的免費額度很容易爆（實測連續用幾次就 HTTP 429），而本機這條路
            // 除了沒有額度問題，病人的語音根本不會離開區網。品質不如雲端時仍可
            // 由下面的雲端流程接手，所以這裡失敗不算致命，只記錄原因。
            if (localUsable) {
                // 本機小模型不給常用詞清單——它聽不清楚時會直接從清單裡挑一句充數，
                // 詳見 buildAudioReconstructPrompt 的 vocabularyHints 說明。
                val localPrompt = buildAudioReconstructPrompt(
                    vault, b64, vocabularyHints = false, audioMime = audioMime,
                )
                val raw = OffloadManager.computerUnderstandAudio(
                    gptSovitBase = sovitsBaseUrl,
                    audioBase64 = b64,
                    mime = audioMime,
                    system = localPrompt.system,
                    user = localPrompt.user,
                )
                // 把模型「聽到什麼」原樣記下來：只記重組結果的話，分不出是聽錯還是重組錯。
                DiagnosticLog.i(
                    DiagnosticLog.Cat.STT, "電腦端本機模型原始回應",
                    raw?.replace(Regex("\\s+"), " ")?.take(300) ?: "（無回應）"
                )
                val parsed = raw?.let { runCatching { parseCoT(it) }.getOrNull() }
                if (parsed != null && parsed.reconstructed.isNotBlank()) {
                    val elapsed = System.currentTimeMillis() - start
                    DiagnosticLog.i(
                        DiagnosticLog.Cat.STT, "音訊理解成功（電腦端本機模型）",
                        "耗時=${elapsed}ms 信心=${(parsed.confidence * 100).toInt()}% 句子=「${parsed.reconstructed}」"
                    )
                    return Result.success(
                        ReconstructedData(
                            originalText = "",
                            reconstructedText = parsed.reconstructed,
                            confidence = parsed.confidence,
                            engineUsed = com.example.ui.I18n.tr("電腦本機·直接聽音訊（免金鑰）"),
                            inferenceTimeMs = elapsed,
                            alternatives = parsed.alternatives,
                        )
                    )
                }
                DiagnosticLog.w(
                    DiagnosticLog.Cat.FALLBACK, "電腦端音訊理解沒有可用結果",
                    if (audioUnderstandingPreference == AudioUnderstandPreference.LocalOnly)
                        "設定為只用本機，不轉雲端" else "改試雲端"
                )
                if (audioUnderstandingPreference == AudioUnderstandPreference.LocalOnly) {
                    error("電腦端本機音訊理解失敗（設定為只用本機）")
                }
            } else if (audioUnderstandingPreference == AudioUnderstandPreference.LocalOnly) {
                error("電腦端音訊理解伺服器未就緒（設定為只用本機）")
            } else if (audioUnderstandingPreference == AudioUnderstandPreference.Auto) {
                // 沒有這行的話，電腦端沒開時日誌裡完全看不出「本機被跳過了」，
                // 使用者只會看到一個雲端錯誤，誤以為本機根本沒接上。
                DiagnosticLog.w(
                    DiagnosticLog.Cat.FALLBACK, "電腦端音訊伺服器未就緒 → 改用雲端",
                    "位址=$sovitsBaseUrl:${OffloadManager.AUDIO_PORT}（確認電腦端已啟動，且 USB 有 adb reverse tcp:9883）"
                )
            }

            // 走到這裡＝要用雲端。沒有雲端音訊供應商就直說，別讓下面的取樣白跑一輪。
            if (native.isEmpty()) {
                error("電腦端本機模型不可用，而且沒有設定任何能直接聽音訊的雲端供應商")
            }

            DiagnosticLog.i(
                DiagnosticLog.Cat.STT, "音訊理解開始",
                "檔案=${audioFile.length() / 1024}KB 原生音訊供應商=${native.joinToString("/") { it.label }}"
            )

            val lastSampleError = java.util.concurrent.atomic.AtomicReference<String?>(null)

            suspend fun sample(attempt: Int): Pair<LlmApiEntry, CoTParsed>? = runCatching {
                tryCloudWithFailover(
                    prompt,
                    timeoutMs = AUDIO_TIMEOUT_MS,
                    candidates = native   // 只送給真的支援音訊的供應商
                ) { raw ->
                    val parsed = parseCoT(raw)
                    if (parsed.reconstructed.isBlank()) error("模型回傳空句子")
                    parsed
                }
            }.onFailure {
                // 以前這裡是 getOrNull()，取樣全失敗只會往上丟一句「多次取樣皆失敗」，
                // 真正的原因（金鑰無效／額度用完／逾時）被整個吃掉。
                DiagnosticLog.e(
                    DiagnosticLog.Cat.STT, "音訊取樣第 $attempt 次失敗",
                    "供應商=${native.joinToString("/") { it.label }}", it
                )
                lastSampleError.compareAndSet(null, it.message)
            }.getOrNull()

            // 階梯式取樣：先打 1 次，只有「結果不夠有把握」時才補打。
            //
            // 以前一律平行送 3 次，等於每按一下就吃掉 3 次額度——那正是撞上 HTTP 429
            // 的直接原因（日誌裡三個請求的時間戳只差 3 毫秒）。實際上大多數錄音第一次
            // 就聽得很清楚，多打兩次只是把同一個答案再確認一遍。
            // 聽不清楚時才值得花額度去交叉驗證，那也正是自我一致性真正有用的場合。
            val samples = buildList {
                sample(1)?.let { add(it) }
                val first = firstOrNull()?.second
                if (first == null || first.confidence < ESCALATE_CONFIDENCE) {
                    DiagnosticLog.i(
                        DiagnosticLog.Cat.STT, "第一次取樣把握不足，補打 2 次交叉驗證",
                        "首次信心=${first?.let { (it.confidence * 100).toInt().toString() + "%" } ?: "失敗"}"
                    )
                    coroutineScope {
                        listOf(async { sample(2) }, async { sample(3) }).awaitAll()
                    }.filterNotNull().forEach { add(it) }
                }
            }

            if (samples.isEmpty()) {
                // 全失敗就是全失敗，不再偷偷改走 Whisper 轉寫。
                // 使用者選的是「不轉文字」，轉一次文字再重組會丟掉語調與停頓，
                // 那正是這條路存在的理由；真要轉文字，畫面上另有那顆按鈕由他自己按。
                // 把第一個真實原因帶上去，上層才有辦法把 429／401 翻成使用者看得懂的話
                error("音訊理解多次取樣皆失敗：${lastSampleError.get() ?: "詳細原因見診斷日誌"}")
            }

            val ranked = rankBySelfConsistency(samples)
            val (selected, top) = ranked.first()
            DiagnosticLog.i(
                DiagnosticLog.Cat.STT, "音訊理解成功",
                "供應商=${selected.label} 實際取樣=${samples.size} " +
                    "候選=${ranked.size} 信心=${(top.confidence * 100).toInt()}% 句子=「${top.text}」"
            )
            Result.success(
                ReconstructedData(
                    // 音訊路徑沒有「碎詞原文」——本來就沒經過 STT。
                    // 留空讓 UI 不要顯示「唸原始輸入」那顆按鈕（沒有原始文字可唸）。
                    originalText = "",
                    reconstructedText = top.text,
                    confidence = top.confidence,
                    engineUsed = "${selected.label}·" + com.example.ui.I18n.tr("直接聽音訊（%d 取樣，未經語音轉文字）").format(samples.size),
                    inferenceTimeMs = System.currentTimeMillis() - start,
                    alternatives = ranked.map { it.second }
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Audio understanding failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * 雲端 Whisper 轉寫（multipart，跟對話 API 完全不同的端點）。
     * 會把地點與看到的物品當成 prompt 偏置詞彙餵進去——Whisper 支援用 prompt
     * 提示詞彙，對失語症場景的同音誤判有幫助（例如提示「醫院、吃藥」）。
     */
    private suspend fun transcribeWithWhisper(
        audioFile: java.io.File,
        candidates: List<LlmApiEntry>,
        vault: GlobalTagsVault,
        languageTag: String = "zh",
        extraHint: String = ""
    ): String? = withContext(Dispatchers.IO) {
        val bytes = runCatching { audioFile.readBytes() }.getOrNull() ?: return@withContext null
        val isZh = languageTag.startsWith("zh")
        // prompt 的字體會影響 Whisper 的輸出字體——中文時開頭就用繁體明講，
        // 從源頭減少簡體輸出（收到後仍會再過一次 toTraditional 保底）。
        // 非中文語言不要餵中文提示詞，否則 Whisper 會被帶偏、把英文聽成中文。
        val hint = buildString {
            if (isZh) {
                append("以臺灣繁體中文輸出。失語症患者日常溝通。")
                if (vault.locationTag.isNotBlank()) append("地點：${vault.locationTag}。")
                if (vault.visionTags.isNotEmpty()) append("周邊：${vault.visionTags.joinToString("、")}。")
            }
            if (extraHint.isNotBlank()) append(extraHint)
        }
        for (entry in candidates) {
            val p = entry.provider
            val req = runCatching {
                WhisperTranscribeAdapter.buildRequest(
                    endpoint = p.transcribeEndpoint,
                    apiKey = entry.key,
                    model = p.transcribeModel,
                    audioBytes = bytes,
                    audioFormat = audioFile.extension.ifBlank { "aac" },
                    languageHint = languageTag,
                    promptHint = hint.ifBlank { null }
                )
            }.getOrNull() ?: continue
            val text = runCatching {
                client.newCall(req).execute().use { res ->
                    val body = res.body?.string().orEmpty()
                    if (!res.isSuccessful) error("HTTP ${res.code}: ${body.take(120)}")
                    WhisperTranscribeAdapter.parseResponse(body)
                }
            }.getOrElse {
                Log.w(TAG, "Whisper 轉寫失敗（${p.displayName}）：${it.message}")
                null
            }
            // Whisper 的中文輸出多半是簡體；使用者是台灣人，畫面與歷史都該是繁體。
            // 非中文語言不能過這一關——轉換表只處理漢字，但沒必要多跑一趟。
            if (!text.isNullOrBlank()) {
                return@withContext if (isZh) ChineseConverter.toTraditional(context, text) else text
            }
        }
        null
    }

    /**
     * 音訊理解的提示詞。與文字版共用「不要腦補」的核心約束，但改成
     * 針對「聽」的任務：允許模型依發音相近去推測，因為失語症患者
     * 常出現語音錯誤（想說「水」卻發成「揮」）——這正是走音訊的價值。
     */
    /**
     * @param vocabularyHints 要不要附上「病人常用詞／常用表達參考」。
     *
     * 實測必須關掉的情況：電腦端的小模型（Gemma 4 E4B）聽不清楚時，會直接從這份清單
     * 裡挑一句當答案。同一段「我…頭…醫院」的錄音，不給清單時它誠實回報
     * heard=「呃」、confidence=10；給了清單就變成「我要喝水」、confidence=70。
     * 把「沒聽清楚」變成「很有把握地講錯」，對這個 App 比答不出來危險得多。
     * 雲端大模型撐得住這種提示（清單確實有助於同音消歧），所以只對本機關閉。
     */
    private fun buildAudioReconstructPrompt(
        vault: GlobalTagsVault,
        audioB64: String,
        vocabularyHints: Boolean = true,
        /**
         * 錄音格式。以前寫死 audio/aac，但聲學捷徑上線後收音改成 PCM／WAV——
         * 送錯 mime 時各家的表現不一樣（有的忽略、有的直接 400），
         * 而 400 的訊息通常不會說是格式問題，查起來很花時間。
         */
        audioMime: String = "audio/aac",
    ) = CloudPrompt(
        system = """
            You help a person with aphasia say what they mean. The attached audio is that
            person speaking. Listen to it directly, work out what they most likely meant,
            and rebuild it as one natural sentence in ${com.example.ui.I18n.outputLanguageNameEn()}.
            (These instructions are in English; that is not an instruction to answer in English.)

            This person has aphasia and/or dysarthria, so:
            - Pronunciation may be off, words may be stretched or slurred, sentences may be
              incomplete, and the same word may repeat.
            - Expect phonemic paraphasia: the word they meant comes out as a similar-sounding
              different word. Infer the intended word by "sounds close AND makes sense here".
            - Prosody carries meaning: a rising ending is usually a question; fast or loud
              usually signals urgency.

            STILL, INVENT NOTHING.
            Every content word in your sentence must come from something you actually heard,
            or from the location/object tags below. You may add only the grammatical glue a
            sentence needs. You may NOT add an event, a time, a person, or a question that you
            did not hear and that the context does not supply. If you hear a clear question
            word, keep its meaning.

            If the audio contains nothing meaningful — only noise, a cough, silence — give a
            confidence of 1-20 and put your closest guess in the candidate. Do not manufacture
            a complete sentence.

            ABSOLUTELY FORBIDDEN: when you did not hear clearly, you may NOT pick a sentence
            out of the "frequent words" or "common phrasings" lists below to fill the gap.
            Those lists are only for choosing between similar-sounding options AFTER you have
            actually heard something. If you heard only scattered syllables or nothing at all,
            the correct answer is an honest "heard" field and a low confidence — not a
            plausible-sounding sentence. Saying the wrong thing harms this person more than
            saying nothing.

            CONFIDENCE is how sure you are about the rebuild (0-100):
              80-100  heard clearly, near certain
              50-79   got the gist and gave a reasonable reading (slurred speech usually lands here)
              1-49    barely audible

            Output JSON only:
            {"heard":"the syllables or words you actually heard, uncertainty included","candidates":[{"text":"most confident wording","confidence":0-100},{"text":"another wording","confidence":0-100},{"text":"a third wording","confidence":0-100}]}

            Give 3 candidates and make them GENUINELY different readings — the same slurred
            audio legitimately has several hearings, and the person picks one of these three to
            say out loud. Do not pad by changing only punctuation. Every one must obey the
            "only what you actually heard" rule; put the one you are most confident about first.
            If you truly heard almost nothing, return a single low-confidence candidate instead
            of inventing two more to reach three.

            ${com.example.ui.I18n.outputLanguageDirective("candidates[].text")}
        """.trimIndent(),
        user = buildString {
            appendLine("Listen to the attached audio.")
            appendLine("Location: ${vault.locationTag.ifBlank { "unspecified" }}")
            appendLine("Objects in view: ${vault.visionTags.joinToString(", ").ifBlank { "none detected" }}")
            if (vault.recentUtterances.isNotEmpty()) {
                appendLine("Earlier utterances in this conversation (disambiguation only, do not repeat them):")
                vault.recentUtterances.forEach { appendLine("- $it") }
            }
            if (vocabularyHints) {
                val personal = AacUsageRanker.topLabels(com.example.data.DefaultAacItems)
                if (personal.isNotEmpty()) {
                    appendLine("Frequent words for this person (lean toward these only when choosing between similar-sounding options): ${personal.joinToString(", ")}")
                }
                val cands = com.example.data.AphasiaPractice.reconstructionCandidates("", vault.visionTags)
                if (cands.isNotEmpty()) {
                    appendLine("Common phrasings (prefer these wordings when what you heard genuinely matches; never force a fit):")
                    cands.take(8).forEach { appendLine("- $it") }
                }
            }
        },
        temperature = RECONSTRUCT_SAMPLE_TEMPERATURE,
        maxTokens = 512,
        audioBase64 = audioB64,
        audioMime = audioMime,
    )

    /**
     * 多數決排名：把 N 次取樣依「正規化後的重組文字」分組去重，依票數（多數決）、
     * 平手再比組內平均信心度排序，最多回傳 3 個候選給「換一個」UI 循環——
     * 第一名＝目前顯示的句子；若三次都各說各話（無共識），退化成單純依信心度排序的
     * best-of-3，也不會比只呼叫一次差。
     */
    private fun rankBySelfConsistency(
        samples: List<Pair<LlmApiEntry, CoTParsed>>
    ): List<Pair<LlmApiEntry, ReconstructedCandidate>> {
        fun normalize(s: String) = s.trim().trimEnd('。', '！', '!', '?', '？', '，', ',').trim()
        // 雲端 LLM 常回傳前後夾帶換行/空白，不清掉會在輸出卡撐出大片空白——壓成單行。
        fun clean(s: String) = s.trim().replace(Regex("\\s*\\n\\s*"), " ")

        // 把每次取樣的「主句 + 同一次回應裡的其他講法」全部攤平：
        // 3 次取樣 × 每次 3 句 = 最多 9 個候選。只靠主句去重會在模型講得一致時
        // 塌成 1 個，使用者就沒得挑。跨取樣重複出現的句子仍會因為 group.size 較大而排前面，
        // 自我一致性的排序意義沒有丟掉。
        val flat: List<Pair<LlmApiEntry, ReconstructedCandidate>> = samples.flatMap { (entry, parsed) ->
            listOf(entry to ReconstructedCandidate(parsed.reconstructed, parsed.confidence)) +
                parsed.alternatives.map { entry to it }
        }.filter { it.second.text.isNotBlank() }

        // 出口把關：文字系統和介面語言對不上的候選句丟掉。
        //
        // 提示詞管不住模型——它是機率性的，而且指示本身還是中文寫的，本來就會往
        // 中文偏。實測英文介面打英文碎詞，回來的三句仍然有兩句是中文。
        // 使用者看到看不懂的語言比少一個候選嚴重得多：那三句是要拿去對別人講出口的。
        // 全部都不符時保留原樣——語言錯的句子他至少看得出來，一句都沒有就完全無法溝通。
        val wanted = flat.filter { com.example.ui.I18n.matchesInterfaceScript(it.second.text) }
        val usable = if (wanted.isNotEmpty()) wanted else flat.also {
            DiagnosticLog.w(
                DiagnosticLog.Cat.LLM, "候選句語言全部不符介面語言",
                "保留原樣：${it.take(3).joinToString(" / ") { c -> c.second.text.take(20) }}"
            )
        }

        val groups = usable.groupBy { normalize(it.second.text) }.values
        return groups.map { group ->
            val rep = group.maxBy { it.second.confidence }
            val avgConfidence = group.sumOf { it.second.confidence } / group.size
            val text = clean(rep.second.text)
            // 使用者以前採納過的說法往前排。
            //
            // 加成刻意壓得很小（上限 0.12），只夠改變「差不多的兩個候選」的先後。
            // 讓歷史蓋過信心度會把使用者困在自己的舊說法裡——他這次想講的新句子
            // 就永遠排不到前面，而那正是這個 App 該幫他做到的事。
            //
            // 顯示的信心度用原始值，不含加成：畫面上那個百分比的意思是
            // 「AI 對這次語音的把握」，混進使用習慣就不再是那個意思了。
            val ranking = avgConfidence + PhraseAdoptionRanker.boostFor(context, text)
            Triple(group.size, ranking, rep.first) to ReconstructedCandidate(text, avgConfidence)
        }
            .sortedWith(compareByDescending<Pair<Triple<Int, Double, LlmApiEntry>, ReconstructedCandidate>> { it.first.first }
                .thenByDescending { it.first.second })
            .map { it.first.third to it.second }
            .take(3)
    }

    override suspend fun composeAacSentence(
        items: List<com.example.data.AacItem>,
        location: String
    ): Result<String> {
        if (items.isEmpty()) return Result.success("")
        if (provider == CloudProvider.LocalOnly) return fallbackEngine.composeAacSentence(items, location)
        if (NetworkQualityGate.shouldPreferLocal()) return fallbackEngine.composeAacSentence(items, location)
        return try {
            val (_, sentence) = tryCloudWithFailover(buildAacPrompt(items, location), timeoutMs = 10_000) { raw ->
                val cleaned = raw.trim().removePrefix("「").removeSuffix("」").trim()
                if (cleaned.isBlank()) error("雲端模型回傳空句子")
                cleaned
            }
            Result.success(sentence)
        } catch (e: Exception) {
            Log.w(TAG, "Cloud AAC compose failed, fallback to local: ${e.message}")
            fallbackEngine.composeAacSentence(items, location)
        }
    }

    private fun buildAacPrompt(items: List<com.example.data.AacItem>, location: String) = CloudPrompt(
        system = """
            You help a person with aphasia say what they mean. They tapped a sequence of
            picture cards. Combine those cards into ONE natural, spoken, polite sentence in
            ${com.example.ui.I18n.outputLanguageNameEn()}.
            Rules:
            - Output one sentence only. No explanation, no quotation marks.
            - If an amount appears (e.g. NT$100), fold it in naturally ("that's 100 dollars in total").
            - If the intent is urgent (help, find family), be clear but not theatrical.
            - Do NOT add any object or action that is not on the cards.

            ${com.example.ui.I18n.outputLanguageDirective("the sentence")}
        """.trimIndent(),
        user = """
            Setting: ${location.ifBlank { "unspecified" }}
            Card sequence: ${items.joinToString(" -> ") { it.label }}
        """.trimIndent(),
        temperature = 0.4,
        maxTokens = 80
    )

    override suspend fun suggestPractice(
        avgScore: Double,
        location: String,
        recent: List<String>
    ): List<String> {
        if (provider == CloudProvider.LocalOnly) return fallbackEngine.suggestPractice(avgScore, location, recent)
        if (NetworkQualityGate.shouldPreferLocal()) return fallbackEngine.suggestPractice(avgScore, location, recent)

        return try {
            val (_, suggestions) = tryCloudWithFailover(buildSuggestPrompt(avgScore, location, recent), timeoutMs = 12_000) { raw ->
                parseSuggestions(raw).ifEmpty { error("雲端模型回傳空練習句清單") }
            }
            suggestions
        } catch (e: Exception) {
            Log.w(TAG, "Cloud suggestion failed, fallback to local: ${e.message}")
            fallbackEngine.suggestPractice(avgScore, location, recent)
        }
    }

    /**
     * 通用文字補完。給 `com.example.aphasia` 那棵新樹用。
     *
     * 開這個缺口而不是讓新樹自己重寫一套：供應商排序、失敗換家、金鑰解析、
     * 診斷日誌這些已經在 [tryCloudWithFailover] 裡跑得好好的，複製一份只會製造
     * 兩邊行為不一致的回歸。新樹要掌握的是 prompt 與輸出契約，不是傳輸層。
     *
     * 這裡不做任何解析——回傳原始文字，怎麼解讀由呼叫端決定。
     */
    suspend fun complete(
        system: String,
        user: String,
        temperature: Double,
        maxTokens: Int,
        timeoutMs: Long,
    ): String = tryCloudWithFailover(
        CloudPrompt(system = system, user = user, temperature = temperature, maxTokens = maxTokens),
        timeoutMs = timeoutMs,
    ) { it }.second

    /**
     * @param candidates 明確指定要試哪些供應商。**音訊請求一定要傳**——
     *   不傳會退回全部（文字）候選，把音訊送給不支援音訊的供應商，
     *   實測時就是這樣被 OpenRouter/Groq 各回 404/400。
     */
    private suspend fun <T> tryCloudWithFailover(
        prompt: CloudPrompt,
        timeoutMs: Long,
        candidates: List<LlmApiEntry>? = null,
        parseAndValidate: (String) -> T
    ): Pair<LlmApiEntry, T> {
        val pool = candidates ?: cloudCandidates()
        if (pool.isEmpty()) error("沒有可用的 LLM 供應商（此請求所需能力）")

        var lastError: Throwable? = null
        for (candidate in pool) {
            val start = System.currentTimeMillis()
            try {
                val raw = withTimeout(timeoutMs) { callCloud(candidate, prompt) }
                val parsed = parseAndValidate(raw)
                val ms = System.currentTimeMillis() - start
                SmartApiRouter.recordSuccess(candidate.id, ms)
                DiagnosticLog.i(
                    DiagnosticLog.Cat.LLM, "供應商回應成功",
                    "供應商=${candidate.label} 模型=${candidate.effectiveModel} 耗時=${ms}ms"
                )
                return candidate to parsed
            } catch (e: Throwable) {
                SmartApiRouter.recordFailure(candidate.id)
                lastError = e
                Log.w(TAG, "LLM ${candidate.label} failed, trying next: ${e.message}")
                DiagnosticLog.w(
                    DiagnosticLog.Cat.LLM, "供應商失敗，換下一家",
                    "供應商=${candidate.label} 模型=${candidate.effectiveModel} " +
                        "耗時=${System.currentTimeMillis() - start}ms 原因=${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
        DiagnosticLog.e(
            DiagnosticLog.Cat.LLM, "所有供應商皆失敗",
            "嘗試過=${pool.joinToString("/") { it.label }}", lastError
        )
        throw lastError ?: IllegalStateException("所有文字 LLM 供應商皆不可用")
    }

    /**
     * 候選清單：使用者在「API 金鑰管理 → 文字 LLM」設定的多供應商清單，
     * 使用中那筆排第一、其餘自動備援；清單為空則退回舊版 Groq/OpenRouter/Cohere 設定（向後相容）。
     */
    /**
     * 這次請求的供應商順序，由 [SmartApiRouter] 依**實測表現**自動決定
     * （成功率 → 延遲 → 免費優先 → 冷卻懲罰），不再靠使用者手動勾「使用中」。
     *
     * ApiKeyOverrides.llmSelectedId 現在的語意是「釘選」：使用者主動釘某一家時
     * 強制排第一（人工覆寫），沒釘就完全自動。
     */
    private fun cloudCandidates(need: SmartApiRouter.Need = SmartApiRouter.Need.TEXT): List<LlmApiEntry> {
        val all = ApiKeyOverrides.llmApis.ifEmpty { legacyEntries() }
        if (all.isEmpty()) return emptyList()
        val pinned = ApiKeyOverrides.llmSelectedId.takeIf { it.isNotBlank() }
        return SmartApiRouter.rank(all, need, pinned)
    }

    /** 舊版相容：把 BuildConfig/.env 與舊端點欄位組成三筆候選。 */
    private fun legacyEntries(): List<LlmApiEntry> = listOf(
        LlmApiEntry("legacy-groq", LlmProvider.GROQ, "",
            ApiKeyOverrides.or(ApiKeyOverrides.groqEndpoint, LlmDefaults.GROQ_ENDPOINT),
            ApiKeyOverrides.or(ApiKeyOverrides.groqModel, LlmDefaults.GROQ_MODEL),
            ApiKeyOverrides.resolve(ApiKeyOverrides.groq, BuildConfig.GROQ_API_KEY)),
        LlmApiEntry("legacy-openrouter", LlmProvider.OPENROUTER, "",
            ApiKeyOverrides.or(ApiKeyOverrides.openRouterEndpoint, LlmDefaults.OPENROUTER_ENDPOINT),
            ApiKeyOverrides.or(ApiKeyOverrides.openRouterModel, LlmDefaults.OPENROUTER_MODEL),
            ApiKeyOverrides.resolve(ApiKeyOverrides.openRouter, BuildConfig.OPENROUTER_API_KEY)),
        LlmApiEntry("legacy-cohere", LlmProvider.COHERE, "",
            ApiKeyOverrides.or(ApiKeyOverrides.cohereEndpoint, LlmDefaults.COHERE_ENDPOINT),
            ApiKeyOverrides.or(ApiKeyOverrides.cohereModel, LlmDefaults.COHERE_MODEL),
            ApiKeyOverrides.resolve(ApiKeyOverrides.cohere, BuildConfig.COHERE_API_KEY)),
    )

    /**
     * 送出請求。格式全部交給 [LlmAdapter] 的各家實作處理——
     * 這裡只管「要什麼」，不管「怎麼組 JSON」，新增廠商不必改這支。
     */
    private suspend fun callCloud(entry: LlmApiEntry, prompt: CloudPrompt): String =
        withContext(Dispatchers.IO) {
            val adapter = LlmAdapter.of(entry)
            // 走音訊時，若該供應商需要換成專用的音訊模型（如 gpt-audio / voxtral），這裡替換
            val effective = if (prompt.audioBase64 != null && entry.provider.audioModel.isNotBlank()) {
                entry.copy(model = entry.provider.audioModel)
            } else entry
            val spec = LlmRequestSpec(
                system = prompt.system,
                user = prompt.user,
                temperature = prompt.temperature,
                maxTokens = prompt.maxTokens,
                audioBase64 = prompt.audioBase64,
                audioFormat = prompt.audioMime.substringAfterLast('/').let {
                    if (it == "mpeg") "mp3" else it
                }
            )
            client.newCall(adapter.buildRequest(effective, spec)).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    // HTTP 狀態碼與回應內容是分辨「金鑰錯 401／額度用完 429／模型名打錯 404」
                    // 的唯一線索，一定要留下來；DiagnosticLog 會自動遮蔽金鑰。
                    DiagnosticLog.e(
                        DiagnosticLog.Cat.LLM, "HTTP ${res.code}",
                        "供應商=${effective.label} 模型=${effective.effectiveModel} " +
                            "端點=${effective.effectiveEndpoint} 音訊=${prompt.audioBase64 != null} " +
                            // 600 字才容得下 Google 429 裡真正有用的那段（是哪一項配額爆掉、
                            // 每分鐘還是每天）。截在 300 字剛好把 "Quota exceeded for..." 切斷。
                            "回應=${text.replace(Regex("\\s+"), " ").take(600)}"
                    )
                    error("HTTP ${res.code}: ${text.take(160)}")
                }
                adapter.parseResponse(text)
            }
        }


    /**
     * 語音復健 AI 評分：語意完整性 + 流暢性 + 語氣，不用字元差異。
     * 電腦接手(本機/USB)優先 → 雲端 LLM → 全失敗退回 Levenshtein（PronunciationEvaluator）。
     */
    override suspend fun scoreRehab(
        target: String,
        recognized: String,
        words: List<WordConf>
    ): RehabEvaluation {
        val prompt = buildRehabEvalPrompt(target, recognized, words)
        // 1) 電腦接手＝最省手機、離線可用（區網/USB）
        if (OffloadManager.shouldUseComputerText()) {
            val txt = OffloadManager.computerReconstruct(prompt.system, prompt.user, temperature = prompt.temperature)?.trim()
            val parsed = txt?.let { runCatching { parseRehabScore(it) }.getOrNull() }
            if (parsed != null) return parsed
        }
        // 2) 雲端 LLM（LocalOnly 時跳過，交給裝置端引擎或 fallback）
        if (provider != CloudProvider.LocalOnly && !NetworkQualityGate.shouldPreferLocal()) {
            try {
                val (_, result) = tryCloudWithFailover(prompt, timeoutMs = 8_000) { raw -> parseRehabScore(raw) }
                return result
            } catch (e: Exception) {
                Log.w(TAG, "Cloud rehab eval failed: ${e.message}")
            }
        }
        // 3) 本地備援：先試裝置端引擎(Gemma/Qwen) 的 AI 評分，再退 Levenshtein
        return fallbackEngine.scoreRehab(target, recognized, words)
    }

    private fun buildRehabEvalPrompt(
        target: String,
        recognized: String,
        words: List<WordConf>
    ): CloudPrompt {
        val confInfo = if (words.isNotEmpty())
            "\nPer-word recognition confidence: ${words.joinToString(" ") { "${it.word}(${(it.conf * 100).toInt()}%)" }}"
        else ""
        return CloudPrompt(
            system = """
                You are a speech-language therapist evaluating how a person with aphasia
                repeated a target sentence.
                Judge by meaning conveyed and by fluency — NOT by character-level difference.
                This is a completely standalone evaluation: score only this attempt against this
                target. There is no prior practice, score, or conversation. Do not refer to,
                compare with, or continue any previous evaluation.
            """.trimIndent(),
            user = """
                Target sentence: $target
                What they said: ${recognized.ifBlank { "(no speech detected)" }}$confInfo

                Scoring (out of 100; the weighted total IS the final score):
                - Meaning conveyed (50%): did the core meaning get across? were the key words said?
                  Different wording with the same meaning still scores high.
                - Fluency (30%): repetition, stumbling? is the delivery connected and natural?
                  Low-confidence words were probably unclear.
                - Prosody (20%): does the intonation fit the sentence type (question / request / thanks)?

                Score scale — use 0-100, NOT 1-5 and NOT 0-10:
                90-100 = essentially correct; 70-89 = meaning landed with minor flaws;
                40-69 = some key words produced; 1-39 = barely resembles the target;
                0 = nothing was said.

                Return JSON only:
                {"score":0-100 integer, "feedback":"one short encouraging or corrective line in ${com.example.ui.I18n.outputLanguageNameEn()}, under 20 words", "wrongChars":["words or characters that were inaccurate or missing"]}

                ${com.example.ui.I18n.outputLanguageDirective("feedback")}
            """.trimIndent(),
            temperature = 0.15,   // 評分求穩定：低溫讓同樣表現得到一致分數
            maxTokens = 150
        )
    }

    private fun parseRehabScore(raw: String): RehabEvaluation {
        val jsonText = raw.extractJsonObject() ?: error("無法解析 AI 回傳 JSON")
        val o = JSONObject(jsonText)
        val score = o.optInt("score", -1)
        if (score !in 0..100) error("分數無效: $score")
        val feedback = o.optString("feedback", "").trim()
        val wrong = mutableListOf<String>()
        o.optJSONArray("wrongChars")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).trim().takeIf { it.isNotBlank() }?.let(wrong::add)
        }
        return RehabEvaluation(score, feedback, wrong)
    }

    private fun buildReconstructPrompt(vault: GlobalTagsVault): CloudPrompt {
        val words = vault.sttWords.joinToString(" ") { "${it.word}(${(it.conf * 100).toInt()}%)" }
        val outLang = com.example.ui.I18n.outputLanguageNameEn()
        return CloudPrompt(
            system = """
                You help a person with aphasia say what they mean.

                You are given fragments from speech recognition, per-word confidence,
                the GPS location, and objects the camera can see. Rebuild the sentence
                the person most likely wanted to say.

                OUTPUT LANGUAGE: every sentence you produce MUST be written in $outLang,
                regardless of what language the fragments are in. The person is looking at
                a $outLang interface, so what gets spoken aloud has to be $outLang.
                These instructions are in English; that is not an instruction to answer in English.

                THE MOST IMPORTANT RULE — stay on the fragments, invent nothing.
                Every content word (noun, verb, adjective) in your sentence must trace back to a
                fragment, or to one of the given location/object tags. You may add only the
                grammatical glue a sentence needs — pronouns, auxiliaries, particles.
                You may NOT introduce a new event, a new time, a new person, or a new question
                that the fragments do not contain.
                Example: fragments "family ... hurry" mean "the family should hurry", so
                "Please ask my family to hurry" is right. "I want to know when my family will
                arrive" is wrong — "when will they arrive" is a brand-new question.
                Low-confidence words may be corrected to a similar-sounding word that fits the
                context, but that is not licence to add detail.

                If SPEECH RECOGNITION ALTERNATIVES are given: those are other ways the SAME audio
                could have been heard, not several things the person said. The top result is often
                wrong — slurred speech is misheard constantly. Compare the alternatives against the
                location and object tags and pick the most plausible one; you may mix in a clearly
                better word from another alternative. You still may not add anything that appears
                in none of them.

                Questions and question words in the input are hard constraints — keep their meaning.
                "when", "where", "may I", "how long" must not be dropped. If the fragments contain
                both a need and a question, express both in one natural sentence.

                EXAMPLES (these show exactly how far you may go — match this level of restraint):
                  "medicine ... head ... hurt" + location=pharmacy
                    -> {"reconstructed":"My head hurts, I need to buy medicine."}  (glue only)
                  "for ... water" + objects=cup
                    -> {"reconstructed":"Four glasses of water."}  (low-confidence homophone fixed: for -> four)
                  "wait ... bus ... long"
                    -> {"reconstructed":"Will the bus take long?"}  (meaning kept, no destination invented)
                  "water" + previous turn "I just took my medicine"
                    -> {"reconstructed":"I want to drink water."}  (context disambiguates; not watering plants)

                If EARLIER UTTERANCES are given: that is the same conversation. Pronouns, dropped
                subjects, and continuations like "another one" / "also" can only be resolved from it —
                e.g. after "I'd like a coffee", the fragments "another ... one" become
                "I'd like another coffee." Use the earlier turns only to disambiguate; do not repeat
                their content.

                If FREQUENT WORDS are given: those are what this person usually says. When a fragment
                is ambiguous or has several similar-sounding readings, lean toward these words.
                When the fragment is clear, ignore the list.

                IMPORTANT: when the fragments are very short — even a single word like "ten", "water",
                "hurt" — still give the most likely everyday reading. Do not refuse and do not return
                an empty sentence. Add only the closest possible glue, do not invent a scenario:
                "ten" -> "I want ten.", "water" -> "I want water.", "hurt" -> "It hurts here."

                CONFIDENCE is how sure YOU are about the rebuild. Use the whole range, do not be
                uniformly cautious:
                  80-100  the fragments are clear and the rebuild is nearly certain
                  50-79   short or ambiguous fragments, but you gave a reasonable common reading
                          (single-word fragments usually land here)
                  1-49    the fragments are genuinely meaningless

                Output JSON only:
                {"reasoning":"brief","candidates":[{"text":"most confident wording","confidence":0-100},{"text":"another wording","confidence":0-100},{"text":"a third wording","confidence":0-100}]}

                Always give all 3 candidates, and make them GENUINELY different (different wording,
                tone, or level of detail). Do not pad the list by changing only punctuation or a
                particle — the person picks one of these three to say out loud, and three identical
                sentences leave them no choice. Every one of them must obey the "stay on the
                fragments" rule above. Put the one you are most confident about first.

                Keep "reasoning" under 20 characters. It is your own scratch note, it is never shown
                to the person, and writing more will blow the output budget and truncate the
                candidates that actually matter.

                ${com.example.ui.I18n.outputLanguageDirective("candidates[].text")}
            """.trimIndent(),
            user = buildString {
                appendLine("Fragments: ${vault.rawText}")
                if (words.isNotBlank()) appendLine("Per-word confidence: $words")
                if (vault.sttAlternatives.isNotEmpty()) {
                    appendLine("Speech recognition alternatives (other readings of the same audio, pick the most plausible):")
                    vault.sttAlternatives.forEach { appendLine("- $it") }
                }
                appendLine("Location: ${vault.locationTag.ifBlank { "unspecified" }}")
                appendLine("Objects in view: ${vault.visionTags.joinToString(", ").ifBlank { "none detected" }}")
                if (vault.recentUtterances.isNotEmpty()) {
                    appendLine("Earlier utterances in this conversation (for disambiguation only):")
                    vault.recentUtterances.forEach { appendLine("- $it") }
                }
                val personal = AacUsageRanker.topLabels(com.example.data.DefaultAacItems)
                if (personal.isNotEmpty()) {
                    appendLine("Frequent words for this person: ${personal.joinToString(", ")}")
                }
                val cands = com.example.data.AphasiaPractice.reconstructionCandidates(vault.rawText, vault.visionTags)
                if (cands.isNotEmpty()) {
                    appendLine("Common phrasings (prefer these wordings when the fragments genuinely match one; never force a fit, never change the meaning of a question):")
                    cands.forEach { appendLine("- $it") }
                }
            },
            temperature = 0.1,
            // 512 會讓話多的模型在 reasoning 就燒完額度、reconstructed 生不出來（截斷→整包 JSON 外漏）。
            maxTokens = 800
        )
    }

    /**
     * 練習句要用哪種語言。
     *
     * 這裡以前有一道閘：只有雲端 Whisper（多語）可用時才敢出非中文，
     * 因為當時裝置端 Vosk 只載中文聲學模型，出了英文句子使用者唸完會固定拿 0 分——
     * 比不提供更糟。
     *
     * 那道閘現在是多餘的：[VoskModelSpec.forLanguage] 會依介面語言載對應的模型
     * （en-us / ja / ko / cn 都有），離線也評得了分。留著它只會讓英文使用者在離線時
     * 拿到中文練習句，而那正是「英文特化」要修掉的東西。
     *
     * 模型還沒下載時 STT 會走它自己的下載流程，那是正常路徑，不是靜靜地給 0 分。
     */
    private fun practiceLanguageName(): String = com.example.ui.I18n.outputLanguageNameEn()

    private fun buildSuggestPrompt(avgScore: Double, location: String, recent: List<String>) = CloudPrompt(
        system = """
            You are a speech rehabilitation assistant for people with aphasia. Based on the
            person's recent average score, their setting, and what they have already practised,
            produce 3 practice sentences in ${practiceLanguageName()} for them to read aloud.

            Difficulty:
              score < 60   short words, 1-4 characters (or 1-2 words)
              score 60-79  short sentences, 5-8 characters (or 3-5 words)
              score >= 80  full situational sentences, 9-15 characters (or 6-10 words)

            Return JSON only: {"sentences":["...","...","..."],"level":"easy|medium|hard"}
        """.trimIndent(),
        user = """
            Recent average score: ${avgScore.toInt()}
            Current setting: ${location.ifBlank { "unspecified" }}
            Already practised: ${recent.take(5)}
        """.trimIndent(),
        temperature = 0.6,
        maxTokens = 256
    )

    /**
     * 危機意圖分流：這句話裡「想自傷的是誰」。
     *
     * 刻意「不」掛 DEFENSIVE_SYSTEM_PROMPT——那條 prompt 要模型迴避這類字眼，
     * 掛上去反而讓它拒答分類任務。這裡是分類、不是生成，明講用途讓模型願意回答。
     */
    override suspend fun classifyCrisisIntent(text: String): com.example.safety.SafetyGuard.CrisisIntent {
        if (text.isBlank()) return com.example.safety.SafetyGuard.CrisisIntent.NONE
        // 「只用本機」的人，連自殺意念這種最敏感的句子都不該離開裝置。
        //
        // 這道檢查以前不需要：LocalOnly 時 wrapIfNeeded 根本不會建立 HybridInferenceEngine，
        // 這支永遠不會被呼叫。現在為了讓電腦端音訊模型可用而改成一律包裝，
        // 這裡就必須自己擋——否則「只用本機」會變成一句空話。
        if (provider == CloudProvider.LocalOnly) {
            DiagnosticLog.i(DiagnosticLog.Cat.LLM, "只用本機：危機意圖分類不送雲端")
            return fallbackEngine.classifyCrisisIntent(text)
        }
        val prompt = CloudPrompt(
            system = """
                You are a safety classifier inside an assistive communication device for people
                with aphasia. Decide WHO in this sentence is at risk of self-harm.

                Output JSON only. No explanation, no caveats, do not decline: {"who":"self"}
                  self  = the speaker themselves wants to self-harm or end their life.
                  other = the speaker is describing someone else or calling for help on their
                          behalf, e.g. "my friend wants to kill himself, call an ambulance".
                  none  = the topic is merely mentioned and nobody is currently at risk, e.g.
                          asking for a helpline number, relaying a news story.

                This routes a real cry for help. Classifying as "self" locks the person's voice
                output; classifying wrongly means someone calling for another person cannot reach
                an ambulance. You must return a classification.
            """.trimIndent(),
            user = text,
            temperature = 0.0,
            maxTokens = 32
        )
        val raw = runCatching {
            tryCloudWithFailover(prompt, timeoutMs = 8_000) { it }.second
        }.getOrNull() ?: return com.example.safety.SafetyGuard.CrisisIntent.UNKNOWN
        val who = Regex("\"who\"\\s*:\\s*\"(self|other|none)\"", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.get(1)?.lowercase()
        return when (who) {
            "self"  -> com.example.safety.SafetyGuard.CrisisIntent.SELF
            "other" -> com.example.safety.SafetyGuard.CrisisIntent.OTHER
            "none"  -> com.example.safety.SafetyGuard.CrisisIntent.NONE
            else    -> com.example.safety.SafetyGuard.CrisisIntent.UNKNOWN
        }
    }

    private fun parseCoT(raw: String): CoTParsed {
        val jsonText = raw.extractJsonObject()
        if (jsonText != null) {
            // JSONObject() 會對壞掉的字串丟例外，這裡不能讓它炸掉整條重組流程。
            val parsed = runCatching {
                val o = JSONObject(jsonText)
                // 新版格式：candidates 陣列（一次給 3 種講法）
                val arr = o.optJSONArray("candidates")
                if (arr != null && arr.length() > 0) {
                    val list = buildList {
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val txt = item.optString("text").trim()
                            if (txt.isNotBlank()) {
                                add(ReconstructedCandidate(txt, item.optInt("confidence", 70).coerceIn(0, 100) / 100.0))
                            }
                        }
                    }
                    if (list.isNotEmpty()) {
                        return@runCatching CoTParsed(list[0].text, list[0].confidence, list.drop(1))
                    }
                }
                // 舊格式：單一 reconstructed（本機引擎與舊回應仍走這條）
                CoTParsed(
                    reconstructed = o.optString("reconstructed").trim(),
                    confidence = o.optInt("confidence", 70).coerceIn(0, 100) / 100.0
                )
            }.getOrNull()
            if (parsed != null && parsed.reconstructed.isNotBlank()) return parsed
        }
        // 走到這裡＝JSON 壞掉，或被 maxTokens 截斷（reasoning 寫太長就會這樣）。
        // 先從殘缺文字裡撈 reconstructed；撈不到寧可回空字串——
        // 上層看到空句會自動換下一家供應商，而把 raw 當句子會讓病人畫面出現
        // {"reasoning":… 這種東西，還被當成可以唸出來的話。
        salvageReconstructed(raw)?.let { return CoTParsed(it, 0.6) }
        return CoTParsed("", 0.0)
    }

    private fun parseSuggestions(raw: String): List<String> {
        val jsonText = raw.extractJsonObject() ?: return emptyList()
        return runCatching {
            val arr = JSONObject(jsonText).optJSONArray("sentences") ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optString(i).trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun String.extractJsonObject(): String? {
        val cleaned = replace("```json", "").replace("```", "").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        return if (start in 0 until end) cleaned.substring(start, end + 1) else null
    }

    private data class CloudPrompt(
        val system: String,
        val user: String,
        val temperature: Double,
        val maxTokens: Int,
        /** 直接餵音訊給模型時的 base64 內容（目前只有 Gemini 風格支援）。null＝純文字。 */
        val audioBase64: String? = null,
        val audioMime: String = "audio/aac"
    )

    /**
     * @param alternatives 同一次回應裡的其他候選說法。新版 prompt 要模型一次給 3 種講法——
     *   光靠「同一份碎詞取樣 3 次」湊不出 3 個候選：溫度 0.3 又是短碎詞時，
     *   三次常常吐一模一樣的句子，去重完只剩 1 個，使用者就沒得挑。
     */
    private data class CoTParsed(
        val reconstructed: String,
        val confidence: Double,
        val alternatives: List<ReconstructedCandidate> = emptyList()
    )

    companion object {
        private const val TAG = "HybridInferenceEngine"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        // 語句重組自我一致性：平行取樣次數；溫度略高於單次呼叫的 0.1，才有足夠多樣性可供多數決，
        // 但不宜太高——溫度越高，模型越容易編出碎詞裡沒有的新內容（腦補），0.3 是折衷值。
        private const val RECONSTRUCT_SAMPLES = 3
        /**
         * 音訊理解「要不要補打額外取樣」的門檻。
         * 第一次就達到這個把握度就直接用，省下 2/3 的雲端額度；低於才交叉驗證。
         */
        private const val ESCALATE_CONFIDENCE = 0.80
        /** 音訊上傳＋理解比純文字慢，逾時放寬。 */
        private const val AUDIO_TIMEOUT_MS = 45_000L
        /** Gemini inline 音訊上限 20MB，保守留 18MB。 */
        private const val MAX_INLINE_AUDIO_BYTES = 18L * 1024 * 1024
        private const val RECONSTRUCT_SAMPLE_TEMPERATURE = 0.3

        fun wrapIfNeeded(
            context: Context,
            provider: CloudProvider,
            fallbackEngine: InferenceEngine
        ): InferenceEngine =
            // 一律包起來，連「只用本機」也是。
            //
            // 以前 LocalOnly 會直接回 fallbackEngine，於是「AI 直接聽」整條路連進都進不去。
            // 但電腦端的音訊模型跑在使用者自己的電腦上、不需要金鑰、語音不出區網——
            // 那正是「只用本機」的人最該拿到的東西，沒有理由擋掉。
            // Hybrid 內部每個雲端方法都已經自己判斷 LocalOnly 並委派回 fallbackEngine，
            // 所以包起來不會讓文字重組偷跑雲端。
            HybridInferenceEngine(context, provider, fallbackEngine)
    }
}


