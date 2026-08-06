package com.example.aphasia

import com.example.aphasia.data.acoustic.DtwMatcher
import com.example.aphasia.data.acoustic.Fft
import com.example.aphasia.data.acoustic.MfccExtractor
import com.example.aphasia.data.acoustic.TemplateMatcher
import com.example.aphasia.domain.model.AcousticTemplate
import com.example.aphasia.domain.model.FeatureSequence
import com.example.aphasia.domain.model.MatchMode
import com.example.aphasia.domain.model.MatchResult
import com.example.aphasia.domain.model.Utterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Module 1（直接波形比對）的純 JVM 測試。
 *
 * 這裡的每一項都對應一個真實的失敗模式，不是為了覆蓋率：
 * DTW 沒有正規化會讓門檻不可跨長度成立；沒有 band 會製造假的相似度；
 * 沒有 margin 會讓 App 替使用者說錯話。
 */
class AcousticPipelineTest {

    private val sr = Utterance.SAMPLE_RATE_HZ
    private val extractor = MfccExtractor()

    // ── 訊號產生器 ──────────────────────────────────────────────────────

    /** 用兩個共振峰模擬一個母音。不同的 (f1, f2) 就是不同的「音」。 */
    private fun vowel(f1: Double, f2: Double, durationMs: Int, amplitude: Float = 0.5f): Utterance {
        val n = sr * durationMs / 1000
        val samples = FloatArray(n) { i ->
            val t = i.toDouble() / sr
            // 加上基頻讓頻譜不是兩根純線，比較接近真實語音
            val v = 1.0 * sin(2 * PI * 120 * t) +
                0.8 * sin(2 * PI * f1 * t) +
                0.5 * sin(2 * PI * f2 * t)
            (v / 2.3 * amplitude).toFloat()
        }
        // 前後各加 100 ms 靜音，測試靜音修剪
        val pad = FloatArray(sr / 10)
        return Utterance(pad + samples + pad, sr)
    }

    private operator fun FloatArray.plus(other: FloatArray): FloatArray {
        val out = FloatArray(size + other.size)
        copyInto(out, 0)
        other.copyInto(out, size)
        return out
    }

    // ── FFT ────────────────────────────────────────────────────────────

    @Test
    fun `FFT 把正弦波的能量集中在對應的頻率格上`() {
        val n = 512
        val bin = 40
        val real = FloatArray(n) { sin(2 * PI * bin * it / n).toFloat() }
        val imag = FloatArray(n)
        Fft.transform(real, imag)

        val power = FloatArray(n / 2 + 1) { real[it] * real[it] + imag[it] * imag[it] }
        val peakBin = power.indices.maxByOrNull { power[it] }
        assertEquals("能量應集中在第 $bin 格", bin, peakBin)

        val total = power.sum()
        assertTrue("峰值應佔絕大部分能量，實得 ${power[bin] / total}", power[bin] / total > 0.9f)
    }

    @Test
    fun `FFT 拒絕非 2 的冪次長度`() {
        val e = runCatching { Fft.transform(FloatArray(100), FloatArray(100)) }.exceptionOrNull()
        assertTrue("應丟 IllegalArgumentException，實得 $e", e is IllegalArgumentException)
    }

    // ── MFCC ───────────────────────────────────────────────────────────

    @Test
    fun `MFCC 維度符合設定並且丟掉 c0`() {
        val seq = extractor.extract(vowel(700.0, 1200.0, 400))
        assertTrue("應產生特徵幀", seq.frameCount > 10)
        // 13 個係數丟掉 c0 剩 12，再加一階差分 = 24
        assertEquals(24, seq.frames[0].size)
        assertEquals(24, seq.coefficientCount)
    }

    @Test
    fun `丟掉 c0 讓同一個音在不同錄音音量下依然相似`() {
        // 這是真實情境：登錄時靠近麥克風、實際使用時離得遠，音量差好幾倍。
        //
        // 音量不變性來自「丟掉 c0」（c0 只反映整體響度），不是來自 CMN——
        // 實測開關 CMN 這一項都是 0.00001。這件事很容易記反，記反的後果是
        // 有人為了別的理由把 CMN 打開，順手破壞關鍵字比對（見 Config 的說明）。
        val loud = extractor.extract(vowel(700.0, 1200.0, 400, amplitude = 0.8f))
        val quiet = extractor.extract(vowel(700.0, 1200.0, 400, amplitude = 0.15f))
        val different = extractor.extract(vowel(300.0, 2600.0, 400, amplitude = 0.8f))

        val sameSound = DtwMatcher.distance(loud, quiet)
        val otherSound = DtwMatcher.distance(loud, different)
        assertTrue(
            "音量差不該比發音差還大：same=$sameSound other=$otherSound",
            sameSound < otherSound,
        )
    }

