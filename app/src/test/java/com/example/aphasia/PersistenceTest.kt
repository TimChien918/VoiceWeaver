package com.example.aphasia

import com.example.aphasia.data.local.EmbeddingRow
import com.example.aphasia.data.local.HashingTextEmbedder
import com.example.aphasia.data.local.PhraseVectorIndex
import com.example.aphasia.data.local.VectorCodec
import com.example.aphasia.domain.model.FeatureSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Phase 3 的儲存與檢索。純 JVM，不碰 Android。
 *
 * 編解碼的往返測試不是形式：這些 blob 存的是使用者花力氣錄出來的樣板，
 * 編碼有一個位元組錯位就會安靜地污染所有距離，而且沒有任何錯誤訊息。
 */
class PersistenceTest {

    private val rng = Random(20260804)

    private fun randomSequence(frames: Int, dim: Int) =
        FeatureSequence(Array(frames) { FloatArray(dim) { rng.nextFloat() * 20f - 10f } }, dim)

    // ── 編解碼 ──────────────────────────────────────────────────────────

    @Test
    fun `向量往返後完全相等`() {
        val v = FloatArray(256) { rng.nextFloat() * 2f - 1f }
        assertTrue(v.contentEquals(VectorCodec.decode(VectorCodec.encode(v))))
    }

    @Test
    fun `特徵序列往返後逐格相等`() {
        val seq = randomSequence(400, 24)
        val back = VectorCodec.decodeSequence(VectorCodec.encodeSequence(seq))
        assertEquals(seq.frameCount, back.frameCount)
        assertEquals(24, back.coefficientCount)
        for (i in 0 until seq.frameCount) {
            assertTrue("第 $i 幀不相等", seq.frames[i].contentEquals(back.frames[i]))
        }
    }

    @Test
    fun `多次登錄錄音打包往返後保持順序與長度`() {
        val takes = listOf(randomSequence(46, 24), randomSequence(52, 24), randomSequence(39, 24))
        val back = VectorCodec.decodeBundle(VectorCodec.encodeBundle(takes))
        assertEquals(listOf(46, 52, 39), back.map { it.frameCount })
        for (i in takes.indices) {
            assertTrue(takes[i].frames[0].contentEquals(back[i].frames[0]))
        }
    }

    @Test
    fun `二進位比 JSON 省一半以上`() {
        // 4 秒錄音 = 400 幀 × 24 維。這是選擇二進位而非 JSON 的實際依據。
        // 實測 38 KB vs 94 KB ≈ 2.5 倍——比直覺小，因為 float 的十進位表示
        // 平均只有 10 個字元左右。真正的理由其實是第二個：JSON 每次讀取都要
        // 跑一次字串剖析，而樣板比對是熱路徑。
        val seq = randomSequence(400, 24)
        val binary = VectorCodec.encodeSequence(seq).size
        val jsonish = seq.frames.sumOf { f -> f.sumOf { it.toString().length + 1 } } + 2
        assertEquals("應為 12 位元組表頭 + 400×24×4", 12 + 400 * 24 * 4, binary)
        assertTrue("二進位應小於 JSON 的一半：binary=$binary json≈$jsonish", binary * 2 < jsonish)
    }

    @Test
    fun `壞掉的 blob 會被明確拒絕而不是安靜地讀出垃圾`() {
        val good = VectorCodec.encodeSequence(randomSequence(10, 24))

        // 表頭被改壞
        val badMagic = good.copyOf().also { it[0] = 0 }
        assertTrue(runCatching { VectorCodec.decodeSequence(badMagic) }.isFailure)

        // 長度對不上表頭
        val truncated = good.copyOf(good.size - 40)
        assertTrue(runCatching { VectorCodec.decodeSequence(truncated) }.isFailure)

        // 不是 4 的倍數的向量 blob
        assertTrue(runCatching { VectorCodec.decode(ByteArray(7)) }.isFailure)
    }

    @Test
    fun `L2 正規化之後內積就是 cosine`() {
        val a = VectorCodec.l2Normalize(FloatArray(64) { rng.nextFloat() })
        assertEquals(1f, VectorCodec.dot(a, a), 1e-4f)

        // 零向量不該產生 NaN——空字串或全是標點的句子會走到這裡
        val zero = VectorCodec.l2Normalize(FloatArray(64))
        assertTrue(zero.all { it == 0f })
        assertEquals(0f, VectorCodec.dot(zero, a), 0f)
    }

    // ── 文字編碼器 ──────────────────────────────────────────────────────

    @Test
    fun `同一句話永遠得到同一個向量`() {
        val e = HashingTextEmbedder()
        assertTrue(e.embed("I need water").contentEquals(e.embed("I need water")))
        // 大小寫與標點不該改變結果
        assertTrue(e.embed("I need water").contentEquals(e.embed("i, need!! WATER?")))
    }

    @Test
    fun `共用字詞的句子比不相干的句子接近`() {
        val e = HashingTextEmbedder()
        val base = e.embed("I would like some water")
        val related = VectorCodec.dot(base, e.embed("I need water please"))
        val unrelated = VectorCodec.dot(base, e.embed("Turn off the television"))
        assertTrue("related=$related unrelated=$unrelated", related > unrelated)
        assertTrue("有共用實詞應該有明顯相似度，實得 $related", related > 0.25f)
    }

