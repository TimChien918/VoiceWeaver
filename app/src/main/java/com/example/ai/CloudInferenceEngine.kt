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
            val (selected, cot) = tryCloudWithFailover(buildReconstructPrompt(vault), timeoutMs = 16_000) { raw ->
                val parsed = parseCoT(raw)
                if (parsed.reconstructed.isBlank()) error("雲端模型回傳空句子")
                parsed
            }
            Result.success(
                ReconstructedData(
                    originalText = vault.rawText,
                    // 雲端 LLM 常回傳前後夾帶換行/空白（甚至多段空行），不清掉會在輸出卡撐出
                    // 大片空白、把句子推到底部。壓成單行：trim + 把連續換行/空白收斂成單一空格。
                    reconstructedText = cot.reconstructed.trim().replace(Regex("\\s*\\n\\s*"), " "),
                    confidence = cot.confidence,
                    engineUsed = "${selected.label}（本地備援待命）",
                    inferenceTimeMs = System.currentTimeMillis() - start
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Cloud inference failed, fallback to local: ${e.message}")
            fallbackEngine.reconstructSentence(vault).map { local ->
                local.copy(engineUsed = "${local.engineUsed} (Cloud fallback: ${provider.displayName})")
            }
        }
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

    private suspend fun callCloud(entry: LlmApiEntry, prompt: CloudPrompt): String =
        withContext(Dispatchers.IO) {
            when (entry.provider.style) {
                LlmApiStyle.OPENAI -> callOpenAiCompatible(entry.effectiveEndpoint, entry.key.trim(), entry.effectiveModel, prompt)
                LlmApiStyle.COHERE -> callCohere(entry.effectiveEndpoint, entry.key.trim(), entry.effectiveModel, prompt)
                LlmApiStyle.GEMINI -> callGemini(entry.effectiveEndpoint, entry.key.trim(), entry.effectiveModel, prompt)
            }
        }

    private fun callGemini(endpoint: String, apiKey: String, model: String, prompt: CloudPrompt): String {
        if (apiKey.isBlank()) error("API key 未設定")
        val url = "${endpoint.trimEnd('/')}/$model:generateContent?key=$apiKey"
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt.system))))
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt.user)))))
            .put("generationConfig", JSONObject().put("temperature", prompt.temperature).put("maxOutputTokens", prompt.maxTokens))
            .toString().toRequestBody(JSON)
        val req = Request.Builder().url(url).header("Content-Type", "application/json").post(body).build()
        client.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("HTTP ${res.code}: ${text.take(160)}")
            return JSONObject(text).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                .optString("text").trim()
        }
    }

    private fun callOpenAiCompatible(
        endpoint: String,
        apiKey: String,
        model: String,
        prompt: CloudPrompt
    ): String {
        if (apiKey.isBlank()) error("API key 未設定")
        val body = JSONObject()
            .put("model", model)
            .put("temperature", prompt.temperature)
            .put("max_tokens", prompt.maxTokens)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", prompt.system))
                    .put(JSONObject().put("role", "user").put("content", prompt.user))
            )
            .toString()
            .toRequestBody(JSON)

        val req = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", "https://voiceweaver.local")
            .header("X-Title", "VoiceWeaver")
            .post(body)
            .build()

        client.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("HTTP ${res.code}: ${text.take(160)}")
            return JSONObject(text)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content")
                .trim()
        }
    }

    private fun callCohere(endpoint: String, apiKey: String, model: String, prompt: CloudPrompt): String {
        if (apiKey.isBlank()) error("API key 未設定")
        val body = JSONObject()
            .put("model", model)
            .put("temperature", prompt.temperature)
            .put("max_tokens", prompt.maxTokens)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", prompt.system))
                    .put(JSONObject().put("role", "user").put("content", prompt.user))
            )
            .toString()
            .toRequestBody(JSON)

        val req = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        client.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("HTTP ${res.code}: ${text.take(160)}")
            val content = JSONObject(text).getJSONObject("message").getJSONArray("content")
            return buildString {
                for (i in 0 until content.length()) {
                    append(content.getJSONObject(i).optString("text"))
                }
            }.trim()
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
                不要過度腦補；低信心字可以依情境修正，但強烈意圖必須能從輸入或情境找到依據。
                輸入中的問句或疑問詞是硬約束，必須保留其語意；例如「什麼時候」、「在哪裡」、「可以嗎」、「要等多久」不可刪掉。
                若碎詞同時包含需求與問句，請用一句自然中文同時表達，例如「我口渴了，請給我一杯水；我想知道什麼時候可以休息。」

                重要：碎詞很短甚至只有一個字（例如「十」「水」「痛」）時，仍要給出「最可能的日常說法」，不要因為短就拒答或回空句。
                以最常見的生活情境去補全，例如「十」→「我要十個」或「現在十點」（依情境擇一最合理的）、「水」→「我想喝水」、「痛」→「我這裡會痛」。
                confidence 是「你對這句重組的把握」，請照這個標準給分，不要一律保守：
                  80-100：碎詞語意清楚、重組幾乎確定
                  50-79 ：碎詞短或有歧義，但你給了合理的常見猜測（單字碎詞多半落在這裡）
                  1-49  ：碎詞真的無意義、看不出想表達什麼，才給低分
                只輸出 JSON：{"reasoning":"簡短分析","reconstructed":"完整繁體中文句子","confidence":0到100整數}
            """.trimIndent(),
            user = buildString {
                appendLine("碎詞：${vault.rawText}")
                if (words.isNotBlank()) appendLine("逐字信心：$words")
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
        val maxTokens: Int
    )

    private data class CoTParsed(val reconstructed: String, val confidence: Double)

    companion object {
        private const val TAG = "HybridInferenceEngine"
        private val JSON = "application/json; charset=utf-8".toMediaType()

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