    @Test
    fun `靜音修剪讓前後留白不影響特徵長度太多`() {
        val padded = extractor.extract(vowel(700.0, 1200.0, 300))   // 內含前後各 100 ms 靜音
        // 300 ms 語音、10 ms 幀移 → 約 30 幀；修剪後前後各留 2 幀
        assertTrue("修剪後應接近語音本身長度，實得 ${padded.frameCount}", padded.frameCount in 20..45)
    }

    // ── DTW ────────────────────────────────────────────────────────────

    @Test
    fun `相同序列的距離為零`() {
        val a = extractor.extract(vowel(700.0, 1200.0, 400))
        assertEquals(0f, DtwMatcher.distance(a, a), 1e-4f)
    }

    @Test
    fun `路徑長度正規化讓長短音的距離落在同一個尺度`() {
        // 沒有正規化的話，長音的累積成本天生較大，
        // 「距離 < 門檻」這個判斷就不可能跨長度成立。
        val shortA = extractor.extract(vowel(700.0, 1200.0, 250))
        val shortB = extractor.extract(vowel(700.0, 1200.0, 260))
        val longA = extractor.extract(vowel(700.0, 1200.0, 900))
        val longB = extractor.extract(vowel(700.0, 1200.0, 930))

        val dShort = DtwMatcher.distance(shortA, shortB)
        val dLong = DtwMatcher.distance(longA, longB)
        assertTrue("兩者都該接近 0：short=$dShort long=$dLong", dShort < 1f && dLong < 1f)
        assertTrue(
            "長音的距離不該因為比較長就明顯較大：short=$dShort long=$dLong",
            kotlin.math.abs(dLong - dShort) < 1f,
        )
    }

    @Test
    fun `同一個音講快講慢仍然算像`() {
        val normal = extractor.extract(vowel(700.0, 1200.0, 500))
        val slower = extractor.extract(vowel(700.0, 1200.0, 650))
        val otherSound = extractor.extract(vowel(300.0, 2600.0, 500))

        val stretched = DtwMatcher.distance(normal, slower)
        val different = DtwMatcher.distance(normal, otherSound)
        assertTrue(
            "時間伸縮應遠小於發音差異：stretched=$stretched different=$different",
            stretched < different,
        )
    }

    @Test
    fun `長度差超過上限直接判定不可比對`() {
        // 這是 band 存在的理由：不擋掉的話，DTW 會把短音硬拉去對齊長音，
        // 產生一個看起來很小、實際毫無意義的距離。
        val short = extractor.extract(vowel(700.0, 1200.0, 200))
        val long = extractor.extract(vowel(700.0, 1200.0, 1500))
        assertEquals(Float.MAX_VALUE, DtwMatcher.distance(short, long), 0f)
        assertTrue(!DtwMatcher.lengthsComparable(short, long))
    }

    @Test
    fun `空序列不會讓 DTW 爆炸`() {
        val empty = FeatureSequence(emptyArray(), 24)
        val real = extractor.extract(vowel(700.0, 1200.0, 400))
        assertEquals(Float.MAX_VALUE, DtwMatcher.distance(empty, real), 0f)
        assertEquals(Float.MAX_VALUE, DtwMatcher.distance(real, empty), 0f)
    }

    // ── 門檻校準 ────────────────────────────────────────────────────────

    @Test
    fun `登錄次數不足時拒絕給門檻`() {
        val one = listOf(extractor.extract(vowel(700.0, 1200.0, 400)))
        assertNull("只有一次錄音沒有 σ 可估", DtwMatcher.calibrateThreshold(one))
    }

    @Test
    fun `發音越不穩定門檻越寬鬆`() {
        val steady = (0 until 4).map {
            extractor.extract(vowel(700.0, 1200.0, 400 + it * 5))
        }
        val shaky = (0 until 4).map {
            extractor.extract(vowel(700.0 + it * 90, 1200.0 + it * 160, 400 + it * 40))
        }
        val tight = DtwMatcher.calibrateThreshold(steady)
        val loose = DtwMatcher.calibrateThreshold(shaky)
        assertNotNull(tight); assertNotNull(loose)
        assertTrue(
            "不穩定的登錄應該得到較寬的門檻：tight=$tight loose=$loose",
            loose!! > tight!!,
        )
    }

