package com.example.aphasia.data.local

/**
 * 把一句話變成向量，供裝置端檢索使用。
 *
 * 抽成介面是為了讓「用什麼模型」這件事可以換掉而不動到儲存與檢索邏輯。
 * [version] 會存進資料庫，換實作時就能認出哪些向量需要重算。
 */
interface TextEmbedder {
    val dimension: Int
    /** 換實作或換參數時必須遞增，否則舊向量會被當成有效的繼續用 */
    val version: Int
    /** 回傳已 L2 正規化的向量，讓檢索端只要做內積 */
    fun embed(text: String): FloatArray
}

/**
 * 雜湊 n-gram 編碼器：不需要模型檔、不需要網路、完全確定性。
 *
 * **這是詞面相似度，不是語意相似度。** 它抓得到
 * "I want water" ↔ "I need water"（共用 token），
 * 抓不到 "I'm thirsty" ↔ "water"（沒有共同字面）。
 *
 * 之所以先做這個而不是直接上嵌入模型：
 * 它讓儲存、索引、Top-K 這整條路現在就能跑、能測、能量測效能，
 * 而換成真正的嵌入模型時只要換掉這個類別。對個人語句庫這個場景，
 * 詞面相似度本身也已經有用——使用者自己加的句子本來就會重複用字。
 *
 * 用 hashing trick（雜湊到固定維度）而不是建詞表：
 * 詞表要隨語句庫成長、要處理未見詞、還要跟著資料庫一起 migrate，
 * 對這個規模不值得。
 */
class HashingTextEmbedder(
    override val dimension: Int = 256,
    private val charNGram: Int = 3,
) : TextEmbedder {

    override val version: Int = 1

    override fun embed(text: String): FloatArray {
        val vector = FloatArray(dimension)
        val normalized = normalize(text)
        if (normalized.isBlank()) return vector

        val words = normalized.split(' ').filter { it.isNotEmpty() }

        // 詞單元：權重較高，因為完整的詞比字元片段更有辨別力
        for (word in words) accumulate(vector, "w:$word", weight = 2f)

        // 字元 n-gram：讓拼字差一點、字尾變化（want/wanted）仍然靠得近。
        // 前後補空白，讓詞首詞尾的 n-gram 也被涵蓋。
        for (word in words) {
            val padded = " $word "
            if (padded.length < charNGram) continue
            for (i in 0..padded.length - charNGram) {
                accumulate(vector, "c:${padded.substring(i, i + charNGram)}", weight = 1f)
            }
        }
        return VectorCodec.l2Normalize(vector)
    }

    /**
     * 累加到雜湊桶。第二個雜湊決定正負號——沒有這一步，
     * 不同特徵撞到同一桶時只會互相加強，撞擊會系統性地放大相似度。
     */
    private fun accumulate(vector: FloatArray, feature: String, weight: Float) {
        val h = fnv1a(feature)
        val bucket = ((h ushr 1) % dimension).toInt()
        val sign = if (h and 1L == 0L) 1f else -1f
        vector[bucket] += sign * weight
    }

    private fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        var lastWasSpace = true
        for (ch in text.lowercase()) {
            when {
                ch.isLetterOrDigit() -> { sb.append(ch); lastWasSpace = false }
                // CJK 沒有空白分詞，逐字當成獨立單元
                ch.code in 0x4E00..0x9FFF -> {
                    if (!lastWasSpace) sb.append(' ')
                    sb.append(ch).append(' ')
                    lastWasSpace = true
                }
                !lastWasSpace -> { sb.append(' '); lastWasSpace = true }
            }
        }
        return sb.toString().trim()
    }

    private fun fnv1a(s: String): Long {
        var hash = -0x340d631b7bdddcdbL   // FNV-1a 64-bit offset basis
        for (ch in s) {
            hash = hash xor ch.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash and Long.MAX_VALUE
    }
}
