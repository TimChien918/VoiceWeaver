package com.example.ai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * MID-TIER 推理引擎：透過 MediaPipe LLM Inference API 在裝置端執行
 * Phi-3 Mini (3.8B · INT4 量化) 或 Qwen2.5-1.5B 離線推理。
 *
 * API 層次：
 * - [LlmInference]（模型層）：管理模型檔案載入與底層 NPU/GPU/CPU 後端分配。
 * - [LlmInferenceSession]（推理層）：每次推理建立獨立 Session，精確控制 temperature/topK/seed。
 *
 * 模型規格：
 * - 格式：MediaPipe .task（由 ai-edge-torch 工具鏈轉換的 flatbuffer 格式）
 * - 打包路徑：APK assets/models/[MODEL_FILENAME]
 * - 工作路徑：[Context.noBackupFilesDir]/models/[MODEL_FILENAME]
 * - 體積：~2.4 GB（首次啟動需下載，需 Wi-Fi 環境）
 */
class MediaPipeLLMEngine(private val context: Context) : InferenceEngine {

    // LlmInference 是執行緒安全的重量級物件，使用 lazy 確保整個 App 生命週期只建立一次
    private var llmInference: LlmInference? = null

    /**
     * 初始化 MediaPipe LLM 引擎（含首次下載）。
     * 在 ViewModel [detectHardwareAndInitialize] 中預熱呼叫，避免第一次推理時的長等待。
     */
    suspend fun initialize(onDownloadProgress: (Int) -> Unit = {}): Result<Unit> = withContext(Dispatchers.IO) {
        if (llmInference != null) return@withContext Result.success(Unit)

        try {
            val modelFile = prepareModelFile(onDownloadProgress)
            if (!modelFile.exists()) {
                Log.w(TAG, "Model file not found at: ${modelFile.absolutePath}")
                return@withContext Result.failure(
                    ModelNotPresentException(
                        "模型檔案不存在。\n\n" +
                        "請把 MediaPipe 相容模型放進 APK assets：\n" +
                        "app/src/main/assets/$ASSET_MODEL_PATH\n\n" +
                        "或先使用 App 內建下載功能放到私有模型工作區。"
                    )
                )
            }

            synchronized(modelInstallLock) {
                sharedLlmInference?.let {
                    llmInference = it
                    Log.i(TAG, "Reusing shared MediaPipe LLM engine.")
                    return@withContext Result.success(Unit)
                }

                Log.i(TAG, "Loading model: ${modelFile.absolutePath} (${modelFile.length() / 1_000_000} MB)")

                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(512)
                    .setMaxTopK(40)
                    .build()

                llmInference = LlmInference.createFromOptions(context, options)
                sharedLlmInference = llmInference
            }
            Log.i(TAG, "MediaPipe LLM engine ready.")
            Result.success(Unit)

        } catch (e: ModelNotPresentException) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaPipe LLM engine", e)
            Result.failure(e)
        }
    }

    /**
     * 用 Qwen 依患者程度生成適合的跟讀練習句。失敗則退回預設規則 fallback（介面提供）。
     * 難度規則：<60 短詞(1-4字) / 60-79 短句(5-8字) / ≥80 完整句(9-15字)
     */
    override suspend fun suggestPractice(
        avgScore: Double,
        location: String,
        recent: List<String>
    ): List<String> = withContext(Dispatchers.IO) {
        val raw = runLlmSession(
            prompt = buildSuggestPrompt(avgScore, location, recent),
            temperature = 0.7f,                              // 推薦要適度發散（重組要低溫，推薦要點變化）
            topK = 40,
            seed = (System.currentTimeMillis() and 0xFFFF).toInt()
        ) ?: return@withContext super.suggestPractice(avgScore, location, recent)
        Log.d(TAG, "Suggest raw: $raw")
        val parsed = parseSuggestions(raw)
        if (parsed.isNotEmpty()) parsed else super.suggestPractice(avgScore, location, recent)
    }

    /**
     * AAC 圖卡序列 → 自然口語句子（本地 Qwen 版）。
     * 失敗退回介面預設（純串接）。
     */
    override suspend fun composeAacSentence(
        items: List<com.example.data.AacItem>,
        location: String
    ): Result<String> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext Result.success("")
        val raw = runLlmSession(
            prompt = buildAacPrompt(items, location),
            temperature = 0.4f,
            topK = 20,
            seed = (System.currentTimeMillis() and 0xFFFF).toInt()
        ) ?: return@withContext super.composeAacSentence(items, location)
        Log.d(TAG, "AAC compose raw: $raw")
        val cleaned = raw
            .replace("<|im_end|>", "").replace("<|im_start|>", "")
            .replace("<|endoftext|>", "")
            .trim().removePrefix("「").removeSuffix("」").trim()
        Result.success(cleaned.ifBlank { items.joinToString("") { it.label } })
    }

    /**
     * 裝置端 AI 復健評分（Gemma/Qwen，離線）。完全由 AI 判語意/流暢/語氣，非字元差異。
     * 失敗退回介面預設（Levenshtein + 字元差異），確保一定有分數。
     */
    override suspend fun scoreRehab(
        target: String,
        recognized: String,
        words: List<WordConf>
    ): RehabEvaluation = withContext(Dispatchers.IO) {
        val raw = runLlmSession(
            prompt = buildRehabEvalPrompt(target, recognized, words),
            temperature = 0.2f,   // 評分求穩定
            topK = 20,
            seed = (System.currentTimeMillis() and 0xFFFF).toInt()
        ) ?: return@withContext super.scoreRehab(target, recognized, words)
        Log.d(TAG, "Rehab eval raw: $raw")
        parseRehabEval(raw) ?: super.scoreRehab(target, recognized, words)
    }

    private fun buildRehabEvalPrompt(target: String, recognized: String, words: List<WordConf>) = buildString {
        val conf = if (words.isNotEmpty()) "（信心：${words.joinToString(" ") { "${it.word}${(it.conf*100).toInt()}%" }}）" else ""
        appendLine("<|im_start|>system")
        appendLine(
            "你是失語症語言治療師，評估患者口語跟讀。不要用字元差異，要看語意完整(50%)、流暢(30%)、語氣(20%)。" +
            "每次評估都是獨立事件：只依據本次的目標句與患者語音判分，不參考、不比較、不延續任何先前的評估或分數（下面的範例只是輸出格式示範）。" +
            "只輸出 JSON：{\"score\":0到100整數,\"feedback\":\"20字內鼓勵\",\"wrongChars\":[\"說不準的字\"]}<|im_end|>"
        )
        appendLine("<|im_start|>user")
        appendLine("目標句：我想喝水")
        appendLine("患者說出：我要水<|im_end|>")
        appendLine("<|im_start|>assistant")
        appendLine("{\"score\":85,\"feedback\":\"意思很清楚，很好！\",\"wrongChars\":[\"想\"]}<|im_end|>")
        appendLine("<|im_start|>user")
        appendLine("目標句：$target")
        appendLine("患者說出：${recognized.ifBlank { "（未偵測到聲音）" }}$conf<|im_end|>")
        append("<|im_start|>assistant\n")
    }

    private fun parseRehabEval(raw: String): RehabEvaluation? {
        val cleaned = raw.replace("<|im_end|>","").replace("<|im_start|>","").replace("<|endoftext|>","").trim()
        val s = cleaned.indexOf('{'); val e = cleaned.lastIndexOf('}')
        if (s !in 0 until e) return null
        return try {
            val o = JSONObject(cleaned.substring(s, e + 1))
            val score = o.optInt("score", -1)
            if (score !in 0..100) return null
            val wrong = mutableListOf<String>()
            o.optJSONArray("wrongChars")?.let { arr -> for (i in 0 until arr.length()) arr.optString(i).trim().takeIf { it.isNotBlank() }?.let(wrong::add) }
            RehabEvaluation(score, o.optString("feedback","").trim(), wrong)
        } catch (e: Exception) { null }
    }

    private fun buildAacPrompt(items: List<com.example.data.AacItem>, location: String) = buildString {
        appendLine("<|im_start|>system")
        appendLine(
            "你是失語症患者的溝通助理。使用者透過圖卡點選一串元素，請組合成一句**自然口語、有禮貌**的繁體中文。" +
            "規則：只輸出一句、不解釋、不要引號；金額自然嵌入；不憑空加入未提到的物品。<|im_end|>"
        )
        appendLine("<|im_start|>user")
        appendLine("場景：客廳")
        appendLine("圖卡序列：水 → NT\$50<|im_end|>")
        appendLine("<|im_start|>assistant")
        appendLine("麻煩給我一杯水，五十元。<|im_end|>")
        appendLine("<|im_start|>user")
        appendLine("場景：${location.ifBlank { "未指定" }}")
        appendLine("圖卡序列：${items.joinToString(" → ") { it.label }}<|im_end|>")
        append("<|im_start|>assistant\n")
    }

    private fun buildSuggestPrompt(avgScore: Double, location: String, recent: List<String>) = buildString {
        appendLine("<|im_start|>system")
        appendLine(
            "你是失語症語言治療助理。依患者狀況產出 3 句適合「跟讀練習」的繁體中文短句。" +
            "難度規則：分數<60→1-4字短詞；60-79→5-8字短句；≥80→9-15字完整情境句。" +
            "句子要日常實用、避開重複「已練過」、貼合「當前場景」。" +
            "只輸出 JSON：{\"sentences\":[\"...\",\"...\",\"...\"],\"level\":\"easy|medium|hard\"}，不要其他文字。<|im_end|>"
        )
        appendLine("<|im_start|>user")
        appendLine("最近平均分：72")
        appendLine("當前場景：客廳")
        appendLine("已練過：[\"我想喝水\"]<|im_end|>")
        appendLine("<|im_start|>assistant")
        appendLine("{\"sentences\":[\"幫我倒杯茶\",\"我想看電視\",\"請拿遙控器\"],\"level\":\"medium\"}<|im_end|>")
        appendLine("<|im_start|>user")
        appendLine("最近平均分：${avgScore.toInt()}")
        appendLine("當前場景：${location.ifBlank { "未指定" }}")
        appendLine("已練過：${recent.take(5)}")
        append("<|im_end|>\n<|im_start|>assistant\n")
    }

    private fun parseSuggestions(raw: String): List<String> {
        val cleaned = raw
            .replace("<|im_end|>", "").replace("<|im_start|>", "")
            .replace("<|endoftext|>", "").trim()
        val start = cleaned.indexOf('{'); val end = cleaned.lastIndexOf('}')
        if (start in 0 until end) {
            return try {
                val o = JSONObject(cleaned.substring(start, end + 1))
                val arr = o.optJSONArray("sentences") ?: return emptyList()
                val out = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i).trim()
                    if (s.isNotBlank()) out.add(s)
                }
                out
            } catch (e: Exception) {
                Log.w(TAG, "suggest JSON parse fail: ${e.message}")
                emptyList()
            }
        }
        return emptyList()
    }

    override suspend fun reconstructSentence(vault: GlobalTagsVault): Result<ReconstructedData> =
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            try {
                val result = runLlmSession(
                    prompt = buildChatPrompt(vault),
                    temperature = 0.1f,  // 低溫 = 低幻覺，適合醫療場景
                    topK = 1,            // Greedy decoding，零隨機性
                    seed = 42
                ) ?: return@withContext Result.failure(
                    IllegalStateException("LLM engine 初始化失敗")
                )

                Log.d(TAG, "Raw LLM output: $result")

                val cot = parseCoT(result, vault)
                Log.d(TAG, "CoT parsed: reconstructed='${cot.reconstructed}' confidence=${cot.confidence}")

                Result.success(
                    ReconstructedData(
                        originalText      = vault.rawText,
                        reconstructedText = cot.reconstructed,
                        confidence        = cot.confidence,
                        engineUsed        = "On-Device LLM (Gemma 3n / Qwen2.5 · MediaPipe · CoT)",
                        inferenceTimeMs   = System.currentTimeMillis() - startTime
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "MediaPipe session inference failed", e)
                Result.failure(e)
            }
        }

    /**
     * 統一的 LLM 推理路徑：lazy 初始化引擎 → 建立 Session → 跑 prompt → 回傳原始輸出。
     * 兩條呼叫端（reconstructSentence 走低溫 greedy；suggestPractice 走中溫發散）共用此 helper，
     * 避免兩處各自重複「engine 判 null + sessionOptions builder + use {} 塊」的樣板。
     *
     * @return 模型原始輸出字串；引擎初始化失敗或推理異常時回傳 null（由呼叫端決定 fallback）
     */
    private suspend fun runLlmSession(
        prompt: String,
        temperature: Float,
        topK: Int,
        seed: Int
    ): String? = withContext(Dispatchers.IO) {
        val engine = llmInference ?: run {
            initialize().onFailure { return@withContext null }
            llmInference
        } ?: return@withContext null

        val options = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTemperature(temperature)
            .setTopK(topK)
            .setRandomSeed(seed)
            .build()

        try {
            LlmInferenceSession.createFromOptions(engine, options).use { session ->
                session.addQueryChunk(prompt)
                session.generateResponse()
            }
        } catch (e: Exception) {
            Log.e(TAG, "LLM session failed", e)
            null
        }
    }

    /**
     * Qwen2.5 ChatML 格式，要求模型以 JSON 鏈式思考(CoT)輸出。
     * 碎詞附逐字辨識信心度 → 模型對低信心字依情境修正；confidence 由模型自評，
     * 供 ReconstructionUseCase 的 <0.40 fallback 真正生效（短碎詞給合理猜測、標低把握而非藏掉）。
     */
    private fun buildChatPrompt(vault: GlobalTagsVault) = buildString {
        appendLine("<|im_start|>system")
        appendLine(
            "你是輔助失語症患者溝通的語言助理。使用者碎詞來自語音辨識，部分字附信心度(%)；" +
            "信心低的字很可能聽錯，請依地點與周邊物件推測修正。" +
            "碎詞很短甚至只有一個字（例如「十」「水」「痛」）時，仍要給最可能的日常說法，不要因為短就拒答：" +
            "例如「十」→「我要十個」或「現在十點」、「水」→「我想喝水」、「痛」→「我這裡會痛」。" +
            "請只輸出一個 JSON 物件，格式：" +
            "{\"reasoning\":\"簡短分析\",\"reconstructed\":\"完整繁體中文句子\",\"confidence\":0到100的整數}。" +
            "confidence 標準：清楚可確定給80-100；短或有歧義但你給了合理常見猜測給50-79（單字碎詞多半在此）；" +
            "真的無意義才給1-49。不要一律保守。不要輸出 JSON 以外的任何文字。<|im_end|>"
        )
        appendLine("<|im_start|>user")
        appendLine("碎詞：渴 水 倒")
        appendLine("逐字信心：渴(91%) 水(58%) 倒(83%)")
        appendLine("地點：客廳 (家)<|im_end|>")
        appendLine("<|im_start|>assistant")
        appendLine("{\"reasoning\":\"碎詞與口渴、倒水有關，地點客廳\",\"reconstructed\":\"在客廳，我覺得口渴了，可以幫我倒杯水嗎？\",\"confidence\":88}<|im_end|>")
        appendLine("<|im_start|>user")
        appendLine("碎詞：${vault.rawText}")
        if (vault.sttWords.isNotEmpty()) {
            appendLine("逐字信心：${formatConfidence(vault.sttWords)}")
        }
        appendLine("地點：${vault.locationTag}")
        if (vault.visionTags.isNotEmpty()) {
            appendLine("周邊物件：${vault.visionTags.joinToString("、")}")
        }
        val cands = com.example.data.AphasiaPractice.reconstructionCandidates(vault.rawText, vault.visionTags)
        if (cands.isNotEmpty()) {
            appendLine("常用表達參考（語意接近時優先採用其說法，勿硬套）：${cands.joinToString("／")}")
        }
        append("<|im_end|>")
        append("\n<|im_start|>assistant\n")
    }

    private fun formatConfidence(words: List<WordConf>): String =
        words.joinToString(" ") { "${it.word}(${(it.conf * 100).toInt()}%)" }

    private data class CoTParsed(val reconstructed: String, val confidence: Double)

    /**
     * 容錯解析 CoT JSON。小模型偶爾吐壞 JSON，故：
     *  1. 先剝除 ChatML token
     *  2. 擷取第一個 '{' 到最後一個 '}' 嘗試解析
     *  3. 失敗則退回把整段純文字當作重組句（信心給保守值）
     */
    private fun parseCoT(raw: String, vault: GlobalTagsVault): CoTParsed {
        val cleaned = raw
            .replace("<|im_end|>", "")
            .replace("<|im_start|>", "")
            .replace("<|endoftext|>", "")
            .trim()

        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start in 0 until end) {
            try {
                val o = JSONObject(cleaned.substring(start, end + 1))
                val recon = o.optString("reconstructed").trim()
                if (recon.isNotBlank()) {
                    val conf = o.optInt("confidence", 70).coerceIn(0, 100) / 100.0
                    return CoTParsed(recon, conf)
                }
            } catch (e: Exception) {
                Log.w(TAG, "CoT JSON parse failed, fallback to plain text: ${e.message}")
            }
        }
        // 退回：整段純文字當重組句（去掉殘留 JSON 符號），信心保守
        val plain = cleaned.removePrefix("assistant").trim().ifBlank { buildFallback(vault) }
        return CoTParsed(plain, 0.6)
    }

    private fun buildFallback(vault: GlobalTagsVault): String {
        val raw = vault.rawText; val loc = vault.locationTag
        return when {
            Regex("渴|水|喝|飲").containsMatchIn(raw) -> "在${loc}，我口渴了，請幫我倒杯水。"
            Regex("餓|吃|食|飯").containsMatchIn(raw) -> "在${loc}，我肚子餓了，想要吃東西。"
            Regex("痛|不舒服|藥").containsMatchIn(raw) -> "在${loc}，我感到不適，需要幫助。"
            else -> "我想告訴您：「${raw}」，當前位於${loc}。"
        }
    }

    // ─── 模型檔案管理 ──────────────────────────────────────────────

    private fun prepareModelFile(onProgress: (Int) -> Unit): File {
        synchronized(modelInstallLock) {
            val packaged = bundledAssetExists(context)
            val workingFile = workingModelFile(context)
            if (packaged) {
                copyBundledModelIfNeeded(workingFile, onProgress)
                deleteLegacyModelIfSafe(workingFile)
                return workingFile
            }

            if (workingFile.exists()) return workingFile

            val legacy = legacyModelFile(context)
            if (legacy.exists()) {
                Log.w(TAG, "Using legacy model path: ${legacy.absolutePath}")
                return legacy
            }

            return workingFile
        }
    }

    private fun copyBundledModelIfNeeded(target: File, onProgress: (Int) -> Unit) {
        val assetSize = bundledAssetSize(context)
        val marker = File(target.parentFile, "$MODEL_FILENAME.source")
        // 已經是內建模型（大小相符）→ 不動
        if (target.exists() && assetSize > 0L && target.length() == assetSize) {
            runCatching { marker.writeText("bundled:$assetSize") }
            onProgress(100)
            return
        }
        // 工作檔已存在、是有效模型、且大小與內建不同：
        //   - 若來源標記為內建（代表內建版本換了）→ 更新成新的內建。
        //   - 否則視為「使用者自備模型」（App 匯入 / ADB 推送 Gemma 等）→ 保留，不覆蓋。
        if (target.exists() && target.length() > 100_000_000L && target.length() != assetSize) {
            val markedBundled = runCatching { marker.readText().trim().startsWith("bundled:") }.getOrDefault(false)
            if (!markedBundled) {
                Log.i(TAG, "保留使用者自備模型（${target.length() / 1_000_000} MB），不以內建模型覆蓋")
                onProgress(100)
                return
            }
            Log.i(TAG, "內建模型版本已更新（${target.length() / 1_000_000} MB → ${assetSize / 1_000_000} MB），重新複製")
        }

        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "$MODEL_FILENAME.copying")
        Log.i(TAG, "Copying bundled model asset to MediaPipe working file: ${target.absolutePath}")

        context.assets.open(ASSET_MODEL_PATH).use { input ->
            FileOutputStream(tmp).use { output ->
                val buffer = ByteArray(1024 * 1024)
                var copied = 0L
                var lastPct = -1
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (assetSize > 0L) {
                        val pct = (copied * 100 / assetSize).toInt().coerceIn(0, 100)
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
        }

        if (!tmp.renameTo(target) && tmp.exists()) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        runCatching { marker.writeText("bundled:$assetSize") }   // 標記此工作檔來自內建
        onProgress(100)
    }

    private fun deleteLegacyModelIfSafe(workingFile: File) {
        val legacy = legacyModelFile(context)
        if (workingFile.exists() && legacy.exists() && legacy.absolutePath != workingFile.absolutePath) {
            runCatching { legacy.delete() }
            runCatching { File(legacy.parentFile, "$MODEL_FILENAME.xnnpack_cache").delete() }
        }
    }

    fun release() {
        synchronized(modelInstallLock) {
            if (llmInference != null && llmInference === sharedLlmInference) {
                sharedLlmInference?.close()
                sharedLlmInference = null
            }
        }
        llmInference = null
    }

    companion object {
        private const val TAG = "MediaPipeLLMEngine"
        private val modelInstallLock = Any()
        private var sharedLlmInference: LlmInference? = null

        /**
         * 本機 LLM 模型統一存放檔名（model-agnostic）。
         * App 內建自動下載 Qwen2.5-1.5B（公開、非 gated）並存為此檔名；
         * 亦可手動 ADB 推送任何 MediaPipe LLM Inference 相容的 .task：
         *   adb push <model>.task /data/user/0/<package>/files/models/$MODEL_FILENAME
         */
        const val MODEL_FILENAME = "ondevice-llm.task"
        const val ASSET_MODEL_PATH = "models/$MODEL_FILENAME"

        fun isModelReady(context: Context) =
            bundledAssetExists(context) ||
            workingModelFile(context).let { it.exists() && it.length() > 100_000_000L } ||
            legacyModelFile(context).let { it.exists() && it.length() > 100_000_000L }

        fun modelPath(context: Context) =
            "${context.noBackupFilesDir}/models/$MODEL_FILENAME"

        fun workingModelFile(context: Context): File =
            File(context.noBackupFilesDir, "models").also { it.mkdirs() }.resolve(MODEL_FILENAME)

        fun legacyModelFile(context: Context): File =
            File(context.filesDir, "models/$MODEL_FILENAME")

        fun bundledAssetExists(context: Context): Boolean =
            runCatching {
                context.assets.open(ASSET_MODEL_PATH).use { true }
            }.getOrDefault(false)

        fun bundledAssetSize(context: Context): Long =
            runCatching {
                context.assets.openFd(ASSET_MODEL_PATH).use { it.length }
            }.getOrDefault(-1L)
    }
}

class ModelNotPresentException(message: String) : Exception(message)
