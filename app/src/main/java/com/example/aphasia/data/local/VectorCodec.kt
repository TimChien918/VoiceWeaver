package com.example.aphasia.data.local

import com.example.aphasia.domain.model.FeatureSequence
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * FloatArray ↔ ByteArray 的二進位編解碼。
 *
 * 沒有用 JSON：一段 4 秒錄音的 MFCC 是 400 幀 × 24 維 = 9,600 個 float。
 * 二進位 38 KB，JSON 約 94 KB（實測 2.5 倍，見 PersistenceTest）。
 * 但主要理由是第二個：JSON 每次讀取都要跑一次字串剖析，
 * 而樣板比對是熱路徑——每次發聲都要把所有樣板讀出來。
 *
 * 位元組序固定為 little-endian，不跟隨平台預設：這些 blob 會隨備份／匯出跨裝置移動。
 */
object VectorCodec {

    private const val MAGIC_SEQUENCE = 0x56575351   // "VWSQ"
    private const val MAGIC_BUNDLE = 0x56574255     // "VWBU"

    // ── 單一向量 ────────────────────────────────────────────────────────

    fun encode(vector: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        vector.forEach { buf.putFloat(it) }
        return buf.array()
    }

    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.size % 4 == 0) { "Vector blob length must be a multiple of 4, got ${bytes.size}" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buf.getFloat() }
    }

    // ── 特徵序列（一次錄音）────────────────────────────────────────────

    /** 格式：magic | frameCount | dimension | float32 × frameCount × dimension */
    fun encodeSequence(sequence: FeatureSequence): ByteArray {
        val frames = sequence.frames
        val dim = frames.firstOrNull()?.size ?: 0
        require(frames.all { it.size == dim }) { "Ragged feature sequence cannot be encoded" }

        val buf = ByteBuffer.allocate(12 + frames.size * dim * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MAGIC_SEQUENCE).putInt(frames.size).putInt(dim)
        frames.forEach { frame -> frame.forEach { buf.putFloat(it) } }
        return buf.array()
    }

    fun decodeSequence(bytes: ByteArray): FeatureSequence {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12 && buf.getInt() == MAGIC_SEQUENCE) { "Not a feature-sequence blob" }
        val frameCount = buf.getInt()
        val dim = buf.getInt()
        require(frameCount >= 0 && dim >= 0) { "Corrupt header: frames=$frameCount dim=$dim" }
        require(bytes.size == 12 + frameCount * dim * 4) {
            "Blob size ${bytes.size} does not match header ${frameCount}×$dim"
        }
        return FeatureSequence(Array(frameCount) { FloatArray(dim) { buf.getFloat() } }, dim)
    }

    // ── 多次登錄錄音打包成一欄 ──────────────────────────────────────────

    /**
     * 把一個樣板的全部登錄錄音包成單一 blob。
     *
     * 不拆成子表：exemplars 永遠是整組讀寫的（比對要全部、刪除要全部），
     * 拆表只會多一次 join 而拿不到任何查詢彈性。
     */
    fun encodeBundle(sequences: List<FeatureSequence>): ByteArray {
        val parts = sequences.map { encodeSequence(it) }
        val total = 8 + parts.sumOf { 4 + it.size }
        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MAGIC_BUNDLE).putInt(parts.size)
        parts.forEach { buf.putInt(it.size).put(it) }
        return buf.array()
    }

    fun decodeBundle(bytes: ByteArray): List<FeatureSequence> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 8 && buf.getInt() == MAGIC_BUNDLE) { "Not an exemplar-bundle blob" }
        val count = buf.getInt()
        require(count in 0..MAX_BUNDLE_ENTRIES) { "Implausible exemplar count: $count" }
        return List(count) {
            val len = buf.getInt()
            require(len in 0..buf.remaining()) { "Corrupt bundle entry length: $len" }
            val part = ByteArray(len)
            buf.get(part)
            decodeSequence(part)
        }
    }

    // ── 向量檢索用的運算 ────────────────────────────────────────────────

    /**
     * L2 正規化。寫入時就做掉，檢索時的 cosine 相似度就退化成單純的內積——
     * 省掉每次查詢對每個向量重算 norm。
     */
    fun l2Normalize(vector: FloatArray): FloatArray {
        var sum = 0f
        for (v in vector) sum += v * v
        val norm = sqrt(sum)
        if (norm < 1e-8f) return vector.copyOf()   // 零向量無法正規化，原樣回傳
        return FloatArray(vector.size) { vector[it] / norm }
    }

    /** 兩個已正規化向量的內積 = cosine 相似度。 */
    fun dot(a: FloatArray, b: FloatArray): Float {
        // **維度不符回 0，不是只比共同前綴。**
        // 比前綴會算出一個看起來很合理的相似度，於是換過編碼器版本之後
        // 檢索會回傳「有排序、但其實不對」的結果——比回傳空的更難發現。
        // 0 代表「不相似」，是這裡唯一安全的答案。
        if (a.size != b.size) return 0f
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    private const val MAX_BUNDLE_ENTRIES = 64
}
