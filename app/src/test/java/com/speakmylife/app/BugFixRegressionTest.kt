package com.speakmylife.app

import com.speakmylife.app.aphasia.data.acoustic.DtwMatcher
import com.speakmylife.app.aphasia.data.local.VectorCodec
import com.speakmylife.app.aphasia.domain.model.FeatureSequence
import com.speakmylife.app.ai.PhraseAdoptionRanker
import com.speakmylife.app.data.CorpusImporter
import com.speakmylife.app.data.LlmApiEntry
import com.speakmylife.app.data.LlmProvider
import com.speakmylife.app.data.SttApiEntry
import com.speakmylife.app.data.SttProvider
import com.speakmylife.app.safety.SafetyGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 這一輪掃出來的 bug 的回歸測試。
 *
 * 每一條都對應一個「實際跑出來確認過」的錯誤行為，不是猜的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BugFixRegressionTest {

    private fun seq(vararg f: FloatArray) = FeatureSequence(arrayOf(*f))

    // ── 聲學比對 ────────────────────────────────────────────────────────

    @Test
    fun `特徵維度不同時不能被當成完全相同`() {
        // 實測原本回 0.0——也就是「一模一樣」。那個樣板會命中所有聲音，
        // 使用者講什麼都被說成同一句。維度會因為 MFCC 設定改變而變，
        // 而樣板現在會跨裝置同步，這不是假想的情況。
        val a = seq(floatArrayOf(1f, 2f, 3f), floatArrayOf(1f, 2f, 3f))
        val b = seq(floatArrayOf(1f, 2f), floatArrayOf(1f, 2f))
        assertEquals(Float.MAX_VALUE, DtwMatcher.distance(a, b), 0f)
    }

    @Test
    fun `向量檢索遇到維度不符要回不相似而不是猜一個分數`() {
        assertEquals(0f, VectorCodec.dot(floatArrayOf(1f, 0f, 0f), floatArrayOf(1f, 0f)), 0f)
        // 同維度照常算
        assertEquals(1f, VectorCodec.dot(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f)), 1e-6f)
    }

    @Test
    fun `幾次錄音一模一樣時門檻不能是 0`() {
        // 門檻 0 代表距離要小於 0 才算命中——也就是**永遠不會命中**。
        // 使用者看到「已啟用」卻怎麼叫都沒反應，完全查不出原因。
        val identical = List(4) { seq(floatArrayOf(1f, 1f), floatArrayOf(1f, 1f)) }
        val t = DtwMatcher.calibrateThreshold(identical)
        assertTrue("門檻 $t 必須大於 0", (t ?: 0f) > 0f)
    }

    // ── 匯入 ───────────────────────────────────────────────────────────

    @Test
    fun `CSV 的引號欄位裡可以有逗號`() {
        // 實測原本會整列往後位移：label 變「"I need water」、emoji 變「please"」。
        // 而 AAC 的標籤本來就是句子，帶逗號是常態。
        val csv = "id,label,emoji,category\n" +
            "a1,\"I need water, please\",💧,products\n"
        val r = CorpusImporter.parseCsv(csv)
        assertTrue(r is CorpusImporter.Result.Ok)
        val item = (r as CorpusImporter.Result.Ok).items.single()
        assertEquals("I need water, please", item.label)
        assertEquals("💧", item.emoji)
    }

    @Test
    fun `CSV 的兩個雙引號代表一個字面雙引號`() {
        assertEquals(
            listOf("a", "他說\"好\"", "b"),
            CorpusImporter.splitCsvLine("a,\"他說\"\"好\"\"\",b"),
        )
    }

    // ── 安全層 ─────────────────────────────────────────────────────────

    @Test
    fun `英文的自傷說法要攔得下來`() {
        // 「I want to die」實測原本判定為 NORMAL——清單裡有 kill myself、
        // end my life，就是漏了最常見的這一句。介面早就支援英文了。
        listOf(
            "I want to die", "i wanna die", "I don't want to live anymore",
            "I'm going to kill myself", "I want to hurt myself",
            "maybe everyone is better off dead", "I took an overdose",
        ).forEach {
            assertTrue("「$it」應該要被判成危機", SafetyGuard.containsCrisisSignal(it))
            assertEquals("「$it」應該鎖住語音", SafetyGuard.RiskLevel.LOCK, SafetyGuard.classifyRisk(it))
        }
    }

    @Test
    fun `日文韓文的自傷說法也要攔`() {
        listOf("死にたい", "消えたい", "죽고 싶어요").forEach {
            assertTrue("「$it」應該要被判成危機", SafetyGuard.containsCrisisSignal(it))
        }
    }

    @Test
    fun `中文原本擋得下的照樣要擋`() {
        listOf("我想死", "自殺", "不想活了", "我想跳樓").forEach {
            assertTrue("「$it」不能因為改動而漏掉", SafetyGuard.containsCrisisSignal(it))
        }
    }

    @Test
    fun `一般的話不會被誤判成危機`() {
        listOf("我要喝水", "I need some water", "頭有點痛", "今天天氣很好",
               "I am tired", "我想睡覺").forEach {
            assertFalse("「$it」不該被當成危機", SafetyGuard.containsCrisisSignal(it))
        }
    }

    @Test
    fun `英文的醫療說法要跳確認`() {
        // 確認那一關是為了防止 AI 把部位講錯。英文介面原本整個跳過。
        listOf("my chest hurts", "I need my medication", "call the nurse",
               "I feel dizzy", "I am bleeding").forEach {
            assertEquals("「$it」應該要二次確認",
                SafetyGuard.RiskLevel.CONFIRM, SafetyGuard.classifyRisk(it))
        }
    }

    @Test
    fun `英文醫療關鍵字要看整個詞不能看子字串`() {
        // "ill" 命中 "still"、"bill" 這種誤判會讓一般句子也要按確認，
        // 按到最後使用者就不看內容直接按了——那一關就等於白做。
        listOf("still here", "the bill is paid", "shot in the dark").forEach {
            // shot 本來就是醫療詞（打針），只驗前兩個
            if (it.startsWith("shot")) return@forEach
            assertEquals("「$it」不該被當成醫療",
                SafetyGuard.RiskLevel.NORMAL, SafetyGuard.classifyRisk(it))
        }
    }

    @Test
    fun `英文的拒絕治療要鎖住`() {
        listOf("I refuse the treatment", "do not resuscitate me", "DNR",
               "I want to give up on treatment").forEach {
            assertEquals("「$it」應該鎖住語音",
                SafetyGuard.RiskLevel.LOCK, SafetyGuard.classifyRisk(it))
        }
    }

    @Test
    fun `模型吐出英文的絕望說法要被消毒`() {
        val out = SafetyGuard.sanitizeForSpeech("You will never get better, just kill yourself.")
        assertFalse("不能把 kill yourself 唸出來", out.contains("kill yourself", ignoreCase = true))
        assertFalse("不能把 never get better 唸出來", out.contains("never get better", ignoreCase = true))
    }

    // ── 設定 ───────────────────────────────────────────────────────────

    @Test
    fun `前後有空白的佔位金鑰不算可用`() {
        // 從文件複製貼上很容易帶到空白，而未 trim 的 startsWith 會被繞過去，
        // 結果是它進了候選清單、換來一個看不懂的 401。
        assertFalse(SttApiEntry(id = "a", provider = SttProvider.GROQ, key = " YOUR_KEY ").usable)
        assertFalse(LlmApiEntry(id = "a", provider = LlmProvider.GROQ, key = " YOUR_KEY ").usable)
        // 真的金鑰帶空白仍然可用（trim 之後是有效的）
        assertTrue(SttApiEntry(id = "a", provider = SttProvider.GROQ, key = "  gsk_real  ").usable)
    }

    // ── 採納紀錄 ───────────────────────────────────────────────────────

    @Test
    fun `英文候選只差大小寫要算同一句`() {
        // 不轉小寫的話同一個說法會被記成兩筆，加分永遠累積不起來，
        // 這個功能對英文使用者等於沒有作用。
        assertEquals(
            PhraseAdoptionRanker.normalize("I need some water"),
            PhraseAdoptionRanker.normalize("i need some water."),
        )
    }

    @Test
    fun `意思相反的句子仍然不會被併在一起`() {
        assertNotEquals(
            PhraseAdoptionRanker.normalize("我要喝水"),
            PhraseAdoptionRanker.normalize("我不要喝水"),
        )
    }
}