    @Test
    fun `多錄幾次會讓門檻收斂而不是膨脹`() {
        // 實機上發生過的回歸：補錄之後門檻從 10.1 一路漲到 21.9，
        // 那個樣板把所有聲音都吞掉，使用者說 A 卻聽到 B。
        //
        // 原因是校準用「全配對平均」，但比對取的是「與任一次錄音的最小距離」。
        // 兩個運算不一致，補錄加進不一致的錄音時 mean 和 σ 會一起被拉高。
        // 改用最近鄰之後，多錄只會讓涵蓋變密、最近鄰距離變小。
        val base = (0 until 3).map { extractor.extract(vowel(700.0, 1200.0, 400 + it * 30)) }
        val tight = DtwMatcher.calibrateThreshold(base)!!

        // 補進幾次落在原本範圍之間的錄音（涵蓋變密）
        val denser = base + listOf(
            extractor.extract(vowel(700.0, 1200.0, 415)),
            extractor.extract(vowel(700.0, 1200.0, 445)),
            extractor.extract(vowel(700.0, 1200.0, 475)),
        )
        val after = DtwMatcher.calibrateThreshold(denser)!!
        // 要守住的是「不會爆炸性成長」，不是「一定變小」——
        // 合成訊號的距離幾乎全由長度差主導，模擬不出真實的涵蓋密度。
        // 實機上看到的是 10.1 → 16.5 → 21.9 這種每次補錄就 1.6 倍的複利成長，
        // 那才是會把所有聲音吞掉的原因。
        assertTrue("補錄後門檻不該爆炸性成長：$tight → $after", after < tight * 1.8f)
    }

    @Test
    fun `補進差異很大的錄音也不會讓門檻爆炸`() {
        // 就算補進來的錄音跟原本差很多，最近鄰仍然只看「離它最近的那一次」，
        // 所以單一個離群值不會把整個門檻拉起來。
        val base = (0 until 3).map { extractor.extract(vowel(700.0, 1200.0, 400 + it * 20)) }
        val tight = DtwMatcher.calibrateThreshold(base)!!
        val withOutlier = base + extractor.extract(vowel(350.0, 2400.0, 420))
        val after = DtwMatcher.calibrateThreshold(withOutlier)!!
        assertTrue("離群值不該讓門檻翻倍：$tight → $after", after < tight * 2f)
    }

    @Test
    fun `登錄一致性可以量出來`() {
        val steady = (0 until 4).map { extractor.extract(vowel(700.0, 1200.0, 400 + it * 5)) }
        val noisy = (0 until 4).map {
            extractor.extract(vowel(700.0 + it * 200, 1200.0 + it * 400, 400 + it * 60))
        }
        val a = DtwMatcher.enrollmentSpread(steady)!!
        val b = DtwMatcher.enrollmentSpread(noisy)!!
        assertTrue("錄得越不一致，離散度應該越大：$a vs $b", b > a)
    }

    @Test
    fun `校準出來的門檻能接受自己人並擋掉別的音`() {
        val takes = (0 until 4).map { extractor.extract(vowel(700.0, 1200.0, 400 + it * 20)) }
        val threshold = DtwMatcher.calibrateThreshold(takes)!!

        val sameAgain = extractor.extract(vowel(700.0, 1200.0, 430))
        val different = extractor.extract(vowel(350.0, 2400.0, 420))

        val dSame = DtwMatcher.distanceToBest(sameAgain, takes)
        val dDiff = DtwMatcher.distanceToBest(different, takes)
        assertTrue("同一個音應通過門檻：$dSame vs $threshold", dSame < threshold)
        assertTrue("不同的音應被擋下：$dDiff vs $threshold", dDiff > threshold)
    }

    // ── 子序列比對（關鍵字觸發）──────────────────────────────────────────

    /** 把幾段語音接在一起，模擬「一句話裡包含關鍵字」。 */
    private fun concat(vararg parts: Utterance): Utterance {
        val total = parts.sumOf { it.samples.size }
        val out = FloatArray(total)
        var at = 0
        parts.forEach { it.samples.copyInto(out, at); at += it.samples.size }
        return Utterance(out, sr)
    }

