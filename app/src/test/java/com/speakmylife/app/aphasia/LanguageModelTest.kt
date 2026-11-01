package com.speakmylife.app.aphasia

import com.speakmylife.app.aphasia.data.remote.AphasiaPrompts
import com.speakmylife.app.aphasia.data.remote.LlmResponseParser
import com.speakmylife.app.aphasia.data.remote.LlmTransport
import com.speakmylife.app.aphasia.data.remote.ReconstructionGuard
import com.speakmylife.app.aphasia.data.repository.LanguageModelRepositoryImpl
import com.speakmylife.app.aphasia.domain.model.CommunicationPhrase
import com.speakmylife.app.aphasia.domain.model.ReconstructedSentence
import com.speakmylife.app.aphasia.domain.repository.PolitenessLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Module 2／3 的 prompt、解析與把關。
 *
 * 全部用假的 transport 跑：真正會出錯的是 prompt 契約與輸出處理，
 * 而那兩件事不需要網路就能驗——需要網路的測試也不會有人固定跑。
 */
class LanguageModelTest {

    private class FakeTransport(
        private val offline: Boolean = false,
        private val respond: (system: String, user: String) -> Result<String>,
    ) : LlmTransport {
        var lastSystem: String? = null
        var lastUser: String? = null
        var lastTemperature: Double? = null
        var callCount = 0
        override val label = "fake"
        override suspend fun complete(
            system: String, user: String, temperature: Double, maxTokens: Int, timeoutMs: Long,
        ): Result<String> {
            lastSystem = system; lastUser = user; lastTemperature = temperature; callCount++
            return respond(system, user)
        }
        override suspend fun isOfflineCapable() = offline
    }

    private fun repo(response: String) =
        LanguageModelRepositoryImpl(FakeTransport { _, _ -> Result.success(response) })

    // ── JSON 解析 ───────────────────────────────────────────────────────

    @Test
    fun `解析正常的候選清單`() {
        val parsed = LlmResponseParser.parseCandidates(
            """{"candidates":[{"text":"I want water.","confidence":88,"rationale":"single noun"},
               {"text":"Is there water?","confidence":60}]}"""
        ).getOrThrow()
        assertEquals(2, parsed.size)
        assertEquals("I want water.", parsed[0].text)
        assertEquals(0.88f, parsed[0].confidence, 1e-4f)
        assertEquals("single noun", parsed[0].rationale)
        assertNull("沒給 rationale 就該是 null", parsed[1].rationale)
    }

    @Test
    fun `markdown 圍欄與前後客套話會被清掉`() {
        // 模型加這些不是壞掉，是常態
        val parsed = LlmResponseParser.parseCandidates(
            """Sure! Here's my answer:
            ```json
            {"candidates":[{"text":"I want water.","confidence":88}]}
            ```
            Let me know if you need anything else."""
        ).getOrThrow()
        assertEquals(1, parsed.size)
        assertEquals("I want water.", parsed[0].text)
    }

    @Test
    fun `被截斷的 JSON 回失敗而不是硬撈出半句話`() {
        // 實際發生過：rationale 太長、token 燒完，句子根本還沒寫完，
        // 而截斷的 JSON 被直接唸給病人聽。
        val truncated = """{"candidates":[{"text":"I would like a glass of"""
        assertTrue(LlmResponseParser.parseCandidates(truncated).isFailure)
        assertNull(LlmResponseParser.extractJsonObject(truncated))
    }

    @Test
    fun `rationale 裡有大括號不會切錯邊界`() {
        val parsed = LlmResponseParser.parseCandidates(
            """{"candidates":[{"text":"I want water.","confidence":80,"rationale":"user said {water}"}]}"""
        ).getOrThrow()
        assertEquals(1, parsed.size)
        assertEquals("user said {water}", parsed[0].rationale)
    }

