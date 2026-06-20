package com.example.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Task 5.2: 本地端發音準確度評估器。
 *
 * 以 Levenshtein 編輯距離比對「目標句」與跟讀辨識內容（由已整合的 Vosk 取得），
 * 等比換算為 0~100 復健分數。純 Kotlin、零模型、零 OOM 風險，於 Dispatchers.Default 執行。
 *
 * 註：規格原寫 Faster-Whisper 音素對齊，但該函式庫無 Android 原生版；
 *     本評估器只需 (target, recognized) 兩個字串，recognized 由 Vosk 提供即可，
 *     完全離線並符合 Task 5.2 的介面規格。
 */
object PronunciationEvaluator {

    /**
     * @param target     復健目標字/詞/句
     * @param recognized Vosk 辨識到的患者跟讀內容
     * @return 0~100 的發音相似度分數
     */
    suspend fun evaluatePronunciation(target: String, recognized: String): Int =
        withContext(Dispatchers.Default) {
            val t = normalize(target)
            val r = normalize(recognized)
            if (t.isEmpty() || r.isEmpty()) return@withContext 0

            val dist = levenshtein(t, r)
            val maxLen = maxOf(t.length, r.length)
            ((1.0 - dist.toDouble() / maxLen) * 100).toInt().coerceIn(0, 100)
        }

    /** 移除標點與空白（中文字屬於 Letter，會保留），使比對公平 */
    private fun normalize(s: String): String = s.filter { it.isLetterOrDigit() }

    /** 兩列滾動陣列版 Levenshtein，O(n) 記憶體 */
    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m

        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)
        for (i in 1..m) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[n]
    }
}
