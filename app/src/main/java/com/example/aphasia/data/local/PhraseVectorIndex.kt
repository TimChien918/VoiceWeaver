package com.example.aphasia.data.local

import java.util.concurrent.atomic.AtomicReference

/**
 * 記憶體內的向量索引，暴力比對取 Top-K。
 *
 * 沒有用 HNSW／ObjectBox：實際規模是「數百～數千句取 Top-5」。
 * 1,000 筆 × 256 維 = 256k 次乘加，在手機上是次毫秒等級，
 * 而近似最近鄰要付出索引建置、額外相依、以及召回率不再是 100% 的代價。
 * 這個量級用暴力法不只夠快，還更準。
 *
 * 向量在寫入時就 L2 正規化過（見 [VectorCodec.l2Normalize]），
 * 所以這裡的內積直接就是 cosine 相似度。
 *
 * 執行緒安全靠不可變快照 + [AtomicReference] 換頁：
 * 檢索永遠讀到一個一致的版本，重建索引不需要鎖住查詢。
 */
class PhraseVectorIndex {

    private class Snapshot(val ids: LongArray, val vectors: Array<FloatArray>, val version: Int)

    private val snapshot = AtomicReference(Snapshot(LongArray(0), emptyArray(), 0))

    val size: Int get() = snapshot.get().ids.size

    /** 目前索引裡的向量是哪個編碼器版本產生的。 */
    val embeddingVersion: Int get() = snapshot.get().version

    /**
     * 用資料庫裡的向量重建索引。
     *
     * 版本不符的直接略過而不是照用——用舊編碼器的向量跟新編碼器的查詢做內積
     * 會得到看似合理但完全無意義的分數，比沒有結果更糟。
     */
    fun rebuild(rows: List<EmbeddingRow>, expectedVersion: Int) {
        val usable = rows.filter { it.embeddingVersion == expectedVersion }
        val ids = LongArray(usable.size)
        val vectors = Array(usable.size) { i ->
            ids[i] = usable[i].id
            VectorCodec.decode(usable[i].embeddingBlob)
        }
        snapshot.set(Snapshot(ids, vectors, expectedVersion))
    }

    /**
     * @param query 已 L2 正規化的查詢向量
     * @param minSimilarity 低於此值不回傳。預設 0 表示不過濾——
     *   但呼叫端通常該給一個下限：把毫不相關的句子塞進 LLM 的上下文，
     *   只會讓它把使用者根本沒提到的東西寫進重組結果。
     * @return (id, similarity)，相似度由高到低
     */
    fun search(query: FloatArray, topK: Int = 5, minSimilarity: Float = 0f): List<Pair<Long, Float>> {
        val snap = snapshot.get()
        if (snap.ids.isEmpty() || topK <= 0) return emptyList()

        // 只保留 K 個的插入排序。K 通常是 5，全排序 1,000 筆是浪費。
        val bestIds = LongArray(topK)
        val bestScores = FloatArray(topK) { Float.NEGATIVE_INFINITY }
        var filled = 0

        for (i in snap.ids.indices) {
            val score = VectorCodec.dot(query, snap.vectors[i])
            if (score < minSimilarity) continue
            if (filled == topK && score <= bestScores[topK - 1]) continue

            var pos = minOf(filled, topK - 1)
            while (pos > 0 && bestScores[pos - 1] < score) {
                bestScores[pos] = bestScores[pos - 1]
                bestIds[pos] = bestIds[pos - 1]
                pos--
            }
            bestScores[pos] = score
            bestIds[pos] = snap.ids[i]
            if (filled < topK) filled++
        }
        return (0 until filled).map { bestIds[it] to bestScores[it] }
    }

    fun clear() = snapshot.set(Snapshot(LongArray(0), emptyArray(), 0))
}