    @Test
    fun `空的候選清單是合法答案不是解析失敗`() {
        // prompt 明確允許模型說「碎詞太稀疏，我不猜」。
        // 這跟解析失敗必須分開，否則上層會白白換一輪供應商。
        val result = LlmResponseParser.parseCandidates("""{"candidates":[]}""")
        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow().size)
    }

    @Test
    fun `text 欄位塞進整包 JSON 時會被丟掉`() {
        val parsed = LlmResponseParser.parseCandidates(
            """{"candidates":[{"text":"{\"reconstructed\":\"water\"}","confidence":90},
               {"text":"I want water.","confidence":70}]}"""
        ).getOrThrow()
        assertEquals(1, parsed.size)
        assertEquals("I want water.", parsed[0].text)
    }

    @Test
    fun `沒給信心度時當成不確定`() {
        val parsed = LlmResponseParser.parseCandidates(
            """{"candidates":[{"text":"I want water."}]}"""
        ).getOrThrow()
        // 0.0 會讓它被當成廢話丟掉，1.0 會讓它被當成確定——兩個都是錯的訊號
        assertEquals(0.5f, parsed[0].confidence, 1e-4f)
    }

    // ── 憑空生成把關 ────────────────────────────────────────────────────

    @Test
    fun `補進碎詞沒有的名詞會被擋下`() {
        // 這是實際踩過的坑：碎詞只有一個字，卻被補成一整句帶時間地點的話
        val verdict = ReconstructionGuard.check(
            ReconstructedSentence("I would like a glass of cold water from the kitchen.", 0.9f),
            fragments = listOf("water"),
        )
        assertTrue("應判定為憑空生成，實得 $verdict", verdict is ReconstructionGuard.Verdict.Fabricated)
        val added = (verdict as ReconstructionGuard.Verdict.Fabricated).addedWords
        assertTrue("應抓到 glass/cold/kitchen，實得 $added",
            added.containsAll(listOf("glass", "cold", "kitchen")))
    }

    @Test
    fun `只用碎詞加載體詞的句子會通過`() {
        assertEquals(
            ReconstructionGuard.Verdict.Allowed,
            ReconstructionGuard.check(ReconstructedSentence("I want water.", 0.9f), listOf("water")),
        )
        assertEquals(
            ReconstructionGuard.Verdict.Allowed,
            ReconstructionGuard.check(
                ReconstructedSentence("I need to go to the bathroom.", 0.9f),
                listOf("bathroom", "go"),
            ),
        )
    }

    @Test
    fun `詞形變化不會被誤判成憑空生成`() {
        // 碎詞 "hurt" 而候選寫 "hurts"，是同一個意思
        assertEquals(
            ReconstructionGuard.Verdict.Allowed,
            ReconstructionGuard.check(
                ReconstructedSentence("My head hurts.", 0.9f), listOf("head", "hurt"),
            ),
        )
    }

    @Test
    fun `個人語句庫裡出現過的詞算有來源`() {
        // 使用者自己講過的話不算憑空生成
        val verdict = ReconstructionGuard.check(
            ReconstructedSentence("I need my medication.", 0.8f),
            fragments = listOf("medication"),
            personalContext = listOf(CommunicationPhrase(text = "I need my medication at eight")),
        )
        assertEquals(ReconstructionGuard.Verdict.Allowed, verdict)
    }

    @Test
    fun `help 與 doctor 不算載體詞`() {
        // 這類詞看起來無害，但補錯了後果很嚴重，所以不放進允許清單
        val verdict = ReconstructionGuard.check(
            ReconstructedSentence("I need help from the doctor.", 0.9f), listOf("water"),
        )
        assertTrue(verdict is ReconstructionGuard.Verdict.Fabricated)
        val added = (verdict as ReconstructionGuard.Verdict.Fabricated).addedWords
        assertTrue("應抓到 help 與 doctor，實得 $added", added.containsAll(listOf("help", "doctor")))
    }

    @Test
    fun `憑空補進來的數字會被抓到`() {
        val verdict = ReconstructionGuard.check(
            ReconstructedSentence("I need 3 pills at 8 o'clock.", 0.9f), listOf("pills"),
        )
        assertTrue(verdict is ReconstructionGuard.Verdict.Fabricated)
    }

    @Test
    fun `中文的憑空生成也擋得住`() {
        assertEquals(
            ReconstructionGuard.Verdict.Allowed,
            ReconstructionGuard.check(ReconstructedSentence("我要喝水", 0.9f), listOf("水", "喝")),
        )
        assertTrue(
            ReconstructionGuard.check(
                ReconstructedSentence("我要去醫院看醫生", 0.9f), listOf("水"),
            ) is ReconstructionGuard.Verdict.Fabricated
        )
    }

    // ── Repository 行為 ─────────────────────────────────────────────────

    @Test
    fun `重組會過濾掉憑空生成的候選並依信心度排序`() = runBlocking {
        val r = repo("""{"candidates":[
            {"text":"I want water.","confidence":70},
            {"text":"Bring me water from the hospital kitchen.","confidence":95},
            {"text":"Water, please.","confidence":85}]}""")
        val out = r.reconstruct(listOf("water"))
        assertEquals("憑空生成那句應被擋下", 2, out.size)
        assertEquals("Water, please.", out[0].text)   // 0.85 排在 0.70 前面
        assertEquals("I want water.", out[1].text)
    }

    @Test
    fun `碎詞是空的就不呼叫模型`() = runBlocking {
        val transport = FakeTransport { _, _ -> Result.success("""{"candidates":[]}""") }
        val out = LanguageModelRepositoryImpl(transport).reconstruct(listOf("", "   "))
        assertEquals(0, out.size)
        assertEquals("不該送出任何請求", 0, transport.callCount)
    }

    @Test
    fun `傳輸失敗會往上丟而不是變成空結果`() = runBlocking {
        // 之前把錯誤吞掉，害得「額度用完」在畫面上顯示成「語音辨識掛了」
        val boom = IllegalStateException("HTTP 429 quota exceeded")
        val r = LanguageModelRepositoryImpl(FakeTransport { _, _ -> Result.failure(boom) })
        val e = runCatching { r.reconstruct(listOf("water")) }.exceptionOrNull()
        assertEquals(boom, e)
    }

    @Test
    fun `模型回傳垃圾時回空清單而不是崩掉`() = runBlocking {
        assertEquals(0, repo("I'm sorry, I can't help with that.").reconstruct(listOf("water")).size)
    }

    @Test
    fun `個人語句庫會被寫進 prompt 但標明不可照抄`() = runBlocking {
        val transport = FakeTransport { _, _ -> Result.success("""{"candidates":[]}""") }
        LanguageModelRepositoryImpl(transport).reconstruct(
            listOf("water"),
            listOf(CommunicationPhrase(text = "Could I have some water please")),
        )
        val user = transport.lastUser!!
        assertTrue("語句庫內容應出現在 prompt 裡", user.contains("Could I have some water please"))
        assertTrue("必須標明不可照抄", user.contains("Do NOT copy"))
    }

    @Test
    fun `重組溫度不是零這樣三個候選才會不一樣`() = runBlocking {
        val transport = FakeTransport { _, _ -> Result.success("""{"candidates":[]}""") }
        LanguageModelRepositoryImpl(transport).reconstruct(listOf("water"))
        assertTrue("溫度應大於 0，實得 ${transport.lastTemperature}", transport.lastTemperature!! > 0.0)
    }

    // ── Grammar Sync ────────────────────────────────────────────────────

    @Test
    fun `文法修正用溫度零`() = runBlocking {
        val transport = FakeTransport { _, _ ->
            Result.success("""{"text":"My head hurts.","confidence":95,"rationale":"agreement"}""")
        }
        LanguageModelRepositoryImpl(transport).polishGrammar("My head hurt")
        // 同一句話每次得到不同結果會讓使用者不知道該相信哪一個
        assertEquals(0.0, transport.lastTemperature!!, 0.0)
    }

    @Test
    fun `文法修正只改文法`() = runBlocking {
        val r = repo("""{"text":"My head hurts.","confidence":95,"rationale":"subject-verb agreement"}""")
        assertEquals("My head hurts.", r.polishGrammar("My head hurt").text)
    }

    @Test
    fun `文法修正順手補內容時會退回原句`() = runBlocking {
        // 模型有時會把「修文法」做成「替使用者把話說完」
        val r = repo("""{"text":"My head hurts and I need to see a doctor.","confidence":90}""")
        val out = r.polishGrammar("My head hurt")
        assertEquals("應退回原句", "My head hurt", out.text)
        assertEquals("no change", out.rationale)
    }

    @Test
    fun `文法修正回應無法解析時保留原句`() = runBlocking {
        assertEquals("My head hurt", repo("nonsense").polishGrammar("My head hurt").text)
    }

    @Test
    fun `空字串會被擋下`() = runBlocking {
        val r = repo("""{"text":"x"}""")
        assertTrue(runCatching { r.polishGrammar("  ") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { r.adjustPoliteness("  ", PolitenessLevel.Formal) }
            .exceptionOrNull() is IllegalArgumentException)
    }

    // ── Politeness ──────────────────────────────────────────────────────

    @Test
    fun `語氣調整把級別寫進 prompt`() = runBlocking {
        val transport = FakeTransport { _, _ -> Result.success("""{"text":"Could I have some water?"}""") }
        LanguageModelRepositoryImpl(transport).adjustPoliteness("Give me water", PolitenessLevel.Formal)
        assertTrue(transport.lastUser!!.contains("formal"))
        assertTrue(transport.lastSystem!!.contains("Registers:"))
    }

    @Test
    fun `語氣調整不套憑空生成把關`() = runBlocking {
        // 換語氣本來就會換詞。用實詞比對去擋會把正確結果全部否決掉——
        // 這是刻意的例外，不是漏掉。
        val r = repo("""{"text":"Could I please have some water?","confidence":90}""")
        assertEquals(
            "Could I please have some water?",
            r.adjustPoliteness("Give me water", PolitenessLevel.Formal).text,
        )
    }

    @Test
    fun `語氣的 prompt 明講緊急需求不能被淡化`() {
        assertTrue(AphasiaPrompts.POLITENESS_SYSTEM.contains("stays urgent at every level"))
    }

    // ── 離線能力 ────────────────────────────────────────────────────────

    @Test
    fun `離線能力由 transport 決定`() = runBlocking {
        val offline = LanguageModelRepositoryImpl(
            FakeTransport(offline = true) { _, _ -> Result.success("{}") })
        val online = LanguageModelRepositoryImpl(
            FakeTransport(offline = false) { _, _ -> Result.success("{}") })
        assertTrue(offline.isAvailableOffline())
        assertTrue(!online.isAvailableOffline())
    }
}
