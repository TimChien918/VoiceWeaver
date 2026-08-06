package com.example.aphasia

import com.example.aphasia.data.acoustic.DtwMatcher
import com.example.aphasia.data.acoustic.MfccExtractor
import com.example.aphasia.domain.model.FeatureSequence
import com.example.aphasia.domain.model.Utterance
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * 句庫的關鍵字為什麼一定要是「連續的一段」。
 *
 * 直覺會想把關鍵字寫成挑出來的實詞（"need go bathroom"），
 * 因為那看起來抓住了重點、又不必唸虛詞。但比對走的是 subsequence DTW：
 * 它能拉伸時間軸，**沒辦法跳過中間插進來的音節**。使用者實際講的是
 * 「I need to go to the bathroom」，中間的 to／to the 沒有地方可以擺，
 * 只能硬塞進相鄰樣板幀的伸縮裡，距離因此爆掉。
 *
 * 這一條沒有測試釘住的話，日後很容易有人「順手把關鍵字改精簡一點」，
 * 而症狀是「講了一樣的話卻沒反應」——從程式碼上完全看不出原因。
 */
class KeywordContiguityTest {

    private val sr = Utterance.SAMPLE_RATE_HZ
    private val extractor = MfccExtractor()

    private fun seg(f1: Double, f2: Double, ms: Int): FloatArray {
        val n = sr * ms / 1000
        return FloatArray(n) { i ->
            val t = i.toDouble() / sr
            ((1.0 * sin(2 * PI * 120 * t) + 0.8 * sin(2 * PI * f1 * t) +
                0.5 * sin(2 * PI * f2 * t)) / 2.3 * 0.5f).toFloat()
        }
    }

    private fun utt(vararg parts: FloatArray): Utterance {
        val pad = FloatArray(sr / 10)
        val all = listOf(pad) + parts.toList() + listOf(pad)
        val out = FloatArray(all.sumOf { it.size })
        var o = 0
        for (p in all) { p.copyInto(out, o); o += p.size }
        return Utterance(out, sr)
    }

    // 把「I need to go to the bathroom」拆成音節段
    private val need = seg(700.0, 1200.0, 260)
    private val to = seg(500.0, 1500.0, 140)
    private val go = seg(450.0, 900.0, 220)
    private val the = seg(520.0, 1600.0, 130)
    private val bath = seg(350.0, 2400.0, 380)
    private val filler = seg(600.0, 1000.0, 300)

    /** 實際說出口的整句：I need TO go TO THE bathroom */
    private val spoken by lazy {
        extractor.extract(utt(filler, need, to, go, to, the, bath, filler))
    }

    private fun distance(template: FeatureSequence) =
        DtwMatcher.subsequenceDistance(template, spoken)?.distance ?: Float.MAX_VALUE

    @Test
    fun `挑實詞跳過虛詞的關鍵字會爛掉`() {
        val skipping = extractor.extract(utt(need, go, bath))          // need go bathroom
        val contiguous = extractor.extract(utt(go, to, the, bath))     // go to the bathroom

        val dSkip = distance(skipping)
        val dCont = distance(contiguous)

        assertTrue(
            "跳虛詞的距離 $dSkip 應該遠大於連續的 $dCont（實測約 7.09 vs 0.85）",
            dSkip > dCont * 3,
        )
    }

    @Test
    fun `跳虛詞的關鍵字幾乎沒有安全邊界`() {
        // 真正致命的不是「距離變大」，而是它跟「不相干的話」拉不開。
        // 距離 7 對上不相干的 12，中間根本放不下一個門檻。
        val skipping = extractor.extract(utt(need, go, bath))
        val other = extractor.extract(utt(filler, seg(300.0, 2900.0, 400), filler))

        val hit = distance(skipping)
        val miss = DtwMatcher.subsequenceDistance(skipping, other)?.distance ?: Float.MAX_VALUE
        assertTrue(
            "跳虛詞：命中 $hit vs 不相干 $miss，倍率不到 3 倍就沒有可用的門檻",
            miss / hit < 3f,
        )
    }

    @Test
    fun `連續的關鍵字有很大的安全邊界`() {
        val contiguous = extractor.extract(utt(go, to, the, bath))
        val other = extractor.extract(utt(filler, seg(300.0, 2900.0, 400), filler))

        val hit = distance(contiguous)
        val miss = DtwMatcher.subsequenceDistance(contiguous, other)?.distance ?: Float.MAX_VALUE
        assertTrue("連續：命中 $hit vs 不相干 $miss，應該差一個數量級", miss / hit > 8f)
    }

    @Test
    fun `連續的前提下越長越好認`() {
        // 這是「關鍵字寫長一點」真正成立的理由：多幾個字＝多幾個對得上的地方。
        // 但前提是連續——不連續的話越長只會越糟。
        val long = distance(extractor.extract(utt(go, to, the, bath)))   // go to the bathroom
        val mid = distance(extractor.extract(utt(the, bath)))            // the bathroom
        val short = distance(extractor.extract(utt(bath)))               // bathroom
        assertTrue("長的應該最好認：$long < $mid < $short", long < mid && mid < short)
    }
}