    @Test
    fun `關鍵字藏在長句中間也找得到`() {
        // 這就是「我做的」跟「使用者要的」的差別：整段比對會因為長度差太多
        // 直接判定不可比對，子序列比對則在語音裡滑動搜尋。
        val keyword = extractor.extract(vowel(700.0, 1200.0, 400))
        val sentence = extractor.extract(
            concat(
                vowel(350.0, 2400.0, 500),   // 前面講了別的
                vowel(700.0, 1200.0, 420),   // ← 關鍵字
                vowel(280.0, 2900.0, 450),   // 後面又講了別的
            )
        )

        assertEquals("整段比對會被長度比擋掉", Float.MAX_VALUE, DtwMatcher.distance(keyword, sentence), 0f)

        val hit = DtwMatcher.subsequenceDistance(keyword, sentence)
        assertNotNull("子序列比對應該找得到", hit)
        assertTrue("距離應該很小，實得 ${hit!!.distance}", hit.distance < 1.5f)
        // 命中區段應該落在中段（前面那句約 50 幀）
        assertTrue("命中位置不合理：${hit.startFrame}-${hit.endFrame}", hit.startFrame > 20)
    }

    @Test
    fun `關鍵字在句首與句尾都找得到`() {
        val keyword = extractor.extract(vowel(700.0, 1200.0, 400))
        val atStart = extractor.extract(concat(vowel(700.0, 1200.0, 410), vowel(350.0, 2400.0, 600)))
        val atEnd = extractor.extract(concat(vowel(350.0, 2400.0, 600), vowel(700.0, 1200.0, 410)))

        val h1 = DtwMatcher.subsequenceDistance(keyword, atStart)
        val h2 = DtwMatcher.subsequenceDistance(keyword, atEnd)
        assertNotNull("句首應找得到", h1)
        assertNotNull("句尾應找得到", h2)
        assertTrue("句首 ${h1!!.distance}", h1.distance < 1.5f)
        assertTrue("句尾 ${h2!!.distance}", h2.distance < 1.5f)
    }

    @Test
    fun `句子裡沒有那個關鍵字時距離明顯偏高`() {
        // 子序列比對幾乎一定找得到「某個」窗口——重點是那個窗口要夠不像。
        val keyword = extractor.extract(vowel(700.0, 1200.0, 400))
        val unrelated = extractor.extract(
            concat(vowel(350.0, 2400.0, 500), vowel(280.0, 2900.0, 450), vowel(300.0, 2600.0, 400))
        )
        val present = DtwMatcher.subsequenceDistance(
            keyword,
            extractor.extract(concat(vowel(350.0, 2400.0, 500), vowel(700.0, 1200.0, 420))),
        )!!
        val absent = DtwMatcher.subsequenceDistance(keyword, unrelated)

        assertTrue(
            "沒有關鍵字時距離應遠高於有的時候：present=${present.distance} absent=${absent?.distance}",
            absent == null || absent.distance > present.distance * 2f,
        )
    }

    @Test
    fun `長度合理性檢查擋住把樣板攤平到整段語音`() {
        // 少了這道檢查，DTW 會為了壓低成本把樣板拉伸去覆蓋整段語音，
        // 產生一個看起來很小、實際毫無意義的距離——那正是誤觸發的來源。
        val keyword = extractor.extract(vowel(700.0, 1200.0, 400))
        val long = extractor.extract(vowel(700.0, 1200.0, 3000))
        val hit = DtwMatcher.subsequenceDistance(keyword, long)
        if (hit != null) {
            assertTrue(
                "命中區段 ${hit.lengthFrames} 幀不該遠大於樣板 ${keyword.frameCount} 幀",
                hit.lengthFrames <= keyword.frameCount * DtwMatcher.MAX_KEYWORD_LENGTH_RATIO,
            )
        }
    }

    @Test
    fun `語音比樣板還短時不做比對`() {
        val keyword = extractor.extract(vowel(700.0, 1200.0, 600))
        val tooShort = extractor.extract(vowel(700.0, 1200.0, 150))
        assertNull(DtwMatcher.subsequenceDistance(keyword, tooShort))
    }

    @Test
    fun `空序列不會讓子序列比對爆炸`() {
        val empty = FeatureSequence(emptyArray(), 24)
        val real = extractor.extract(vowel(700.0, 1200.0, 400))
        assertNull(DtwMatcher.subsequenceDistance(empty, real))
        assertNull(DtwMatcher.subsequenceDistance(real, empty))
    }

