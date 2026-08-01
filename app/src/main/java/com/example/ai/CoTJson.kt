package com.example.ai

/**
 * LLM 回傳 JSON 的搶救工具。
 *
 * 為什麼需要：重組用的 prompt 要模型輸出
 * {"reasoning":…,"reconstructed":…,"confidence":…}，而 reasoning 排在最前面。
 * 話多的模型會在 reasoning 就把 maxTokens 燒完，回傳變成沒有結尾的半截 JSON。
 * 這種字串「不是空的」，過去會被當成重組結果直接顯示，病人畫面就出現
 * {"reasoning":"碎詞與頭痛有關… 這種東西，還被當成可以唸出來的話。
 */

/**
 * 從被截斷／格式不完整的 JSON 裡撈出 reconstructed 的內容。
 *
 * 收尾的引號可能根本還沒生出來，所以結尾不強制匹配 " ——
 * 只要 reconstructed 已經開始寫，就能把已經寫出來的部分救回來。
 * 撈不到回 null，讓呼叫端決定要換供應商還是走關鍵字 fallback，
 * 一律不要把原始 JSON 當句子用。
 */
internal fun salvageReconstructed(raw: String): String? {
    // 兩種格式都要撈：新版 candidates:[{"text":…}]，舊版 {"reconstructed":…}
    val m = Regex("\"(?:reconstructed|text)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)").find(raw) ?: return null
    return m.groupValues[1]
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .trim()
        .ifBlank { null }
}

/** 這段文字看起來是模型吐的 JSON，而不是可以唸給人聽的句子。 */
internal fun looksLikeRawJson(text: String): Boolean =
    text.startsWith("{") || text.contains("\"reasoning\"") ||
    text.contains("\"reconstructed\"") || text.contains("\"candidates\"")