    @Test
    fun `字尾變化仍然靠得近`() {
        // want / wanted / wanting 在失語症使用者的輸入裡很常混用
        val e = HashingTextEmbedder()
        val a = e.embed("I want the bathroom")
        val inflected = VectorCodec.dot(a, e.embed("I wanted the bathroom"))
        val different = VectorCodec.dot(a, e.embed("I want the doctor"))
        assertTrue("字尾變化 $inflected 應高於換掉名詞 $different", inflected > different)
    }

    @Test
    fun `符號決定正負讓雜湊撞擊不會系統性放大相似度`() {
        val e = HashingTextEmbedder()
        // 兩句完全無關的話，相似度應該接近 0 而不是偏正
        val scores = listOf(
            "the weather is cold" to "call my daughter",
            "I am tired" to "open the window",
            "hospital appointment tomorrow" to "chicken soup",
        ).map { (a, b) -> VectorCodec.dot(e.embed(a), e.embed(b)) }
        assertTrue("不相關的句子相似度應接近 0，實得 $scores", scores.all { it < 0.2f })
    }

    @Test
    fun `中文逐字切分不會整句擠成一個單元`() {
        val e = HashingTextEmbedder()
        val a = e.embed("我要喝水")
        val related = VectorCodec.dot(a, e.embed("我要水"))
        val unrelated = VectorCodec.dot(a, e.embed("關掉電視"))
        assertTrue("related=$related unrelated=$unrelated", related > unrelated)
    }

    @Test
    fun `空字串不會爆炸`() {
        val e = HashingTextEmbedder()
        assertEquals(256, e.embed("").size)
        assertEquals(256, e.embed("   !!!  ").size)
    }

    // ── 向量索引 ────────────────────────────────────────────────────────

    private fun rows(embedder: HashingTextEmbedder, vararg texts: String, version: Int = 1) =
        texts.mapIndexed { i, t ->
            EmbeddingRow((i + 1).toLong(), VectorCodec.encode(embedder.embed(t)), version)
        }

    @Test
    fun `Top-K 依相似度由高到低排序`() {
        val e = HashingTextEmbedder()
        val index = PhraseVectorIndex()
        index.rebuild(
            rows(e, "I need water", "I need my medication", "Turn on the light", "Water please"),
            expectedVersion = 1,
        )
        val hits = index.search(e.embed("water"), topK = 3)
        assertTrue("應有結果", hits.isNotEmpty())
        assertTrue("分數必須遞減：${hits.map { it.second }}",
            hits.zipWithNext().all { (a, b) -> a.second >= b.second })
        assertTrue("最相近的應是含 water 的兩句，實得 ${hits.map { it.first }}",
            hits.first().first in listOf(1L, 4L))
    }

    @Test
    fun `Top-K 真的只回 K 筆`() {
        val e = HashingTextEmbedder()
        val index = PhraseVectorIndex()
        index.rebuild(rows(e, *Array(50) { "phrase number $it about water" }), expectedVersion = 1)
        assertEquals(5, index.search(e.embed("water"), topK = 5).size)
        assertEquals(1, index.search(e.embed("water"), topK = 1).size)
        assertEquals(0, index.search(e.embed("water"), topK = 0).size)
    }

    @Test
    fun `相似度下限擋掉不相干的句子`() {
        val e = HashingTextEmbedder()
        val index = PhraseVectorIndex()
        index.rebuild(rows(e, "Turn on the television", "Call my daughter"), expectedVersion = 1)
        // 沒有下限時會硬回傳最不糟的那個——那正是把幻覺材料餵給 LLM 的來源
        assertTrue("沒有下限應該有結果", index.search(e.embed("water"), 5, 0f).isNotEmpty())
        assertEquals("有下限應該一筆都不回", 0, index.search(e.embed("water"), 5, 0.25f).size)
    }

    @Test
    fun `編碼器版本不符的向量會被略過而不是照用`() {
        // 用舊編碼器的向量跟新編碼器的查詢做內積，會得到看似合理但無意義的分數。
        val e = HashingTextEmbedder()
        val index = PhraseVectorIndex()
        index.rebuild(rows(e, "I need water", version = 1), expectedVersion = 2)
        assertEquals(0, index.size)
        assertEquals(0, index.search(e.embed("water"), 5).size)
    }

    @Test
    fun `一千筆的檢索夠快`() {
        val e = HashingTextEmbedder()
        val index = PhraseVectorIndex()
        index.rebuild(
            (1..1000).map { EmbeddingRow(it.toLong(), VectorCodec.encode(e.embed("phrase $it")), 1) },
            expectedVersion = 1,
        )
        val q = e.embed("phrase 500")
        repeat(50) { index.search(q, 5) }                      // 暖機
        val t0 = System.nanoTime()
        repeat(200) { index.search(q, 5) }
        val perQueryMs = (System.nanoTime() - t0) / 200 / 1_000_000.0
        // JVM 上遠低於 1ms；手機慢個幾倍仍在可接受範圍。
        // 這一項的目的是在有人換成 O(n²) 寫法時把它擋下來。
        assertTrue("每次查詢 ${perQueryMs}ms，超出預期", perQueryMs < 5.0)
    }

    @Test
    fun `空索引查詢回空而不是丟例外`() {
        val index = PhraseVectorIndex()
        assertEquals(0, index.search(FloatArray(256) { 1f }, 5).size)
        index.rebuild(emptyList(), 1)
        assertEquals(0, index.size)
    }

    @Test
    fun `不同的句子不會產生完全相同的向量`() {
        val e = HashingTextEmbedder()
        assertNotEquals(
            e.embed("I need water").toList(),
            e.embed("I need help").toList(),
        )
    }
}
