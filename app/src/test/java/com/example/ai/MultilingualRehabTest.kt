package com.example.ai

import com.example.data.AphasiaPracticeI18n
import com.example.data.AppLanguage
import com.example.ui.I18n
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 釘住「跟讀題目換成介面語言」這條路上會壞掉的地方。
 *
 * 背景：裝置端 Vosk 是 vosk-model-small-cn，只認中文。題目換成英文而辨識器
 * 還是中文的話，畫面上的字跟拿去比對的字對不起來，分數會固定 0 分——這是
 * 網頁版真的踩過的坑。所以每一段「英文題目」的路徑都要有測試守著。
 */
class MultilingualRehabTest {

    @After
    fun reset() { I18n.lang = AppLanguage.ZhTW }

    // ── 題目本身 ────────────────────────────────────────────────────────────

    @Test
    fun `沒有多語辨識時題目一律維持中文`() {
        I18n.lang = AppLanguage.En
        assertEquals("我想喝水", AphasiaPracticeI18n.target("我想喝水", multilingualStt = false))
        assertEquals("水", AphasiaPracticeI18n.target("水", multilingualStt = false))
    }

    @Test
    fun `有多語辨識時題目換成介面語言`() {
        I18n.lang = AppLanguage.En
        assertEquals("I want to drink water", AphasiaPracticeI18n.target("我想喝水", true))
        I18n.lang = AppLanguage.Ja
        assertEquals("水が飲みたい", AphasiaPracticeI18n.target("我想喝水", true))
        I18n.lang = AppLanguage.Ko
        assertEquals("물 마시고 싶어요", AphasiaPracticeI18n.target("我想喝水", true))
    }

    @Test
    fun `中文介面即使有多語辨識也維持中文`() {
        I18n.lang = AppLanguage.ZhTW
        assertEquals("我想喝水", AphasiaPracticeI18n.target("我想喝水", true))
    }

    @Test
    fun `查無譯文時退回中文原文而不是空字串`() {
        I18n.lang = AppLanguage.En
        val odd = "這句不在題庫翻譯表裡"
        assertEquals(odd, AphasiaPracticeI18n.target(odd, true))
    }

    // ── 字卡切分 ────────────────────────────────────────────────────────────

    @Test
    fun `英文句子一詞一張卡而不是每兩個字母硬切`() {
        assertEquals(
            listOf("Please", "give", "me", "my", "medicine"),
            "Please give me my medicine".toRehabChips()
        )
    }

    @Test
    fun `中文仍然維持每兩字一組`() {
        assertEquals(listOf("我想", "喝水"), "我想喝水".toRehabChips())
    }

    @Test
    fun `英文標點不會變成空白字卡`() {
        val chips = "It hurts. I want to go home!".toRehabChips()
        assertTrue(chips.none { it.isBlank() })
        assertEquals(listOf("It", "hurts", "I", "want", "to", "go", "home"), chips)
    }

    // ── 離線備援的錯字標記 ──────────────────────────────────────────────────
    // 這裡是網頁版原本的 bug 在 App 的對應位置：英文若照中文逐字比，
    // 回傳的會是一堆單獨字母，跟畫面上一詞一卡的字卡對不起來。

    @Test
    fun `英文漏講的部分以整個詞回報`() {
        val wrong = PronunciationEvaluator.wrongChars(
            target = "I want to drink water",
            recognized = "I want water"
        )
        assertEquals(listOf("to", "drink"), wrong)
    }

    @Test
    fun `英文比對不分大小寫`() {
        val wrong = PronunciationEvaluator.wrongChars("It hurts", "IT HURTS")
        assertTrue(wrong.isEmpty())
    }

    @Test
    fun `中文仍然逐字回報`() {
        val wrong = PronunciationEvaluator.wrongChars("我想喝水", "我要喝")
        assertEquals(listOf("想", "水"), wrong)
    }
}
