package com.example.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Task 3.1: Android Native 輕量防護網 (NativeRuleCritic)
 * 嚴禁使用 ML 模型以防 OOM，改用純 Kotlin 實作之輕量級字串相似度與意圖過濾演算法。
 * 執行於 Dispatchers.Default。
 */
object NativeRuleCritic {

    // 定義強烈意圖關鍵詞（強烈意圖詞彙庫）
    private val STRONG_INTENT_WORDS = setOf(
        "渴", "水", "杯", "喝", "飲", "茶",
        "餓", "飯", "麵", "吃", "飽", "食", "餐",
        "不舒服", "痛", "藥", "醫", "病", "吐", "暈", "難受", "傷",
        "廁所", "尿", "便", "洗手間", "馬桶", "拉",
        "睡", "累", "床", "瞇", "睏",
        "救", "警", "急", "跌", "扶", "倒", "幫忙", "護士",
        "熱", "冷", "寒", "悶"
    )

    // 常見中文停用詞，計算 Jaccard 相似度時予以排除以提昇精準度
    private val STOP_WORDS = setOf(
        '，', '。', '？', '！', '、', '的', '了', '是', '我', '你', '他', '在', '有', '個', 
        '和', '與', '或', '且', '這', '那', '就', '去', '要', '想', '會', '能', '可', '到',
        '、', '；', '：', '「', '」', '『', '』', '(', ')', '[', ']', '{', '}', ' ', '\t', '\n'
    )

    /**
     * 審查重組後的文本是否包含幻覺或過度解讀
     * @return Boolean true 表示通過（無明顯幻覺，語法安全），false 表示判定為幻覺或不通過
     */
    suspend fun audit(
        reconstructedText: String,
        vault: GlobalTagsVault
    ): Boolean = withContext(Dispatchers.Default) {
        if (reconstructedText.isBlank()) return@withContext false

        // 1. 統一轉為小寫簡潔化處理
        val normalizedReconstructed = reconstructedText.lowercase()
        val normalizedRaw = vault.rawText.lowercase()
        val normalizedLocation = vault.locationTag.lowercase()
        val normalizedVision = vault.visionTags.joinToString(" ").lowercase()

        // 2. 檢測重組句中出現的所有強烈意圖關鍵字
        val reconstructedIntents = STRONG_INTENT_WORDS.filter { word ->
            normalizedReconstructed.contains(word)
        }

        // 3. 檢查這些強烈意圖關鍵字是否可在原始輸入或環境 Tag 中找到對應關聯
        for (intentWord in reconstructedIntents) {
            val inRaw = normalizedRaw.contains(intentWord)
            val inLocation = normalizedLocation.contains(intentWord)
            val inVision = normalizedVision.contains(intentWord)

            // 如果出現了強烈意圖，但該意圖在 vault (rawText/location/vision) 中找不到任何影子，
            // 判定為 LLM 自行憑空捏造的幻覺 (全新強烈意圖詞彙)，不予通過
            if (!inRaw && !inLocation && !inVision) {
                Log.w(
                    "NativeRuleCritic",
                    "Audit FAILED: Detected hallucinated intent word '$intentWord' not present in GlobalTagsVault."
                )
                return@withContext false
            }
        }

        // 4. 計算 Jaccard Index (相似度交集) 作為雙重防線
        val reconstructedChars = normalizedReconstructed.filter { !STOP_WORDS.contains(it) }.toSet()
        
        // 組合整個 Vault 作為比對母體
        val vaultCleanText = "$normalizedRaw $normalizedLocation $normalizedVision"
        val vaultChars = vaultCleanText.filter { !STOP_WORDS.contains(it) }.toSet()

        if (reconstructedChars.isEmpty()) {
            return@withContext true
        }

        val intersection = reconstructedChars.intersect(vaultChars)
        val union = reconstructedChars.union(vaultChars)
        val jaccardIndex = intersection.size.toDouble() / union.size.toDouble()

        Log.d(
            "NativeRuleCritic",
            "Audit metrics - Jaccard Index: $jaccardIndex (Intersection: ${intersection.size}, Union: ${union.size})"
        )

        // 如果原始輸入極短且包含英文字，Jaccard 雖然會低，但只要沒有憑空冒出不相干的強烈意圖，我們仍放行。
        // 若 Jaccard Index 大於 0.05 或是至少包含原始碎詞的所有實質中文字特徵，則判定放行
        val rawCharsToCheck = normalizedRaw.filter { !STOP_WORDS.contains(it) && it.isDefined() }.toSet()
        val containsAllRawNouns = rawCharsToCheck.all { reconstructedChars.contains(it) }

        if (jaccardIndex < 0.02 && !containsAllRawNouns) {
            Log.w("NativeRuleCritic", "Audit FAILED: Low Jaccard similarity without content preserve.")
            return@withContext false
        }

        return@withContext true
    }
}