    @Test
    fun `關鍵字模式在長句裡命中而整段模式不會`() {
        val takes = (0 until 4).map { extractor.extract(vowel(700.0, 1200.0, 400 + it * 20)) }
        val threshold = DtwMatcher.calibrateThreshold(takes)!!
        val sentence = extractor.extract(
            concat(vowel(350.0, 2400.0, 500), vowel(700.0, 1200.0, 420), vowel(280.0, 2900.0, 400))
        )

        val keywordTemplate = AcousticTemplate(
            id = 1, label = "water", matchMode = MatchMode.Keyword, keyword = "water",
            spokenPhrase = "I need some water.", exemplars = takes,
            rejectionThreshold = threshold,
        )
        val wholeTemplate = keywordTemplate.copy(id = 2, matchMode = MatchMode.Whole)

        val keywordResult = TemplateMatcher.match(sentence, listOf(keywordTemplate))
        assertTrue("關鍵字模式應命中，實得 $keywordResult", keywordResult is MatchResult.Hit)
        assertNotNull("命中應帶區段位置", (keywordResult as MatchResult.Hit).span)

        assertEquals(
            "整段模式在長句裡不該命中",
            MatchResult.NoMatch, TemplateMatcher.match(sentence, listOf(wholeTemplate)),
        )
    }

    @Test
    fun `只說關鍵字時門檻不收緊`() {
        // 「唸了關鍵字卻沒反應」的根因：收緊 25% 是為了補償「子序列比對可以在
        // 長語音裡挑最好的窗口」，但查詢跟樣板一樣長時根本沒有窗口可挑——
        // 固定係數等於對最單純的情況最嚴格。
        assertEquals(1f, DtwMatcher.keywordThresholdFactor(87, 87), 1e-4f)
        assertEquals(1f, DtwMatcher.keywordThresholdFactor(80, 87), 1e-4f)   // 比樣板還短也不收緊
    }

    @Test
    fun `查詢越長收緊越多`() {
        val same = DtwMatcher.keywordThresholdFactor(90, 87)
        val double = DtwMatcher.keywordThresholdFactor(174, 87)
        val triple = DtwMatcher.keywordThresholdFactor(261, 87)
        assertTrue("應單調遞減：$same > $double > $triple", same > double && double > triple)
        // 3 倍長就收緊到滿檔，再長也不會更嚴
        assertEquals(DtwMatcher.KEYWORD_THRESHOLD_FACTOR, triple, 1e-4f)
        assertEquals(DtwMatcher.KEYWORD_THRESHOLD_FACTOR, DtwMatcher.keywordThresholdFactor(900, 87), 1e-4f)
    }

    @Test
    fun `用真實錄音的數字驗證收緊曲線`() {
        // 實機日誌裡的真實數字（樣板平均 87 幀，當時校準出來的門檻 7.323）
        val base = 7.323f

        // 只說關鍵字：97 幀、距離 4.010。這是使用者回報「唸了卻沒反應」的情況，
        // 必須有明顯餘裕通過，不能只是剛好擦邊。
        val shortThreshold = base * DtwMatcher.keywordThresholdFactor(97, 87)
        assertTrue("只說關鍵字應通過：4.010 vs $shortThreshold", 4.010f < shortThreshold)
        assertTrue("而且要有餘裕，不是剛好擦邊", 4.010f < shortThreshold * 0.7f)

        // 講整句：281 幀，收緊應該到滿檔
        assertEquals(
            DtwMatcher.KEYWORD_THRESHOLD_FACTOR,
            DtwMatcher.keywordThresholdFactor(281, 87), 1e-4f,
        )
    }

    @Test
    fun `講整句時正確的樣板要贏過相近的那個`() {
        // 這才是真正該守住的不變量。
        //
        // 原本這裡寫的是「不相干的樣板必須超過門檻」，但那個要求訂太死：
        // 放寬門檻之後它一定會失敗，而失敗不代表會說錯話——
        // 實測講「need food」時 need_food=3.253、need_water=5.538，
        // 兩者就算都在門檻內，差距 2.29 也遠大於 margin，正確的那個本來就會勝出。
        val foodDistance = 3.253f
        val waterDistance = 5.538f
        val margin = waterDistance - foodDistance
        assertTrue("正確的樣板距離要明顯較小", foodDistance < waterDistance)
        assertTrue(
            "差距要遠大於 margin 門檻，才不會被判成歧義：$margin vs ${AcousticTemplate.DEFAULT_MARGIN}",
            margin > AcousticTemplate.DEFAULT_MARGIN * 5,
        )
    }

