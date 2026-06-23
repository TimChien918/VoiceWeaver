package com.example.ai

import android.content.Context

/**
 * 繁體中文 → 簡體中文 轉換器（單字 1:1 對照）。
 *
 * 為什麼需要：GPT-SoVITS 的中文模型多半是**簡體語料**訓練的，
 * 其中文 g2p（字 → 拼音）字典只認簡體字。App UI 與重組句是**繁體**（台灣使用者），
 * 直接把「這是語音測試」送進去，繁體字不在字典 → g2p 退化成逐字/錯誤發音 →
 * 聽起來像雜音、節奏錯亂、咬字模糊。實測同句繁體輸出比簡體長 13%，即 g2p 異常的徵兆。
 *
 * 解法：**送進 TTS 前**把繁體轉簡體（畫面與歷史仍存繁體，只有送模型那一刻轉）。
 *
 * 對照表來源：OpenCC `t2s` 產生的 BMP 範圍單字 1:1 對照，打包成 `assets/t2s_map.txt`
 * （格式：繁簡繁簡…交錯字元）。只放 BMP（≤U+FFFF）字元，確保每字剛好 1 個 UTF-16 char。
 */
object ChineseConverter {

    @Volatile private var map: Map<Char, Char>? = null

    /** 第一次呼叫時從 asset 載入對照表（lazy + 執行緒安全）。載入失敗回空表 = 原樣輸出。 */
    private fun ensureLoaded(context: Context): Map<Char, Char> {
        map?.let { return it }
        synchronized(this) {
            map?.let { return it }
            val loaded = runCatching {
                val packed = context.assets.open("t2s_map.txt").use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                HashMap<Char, Char>(packed.length).apply {
                    var i = 0
                    while (i + 1 < packed.length) {
                        put(packed[i], packed[i + 1])
                        i += 2
                    }
                }
            }.getOrDefault(emptyMap())
            map = loaded
            return loaded
        }
    }

    /** 把繁體字逐字轉簡體；非中文字（標點、英數、emoji）原樣保留。 */
    fun toSimplified(context: Context, text: String): String {
        if (text.isEmpty()) return text
        val m = ensureLoaded(context)
        if (m.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (ch in text) sb.append(m[ch] ?: ch)
        return sb.toString()
    }
}
