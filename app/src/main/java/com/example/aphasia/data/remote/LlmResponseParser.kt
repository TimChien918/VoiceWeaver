package com.example.aphasia.data.remote

import com.example.aphasia.domain.model.ReconstructedSentence
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi

/**
 * 解析模型回來的 JSON。
 *
 * 兩個非協商的原則：
 *
 * 1. **解析失敗就是失敗，絕不把原始文字當句子用。** 曾經發生過模型的
 *    reasoning 太長把 token 燒完、`reconstructed` 根本還沒開始寫，
 *    而截斷的 JSON 被直接唸給病人聽。撈不到就回失敗，讓上層換供應商或走備援。
 * 2. **模型愛在 JSON 前後加客套話與 markdown 圍欄。** 這不是模型壞掉，
 *    是常態，所以這裡負責清掉，而不是把它當成錯誤。
 */
object LlmResponseParser {

    @JsonClass(generateAdapter = true)
    data class CandidateDto(
        val text: String? = null,
        val confidence: Double? = null,
        val rationale: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class CandidatesDto(val candidates: List<CandidateDto>? = null)

    private val moshi = Moshi.Builder().build()
    private val candidatesAdapter by lazy { moshi.adapter(CandidatesDto::class.java) }
    private val singleAdapter by lazy { moshi.adapter(CandidateDto::class.java) }

    /** [AphasiaPrompts.RECONSTRUCT_SYSTEM] 的輸出。 */
    fun parseCandidates(raw: String): Result<List<ReconstructedSentence>> = runCatching {
        val json = extractJsonObject(raw) ?: error("No JSON object found in model response")
        val dto = candidatesAdapter.fromJson(json) ?: error("Model response was not a JSON object")
        // 空清單是合法答案——prompt 明確允許模型說「碎詞太稀疏，我不猜」。
        // 這跟解析失敗必須分開，否則上層會白白換一輪供應商。
        (dto.candidates ?: emptyList()).mapNotNull { it.toDomainOrNull() }
    }

    /** [AphasiaPrompts.GRAMMAR_SYSTEM] 與 [AphasiaPrompts.POLITENESS_SYSTEM] 的輸出。 */
    fun parseSingle(raw: String): Result<ReconstructedSentence> = runCatching {
        val json = extractJsonObject(raw) ?: error("No JSON object found in model response")
        val dto = singleAdapter.fromJson(json) ?: error("Model response was not a JSON object")
        dto.toDomainOrNull() ?: error("Model returned an empty sentence")
    }

    private fun CandidateDto.toDomainOrNull(): ReconstructedSentence? {
        val cleaned = text?.trim()?.replace(WHITESPACE, " ").orEmpty()
        if (cleaned.isBlank()) return null
        // 模型偶爾會把整個 JSON 塞進 text 欄位。唸出來會是一串括號和引號。
        if (looksLikeRawJson(cleaned)) return null
        return ReconstructedSentence(
            text = cleaned,
            // 沒給信心度時當成「不確定」而不是「確定」。少了這個預設，
            // 缺欄位的回應會拿到 0.0 或 1.0，兩個都是錯的訊號。
            confidence = ((confidence ?: 50.0) / 100.0).coerceIn(0.0, 1.0).toFloat(),
            rationale = rationale?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * 從一坨文字裡撈出第一個完整的 JSON 物件。
     *
     * 用括號配對而不是正規表示式：字串裡合法出現的 `{` `}`（例如 rationale 引用了
     * 使用者的話）會讓貪婪／非貪婪的正規表示式都抓錯邊界。
     */
    fun extractJsonObject(raw: String): String? {
        val text = raw.trim().removeSurrounding("```json", "```").trim()
            .removeSurrounding("```", "```").trim()
        val start = text.indexOf('{')
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        // 走到底還沒配對完 = 被截斷。回 null，讓上層知道這次不可用。
        return null
    }

    /** 看起來像沒解析成功的 JSON 殘骸，不該當成句子播出去。 */
    fun looksLikeRawJson(text: String): Boolean {
        val t = text.trim()
        if (t.startsWith("{") || t.startsWith("[")) return true
        return t.contains("\":") && (t.contains("{") || t.contains("}"))
    }

    private val WHITESPACE = Regex("\\s+")

}