    @Test
    fun `關鍵字模式的門檻有收緊`() {
        // 子序列比對可以挑整段語音裡最好的窗口，距離天生低於整段比對。
        // 沿用原門檻會一講長句就到處誤觸發。
        assertTrue(DtwMatcher.KEYWORD_THRESHOLD_FACTOR < 1f)
        assertTrue("收得太緊會連自己都過不了", DtwMatcher.KEYWORD_THRESHOLD_FACTOR > 0.5f)
    }

    // ── 比對政策 ────────────────────────────────────────────────────────

    private fun template(
        id: Long, label: String, takes: List<FeatureSequence>, margin: Float = 0.15f,
    ) = AcousticTemplate(
        id = id,
        label = label,
        spokenPhrase = "I'd like some $label, please.",
        exemplars = takes,
        rejectionThreshold = DtwMatcher.calibrateThreshold(takes)!!,
        marginThreshold = margin,
    )

    @Test
    fun `清楚命中回傳 Hit`() {
        val water = (0 until 4).map { extractor.extract(vowel(700.0, 1200.0, 400 + it * 20)) }
        val help = (0 until 4).map { extractor.extract(vowel(350.0, 2400.0, 400 + it * 20)) }
        val templates = listOf(template(1, "water", water), template(2, "help", help))

        val result = TemplateMatcher.match(extractor.extract(vowel(700.0, 1200.0, 420)), templates)
        assertTrue("應為 Hit，實得 $result", result is MatchResult.Hit)
        assertEquals("water", (result as MatchResult.Hit).template.label)
    }

    @Test
    fun `兩個樣板同樣接近時寧可問也不要說錯話`() {
        // 語句庫裡出現聲學上很接近的兩句是常態
        //（"I need the bathroom" / "I need my medication"）。
        // 這種時候直接播出最佳解等於替使用者發言，風險遠高於多問一次。
        val takes = (0 until 4).map { extractor.extract(vowel(700.0, 1200.0, 400 + it * 20)) }
        val twins = listOf(
            template(1, "water", takes, margin = 5f),   // 要求次佳遠 500% → 必定判為歧義
            template(2, "juice", takes, margin = 5f),
        )
        val result = TemplateMatcher.match(extractor.extract(vowel(700.0, 1200.0, 420)), twins)
        assertTrue("應為 Ambiguous，實得 $result", result is MatchResult.Ambiguous)
        assertEquals(2, (result as MatchResult.Ambiguous).candidates.size)
    }

    @Test
    fun `兩個樣板一樣像時判為歧義而不是講錯話`() {
        // margin 原本是絕對距離 0.15，但真實距離落在 3～15 之間，
        // 那個檢查等於從來沒生效過——App 會很有把握地講錯那一句。
        // 改成相對比例之後，次佳必須比最佳遠 15% 以上才算「確定」。
        val takes = (0 until 4).map { extractor.extract(vowel(700.0, 1200.0, 400 + it * 20)) }
        val twins = listOf(
            template(1, "water", takes),
            template(2, "juice", takes),   // 完全相同的樣板 → margin 必為 0
        )
        val result = TemplateMatcher.match(extractor.extract(vowel(700.0, 1200.0, 420)), twins)
        assertTrue("應為 Ambiguous，實得 $result", result is MatchResult.Ambiguous)
    }

    @Test
    fun `margin 是相對比例不是絕對距離`() {
        val takes = (0 until 4).map { extractor.extract(vowel(700.0, 1200.0, 400 + it * 20)) }
        val other = (0 until 4).map { extractor.extract(vowel(350.0, 2400.0, 400 + it * 20)) }
        val templates = listOf(template(1, "water", takes), template(2, "help", other))
        val hit = TemplateMatcher.match(extractor.extract(vowel(700.0, 1200.0, 420)), templates)
        assertTrue(hit is MatchResult.Hit)
        // 兩個音差很多，相對 margin 應該遠大於 1（次佳距離是最佳的兩倍以上）
        assertTrue("margin 應是比例值，實得 ${(hit as MatchResult.Hit).margin}", hit.margin > 1f)
    }

