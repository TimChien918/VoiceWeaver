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
class HybridInferenceEngine(
    private val context: Context,
    private val provider: CloudProvider,
    val fallbackEngine: InferenceEngine
) : InferenceEngine {

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
                        engineUsed = "電腦接手（本機/雲端備援待命）",
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
            val consensusNote = if (samples.size > 1) "自我一致性 ${samples.size} 取樣" else "單次取樣（其餘取樣失敗）"
            Result.success(
                ReconstructedData(
                    originalText = vault.rawText,
                    reconstructedText = top.text,
                    confidence = top.confidence,
                    engineUsed = "${selected.label}（$consensusNote／本地備援待命）",
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
    private fun nativeAudioCandidates(): List<LlmApiEntry> = cloudCandidates().filter {
        it.provider.audioStyle == com.example.data.LlmAudioStyle.GEMINI_INLINE ||
            it.provider.audioStyle == com.example.data.LlmAudioStyle.OPENAI_INPUT_AUDIO
    }

    /** 只有 Whisper 轉寫能力的供應商（中間路線：雲端 STT，比裝置端 Vosk 準）。 */
    private fun transcribeCandidates(): List<LlmApiEntry> = cloudCandidates().filter {
        it.provider.audioStyle == com.example.data.LlmAudioStyle.WHISPER_TRANSCRIBE &&
            it.provider.transcribeEndpoint.isNotBlank()
    }

    override fun supportsAudioUnderstanding(): Boolean =
        provider != CloudProvider.LocalOnly &&
            (nativeAudioCandidates().isNotEmpty() || transcribeCandidates().isNotEmpty())

    override suspend fun reconstructFromAudio(
        audioFile: java.io.File,
        vault: GlobalTagsVault
    ): Result<ReconstructedData> {
        if (provider == CloudProvider.LocalOnly) {
            return Result.failure(IllegalStateException("已設為只用本機，音訊理解需要雲端模型"))
        }
        val native = nativeAudioCandidates()
        val transcribe = transcribeCandidates()
        if (native.isEmpty() && transcribe.isEmpty()) {
            return Result.failure(IllegalStateException("沒有具備音訊能力的供應商（Gemini／OpenAI gpt-audio／Mistral Voxtral／Groq Whisper 任一）"))
        }
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return Result.failure(IllegalStateException("錄音檔是空的"))
        }
        // Gemini inline 上限 20MB；16kHz/32kbps 大約 4KB/秒，正常一句話遠低於上限。
        if (audioFile.length() > MAX_INLINE_AUDIO_BYTES) {
            return Result.failure(IllegalStateException("錄音太長（${audioFile.length() / 1024} KB），請講短一點"))
        }

        val start = System.currentTimeMillis()
        return try {
            val b64 = withContext(Dispatchers.IO) {
                android.util.Base64.encodeToString(audioFile.readBytes(), android.util.Base64.NO_WRAP)
            }
            // 沒有原生音訊供應商時不要白跑取樣，直接走 Whisper 轉寫
            if (native.isEmpty() && transcribe.isNotEmpty()) {
                val heard = transcribeWithWhisper(audioFile, transcribe, vault)
                    ?: return Result.failure(IllegalStateException("雲端語音轉寫失敗"))
                Log.i(TAG, "雲端 Whisper 轉寫結果：$heard")
                return reconstructSentence(vault.copy(rawText = heard)).map { data ->
                    data.copy(engineUsed = "${data.engineUsed}｜雲端 Whisper 轉寫")
                }
            }

            val prompt = buildAudioReconstructPrompt(vault, b64)

            // 跟文字路徑一樣做自我一致性：平行取樣多次、多數決，抗單次誤聽。
            val samples = coroutineScope {
                (1..RECONSTRUCT_SAMPLES).map {
                    async {
                        runCatching {
                            tryCloudWithFailover(prompt, timeoutMs = AUDIO_TIMEOUT_MS) { raw ->
                                val parsed = parseCoT(raw)
                                if (parsed.reconstructed.isBlank()) error("模型回傳空句子")
                                parsed
                            }
                        }.getOrNull()
                    }
                }.awaitAll()
            }.filterNotNull()

            if (samples.isEmpty()) {
                // 原生音訊全失敗（或本來就沒有原生音訊供應商）→ 走 Whisper 轉寫中間路線：
                // 雲端 Whisper 通常比裝置端 Vosk 準；轉出文字後再交給文字重組流程。
                if (transcribe.isNotEmpty()) {
                    val heard = transcribeWithWhisper(audioFile, transcribe, vault)
                    if (!heard.isNullOrBlank()) {
                        Log.i(TAG, "原生音訊不可用，改用雲端 Whisper 轉寫：$heard")
                        val textVault = vault.copy(rawText = heard)
                        return reconstructSentence(textVault).map { data ->
                            data.copy(engineUsed = "${data.engineUsed}｜雲端 Whisper 轉寫")
                        }
                    }
                }
                error("音訊理解多次取樣皆失敗")
            }

            val ranked = rankBySelfConsistency(samples)
            val (selected, top) = ranked.first()
            Result.success(
                ReconstructedData(
                    // 音訊路徑沒有「碎詞原文」——本來就沒經過 STT。
                    // 留空讓 UI 不要顯示「唸原始輸入」那顆按鈕（沒有原始文字可唸）。
                    originalText = "",
                    reconstructedText = top.text,
                    confidence = top.confidence,
                    engineUsed = "${selected.label}·直接聽音訊（${samples.size} 取樣，未經語音轉文字）",
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
        vault: GlobalTagsVault
    ): String? = withContext(Dispatchers.IO) {
        val bytes = runCatching { audioFile.readBytes() }.getOrNull() ?: return@withContext null
        val hint = buildString {
            append("失語症患者日常溝通。")
            if (vault.locationTag.isNotBlank()) append("地點：${vault.locationTag}。")
            if (vault.visionTags.isNotEmpty()) append("周邊：${vault.visionTags.joinToString("、")}。")
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
                    languageHint = "zh",
                    promptHint = hint
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
            if (!text.isNullOrBlank()) return@withContext text
        }
        null
    }

    /**
     * 音訊理解的提示詞。與文字版共用「不要腦補」的核心約束，但改成
     * 針對「聽」的任務：允許模型依發音相近去推測，因為失語症患者
     * 常出現語音錯誤（想說「水」卻發成「揮」）——這正是走音訊的價值。
     */
    private fun buildAudioReconstructPrompt(vault: GlobalTagsVault, audioB64: String) = CloudPrompt(
        system = """
            你是輔助失語症患者溝通的語言助理。附上的音訊是患者本人說的話。
            請直接聽這段音訊，判斷他最可能想表達什麼，並重組成一句自然的繁體中文。

            這位患者有失語症／構音障礙，所以：
            - 發音可能不標準、字詞可能拉長或含糊、句子可能不完整、可能重複同一個字。
            - 可能出現語音錯誤：想講的字被講成發音相近的另一個字（例如想說「水」講成「揮」）。
              請以「發音相近且在當下情境合理」為準去推測他真正想說的詞。
            - 語調與停頓有意義：語尾上揚多半是問句，急促或音量大多半是急迫需求。

            【但仍然不可以腦補】
            句子裡每個實詞都必須來自你真的在音訊裡聽到的內容，或明確對應到下方的地點／物件情境。
            你只能補讓句子成立所需的語法零件（我、請、想、了、嗎）。
            不可以加入你沒聽到、情境也沒有的新事件、新時間、新對象或新問句。
            如果音訊裡有明確的疑問詞，必須保留其語意。

            若整段音訊聽不出任何有意義的內容（只有雜音、咳嗽、沉默），
            confidence 給 1-20 並在 reconstructed 給你最接近的猜測，不要硬掰一句完整的話。

            confidence 是你對這句重組的把握（0-100）：
              80-100：聽得很清楚、幾乎確定
              50-79 ：聽得出大概、給了合理推測（含糊發音多半落在這裡）
              1-49  ：幾乎聽不出來
            只輸出 JSON：{"heard":"你實際聽到的音節或字（可含不確定處）","reconstructed":"完整繁體中文句子","confidence":0到100整數}
        """.trimIndent(),
        user = buildString {
            appendLine("請聽附上的音訊。")
            appendLine("地點：${vault.locationTag.ifBlank { "未指定" }}")
            appendLine("周邊物件：${vault.visionTags.joinToString("、").ifBlank { "未偵測" }}")
            val cands = com.example.data.AphasiaPractice.reconstructionCandidates("", vault.visionTags)
            if (cands.isNotEmpty()) {
                appendLine("常用表達參考（聽起來接近其中之一時可優先採用其自然說法，但不可硬套）：")
                cands.take(8).forEach { appendLine("- $it") }
            }
        },
        temperature = RECONSTRUCT_SAMPLE_TEMPERATURE,
        maxTokens = 512,
        audioBase64 = audioB64,
        audioMime = "audio/aac"
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

        val groups = samples.groupBy { normalize(it.second.reconstructed) }.values
        return groups.map { group ->
            val rep = group.maxBy { it.second.confidence }
            val avgConfidence = group.sumOf { it.second.confidence } / group.size
            Triple(group.size, avgConfidence, rep.first) to ReconstructedCandidate(clean(rep.second.reconstructed), avgConfidence)
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
            你是失語症患者的溝通助理。使用者透過圖卡點選了一串元素，請組合成一句**自然、口語、有禮貌**的繁體中文。
            規則：
            - 只輸出一句話、不要解釋、不要加引號。
            - 若有金額（如 NT$100），請依場景自然嵌入（例如「總共 100 元」）。
            - 若有強烈意圖（救命、找家人），語氣要清楚但不誇張。
            - 不要憑空加入沒在圖卡中的物品或行為。
        """.trimIndent(),
        user = """
            場景：${location.ifBlank { "未指定" }}
            圖卡序列：${items.joinToString(" → ") { it.label }}
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

    private suspend fun <T> tryCloudWithFailover(
        prompt: CloudPrompt,
        timeoutMs: Long,
        parseAndValidate: (String) -> T
    ): Pair<LlmApiEntry, T> {
        val candidates = cloudCandidates()
        if (candidates.isEmpty()) error("沒有已設定金鑰的文字 LLM 供應商")

        var lastError: Throwable? = null
        for (candidate in candidates) {
            val start = System.currentTimeMillis()
            try {
                val raw = withTimeout(timeoutMs) { callCloud(candidate, prompt) }
                val parsed = parseAndValidate(raw)
                CloudHealth.recordSuccess(candidate.id, System.currentTimeMillis() - start)
                return candidate to parsed
            } catch (e: Throwable) {
                CloudHealth.recordFailure(candidate.id)
                lastError = e
                Log.w(TAG, "LLM ${candidate.label} failed, trying next: ${e.message}")
            }
        }
        throw lastError ?: IllegalStateException("所有文字 LLM 供應商皆不可用")
    }

    /**
     * 候選清單：使用者在「API 金鑰管理 → 文字 LLM」設定的多供應商清單，
     * 使用中那筆排第一、其餘自動備援；清單為空則退回舊版 Groq/OpenRouter/Cohere 設定（向後相容）。
     */
    private fun cloudCandidates(): List<LlmApiEntry> {
        val all = ApiKeyOverrides.llmApis.ifEmpty { legacyEntries() }.filter { it.usable }
        if (all.isEmpty()) return emptyList()
        // 自動輪詢：使用中那筆所屬供應商的多把金鑰每次請求輪流換一把；再跨供應商備援。
        val selId = ApiKeyOverrides.llmSelectedId
        val selProvider = (all.firstOrNull { it.id == selId } ?: all.first()).provider
        val sel = all.filter { it.provider == selProvider }
        val other = all.filter { it.provider != selProvider }
        val off = CloudHealth.nextOffset(Int.MAX_VALUE)
        fun rot(l: List<LlmApiEntry>) = if (l.size <= 1) l else { val o = Math.floorMod(off, l.size); l.drop(o) + l.take(o) }
        val ordered = rot(sel) + rot(other)
        val healthy = ordered.filter { CloudHealth.isAvailable(it.id) }
        return healthy.ifEmpty { ordered }
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
                if (!res.isSuccessful) error("HTTP ${res.code}: ${text.take(160)}")
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
            "\n逐字辨識信心度：${words.joinToString(" ") { "${it.word}(${(it.conf * 100).toInt()}%)" }}"
        else ""
        return CloudPrompt(
            system = """
                你是失語症語言治療師，評估患者的口語跟讀表現。
                不要用字元差異計算，要從語意傳達與流暢自然的角度判斷。
                這是一次完全獨立的評估：只依據本次的目標句與患者語音判分，
                沒有任何先前的練習、分數或對話——不得參考、比較或延續任何過去的評估結果。
            """.trimIndent(),
            user = """
                目標句：$target
                患者說出的：${recognized.ifBlank { "（未偵測到聲音）" }}$confInfo

                評分細則：
                • 語意完整性（50%）：核心意思有沒有傳達？關鍵詞說到了嗎？即使用詞稍異但意思相同可給高分。
                • 流暢性（30%）：有無重複、結巴？語氣是否連貫自然？信心度低的字可能發音不清。
                • 語氣語調（20%）：語氣是否符合句型（問句/請求/感謝）？

                只回傳 JSON：{"score":整數, "feedback":"一句繁體中文鼓勵或指引（20字以內）", "wrongChars":["說得不準或漏掉的字"]}
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
        return CloudPrompt(
            system = """
                你是輔助失語症患者溝通的語言助理。
                請使用碎詞、逐字信心度、GPS 場景與相機物件，重組患者最可能想表達的句子。

                【最重要的原則：貼著碎詞走，不要腦補新內容】
                句子裡每個實詞（名詞、動詞、形容詞）都必須能對應回碎詞本身，或明確對應到給定的
                地點／物件情境標籤；你只能補上讓句子成立所需的「語法零件」——代名詞（我、你）、
                助動詞（請、需要、想）、語助詞（了、嗎、呢）——不可以新增碎詞裡完全沒提到的
                新事件、新時間、新對象或新問句。
                例如碎詞「家人...快」只代表「家人動作要快」，應重組成「請家人快一點」這類貼近原意的句子；
                不可以延伸成「我想知道家人什麼時候會到」——「什麼時候會到」是碎詞裡沒有的全新問句，屬於過度腦補。
                低信心字可以依情境修正用字（例如同音異字），但不能藉機加戲、加細節。

                若有提供「語音辨識其他候選」：那是同一段語音的其他可能聽法，不是病人講了很多句。
                第一名（碎詞）不一定對——失語症口齒不清時常常聽錯。請比對這些候選與地點／物件情境，
                挑出最合理的那個當依據；必要時可以混用不同候選裡明顯比較合理的字詞。
                但仍然不可以加入任何候選裡都沒有出現的新內容。

                輸入中的問句或疑問詞是硬約束，必須保留其語意；例如「什麼時候」、「在哪裡」、「可以嗎」、「要等多久」不可刪掉。
                若碎詞同時包含需求與問句，請用一句自然中文同時表達，例如「我口渴了，請給我一杯水；我想知道什麼時候可以休息。」

                重要：碎詞很短甚至只有一個字（例如「十」「水」「痛」）時，仍要給出「最可能的日常說法」，不要因為短就拒答或回空句。
                只補最貼近字面的語法零件即可，不要另外編情境：「十」→「我要十個」、「水」→「我想喝水」、「痛」→「我這裡會痛」。
                confidence 是「你對這句重組的把握」，請照這個標準給分，不要一律保守：
                  80-100：碎詞語意清楚、重組幾乎確定
                  50-79 ：碎詞短或有歧義，但你給了合理的常見猜測（單字碎詞多半落在這裡）
                  1-49  ：碎詞真的無意義、看不出想表達什麼，才給低分
                只輸出 JSON：{"reasoning":"簡短分析","reconstructed":"完整繁體中文句子","confidence":0到100整數}
            """.trimIndent(),
            user = buildString {
                appendLine("碎詞：${vault.rawText}")
                if (words.isNotBlank()) appendLine("逐字信心：$words")
                if (vault.sttAlternatives.isNotEmpty()) {
                    appendLine("語音辨識其他候選（同一段語音的其他聽法，擇最合理者）：")
                    vault.sttAlternatives.forEach { appendLine("- $it") }
                }
                appendLine("地點：${vault.locationTag.ifBlank { "未指定" }}")
                appendLine("周邊物件：${vault.visionTags.joinToString("、").ifBlank { "未偵測" }}")
                val cands = com.example.data.AphasiaPractice.reconstructionCandidates(vault.rawText, vault.visionTags)
                if (cands.isNotEmpty()) {
                    appendLine("常用表達參考（碎詞語意接近其中之一時，優先採用其自然說法；但不可硬套、不可改變問句語意）：")
                    cands.forEach { appendLine("- $it") }
                }
            },
            temperature = 0.1,
            maxTokens = 512
        )
    }

    private fun buildSuggestPrompt(avgScore: Double, location: String, recent: List<String>) = CloudPrompt(
        system = """
            你是失語症語言復健助理。依患者近期平均分、場景與已練過句子，產生 3 句適合跟讀的繁體中文練習句。
            難度規則：分數<60 產生 1-4 字短詞；60-79 產生 5-8 字短句；>=80 產生 9-15 字完整情境句。
            只輸出 JSON：{"sentences":["...","...","..."],"level":"easy|medium|hard"}
        """.trimIndent(),
        user = """
            最近平均分：${avgScore.toInt()}
            當前場景：${location.ifBlank { "未指定" }}
            已練過：${recent.take(5)}
        """.trimIndent(),
        temperature = 0.6,
        maxTokens = 256
    )

    private fun parseCoT(raw: String): CoTParsed {
        val jsonText = raw.extractJsonObject()
        if (jsonText != null) {
            val o = JSONObject(jsonText)
            return CoTParsed(
                reconstructed = o.optString("reconstructed").trim(),
                confidence = o.optInt("confidence", 70).coerceIn(0, 100) / 100.0
            )
        }
        return CoTParsed(raw.trim(), 0.6)
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

    private data class CoTParsed(val reconstructed: String, val confidence: Double)

    companion object {
        private const val TAG = "HybridInferenceEngine"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        // 語句重組自我一致性：平行取樣次數；溫度略高於單次呼叫的 0.1，才有足夠多樣性可供多數決，
        // 但不宜太高——溫度越高，模型越容易編出碎詞裡沒有的新內容（腦補），0.3 是折衷值。
        private const val RECONSTRUCT_SAMPLES = 3
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
            if (provider == CloudProvider.LocalOnly) fallbackEngine
            else HybridInferenceEngine(context, provider, fallbackEngine)
    }
}

private object CloudHealth {
    private const val FAILURE_COOLDOWN_MS = 60_000L
    private val cursor = AtomicInteger(0)
    private val failuresUntil = mutableMapOf<String, Long>()
    private val latencyMs = mutableMapOf<String, Long>()

    @Synchronized
    fun isAvailable(id: String): Boolean =
        (failuresUntil[id] ?: 0L) <= System.currentTimeMillis()

    @Synchronized
    fun latencyPenalty(id: String): Long =
        latencyMs[id] ?: Long.MAX_VALUE / 4

    @Synchronized
    fun recordSuccess(id: String, latency: Long) {
        failuresUntil.remove(id)
        latencyMs[id] = latency
    }

    @Synchronized
    fun recordFailure(id: String) {
        failuresUntil[id] = System.currentTimeMillis() + FAILURE_COOLDOWN_MS
    }

    fun nextOffset(size: Int): Int =
        Math.floorMod(cursor.getAndIncrement(), size)
}