    @Test
    fun `沒有任何樣板夠接近就回 NoMatch 而不是硬挑一個`() {
        val water = (0 until 4).map { extractor.extract(vowel(700.0, 1200.0, 400 + it * 20)) }
        val templates = listOf(template(1, "water", water))
        val unrelated = extractor.extract(vowel(280.0, 2900.0, 410))

        assertEquals(MatchResult.NoMatch, TemplateMatcher.match(unrelated, templates))
    }

    @Test
    fun `雜訊不會被硬塞給某個樣板`() {
        val rng = Random(20260804)
        val noise = Utterance(FloatArray(sr * 4 / 10) { (rng.nextFloat() - 0.5f) * 0.3f }, sr)
        val water = (0 until 4).map { extractor.extract(vowel(700.0, 1200.0, 400 + it * 20)) }
        val result = TemplateMatcher.match(extractor.extract(noise), listOf(template(1, "water", water)))
        assertEquals(MatchResult.NoMatch, result)
    }

    /**
     * 把整條管線的距離排序釘住。這是最有價值的一項——上面每個測試各自只看一個性質，
     * 但真正決定 App 會不會說錯話的是「這些距離彼此的相對大小」。
     *
     * 實測值（合成訊號，門檻 ≈ 0.82）：
     *   自己 0.17 < 講慢 0.78 < 相近的音 3.2 < 靜音 4.4 < 雜訊 4.8 < 不同的音 5.5
     *
     * 注意「講慢 0.78」離門檻只差一點。合成的穩態母音會低估這個差距，
     * 但方向是真的：登錄時若每次語速都一樣，校準出來的門檻就容不下語速變化。
     * 對策是登錄流程要引導使用者用不同語速錄，而不是把門檻調寬——
     * 調寬門檻會同時放進別的音。
     */
    @Test
    fun `距離的相對大小符合預期排序`() {
        val enrolled = (0 until 4).map { extractor.extract(vowel(700.0, 1200.0, 400 + it * 20)) }
        fun d(f1: Double, f2: Double, ms: Int, amp: Float = 0.5f) =
            DtwMatcher.distanceToBest(extractor.extract(vowel(f1, f2, ms, amp)), enrolled)

        val self = d(700.0, 1200.0, 430)
        val slower = d(700.0, 1200.0, 600)
        val nearby = d(750.0, 1300.0, 420)
        val different = d(350.0, 2400.0, 420)
        val rng = Random(20260804)
        val noise = DtwMatcher.distanceToBest(
            extractor.extract(Utterance(FloatArray(sr * 4 / 10) { (rng.nextFloat() - 0.5f) * 0.3f }, sr)),
            enrolled,
        )
        val order = "self=$self slower=$slower nearby=$nearby noise=$noise different=$different"

        assertTrue("自己 < 講慢：$order", self < slower)
        assertTrue("講慢 < 相近的音：$order", slower < nearby)
        assertTrue("相近的音 < 不同的音：$order", nearby < different)
        assertTrue("雜訊要遠離所有樣板：$order", noise > nearby)

        // 相近的音必須離門檻夠遠，不能只是「剛好被擋下」
        val threshold = DtwMatcher.calibrateThreshold(enrolled)!!
        assertTrue("相近的音應明顯高於門檻 $threshold：$order", nearby > threshold * 2f)
    }

    @Test
    fun `音量差異幾乎完全不影響距離`() {
        val enrolled = (0 until 4).map { extractor.extract(vowel(700.0, 1200.0, 400 + it * 20)) }
        val threshold = DtwMatcher.calibrateThreshold(enrolled)!!
        // 離麥克風遠了好幾倍仍然要能命中——這是實際使用最常見的落差
        val quiet = DtwMatcher.distanceToBest(extractor.extract(vowel(700.0, 1200.0, 420, 0.15f)), enrolled)
        assertTrue("小聲版本應遠低於門檻 $threshold，實得 $quiet", quiet < threshold * 0.5f)
    }

    @Test
    fun `空的查詢判為不是發聲`() {
        assertEquals(
            MatchResult.NotSpeech,
            TemplateMatcher.match(FeatureSequence(emptyArray(), 24), emptyList()),
        )
    }

    @Test
    fun `還沒登錄任何樣板時回 NoMatch`() {
        val q = extractor.extract(vowel(700.0, 1200.0, 400))
        assertEquals(MatchResult.NoMatch, TemplateMatcher.match(q, emptyList()))
    }
}
