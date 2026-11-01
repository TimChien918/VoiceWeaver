package com.speakmylife.app.ai

import com.speakmylife.app.data.AphasiaPracticeI18n
import com.speakmylife.app.data.AppLanguage
import com.speakmylife.app.ui.I18n
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ── 沒出聲的防護 ────────────────────────────────────────────────────────
    // 失語症患者「想說但發不出聲音」是常態不是例外。這種時候回一段他沒講過的話
    // 再配一個分數，對患者是打擊、對治療師是髒資料。

    @Test
    fun `太安靜或太短的錄音會被擋下來`() {
        assertTrue(SpeechNoiseGuard.isTooQuiet(0))
        assertTrue(SpeechNoiseGuard.isTooQuiet(SpeechNoiseGuard.MIN_PEAK_AMPLITUDE - 1))
        assertTrue(SpeechNoiseGuard.isTooShort(200))
    }

    @Test
    fun `正常音量與長度不會被擋`() {
        assertFalse(SpeechNoiseGuard.isTooQuiet(SpeechNoiseGuard.MIN_PEAK_AMPLITUDE))
        assertFalse(SpeechNoiseGuard.isTooQuiet(8000))
        assertFalse(SpeechNoiseGuard.isTooShort(1500))
    }

    @Test
    fun `構音微弱的氣音仍然放行`() {
        // 門檻刻意訂低：把真的講話當成沒講話，比偶爾放一段噪音進去嚴重得多。
        assertFalse(SpeechNoiseGuard.isTooQuiet(700))
    }

    @Test
    fun `空的辨識結果視為沒出聲`() {
        assertTrue(SpeechNoiseGuard.looksLikeSilenceHallucination("", "我想喝水"))
        assertTrue(SpeechNoiseGuard.looksLikeSilenceHallucination("   ", "我想喝水"))
    }

    @Test
    fun `Whisper 對靜音常吐的固定句被判為幻覺`() {
        listOf(
            "Thank you." to "The boy is washing his hair",
            "Thanks for watching!" to "It hurts",
            "謝謝觀看" to "我想喝水",
            "字幕由 Amara.org 社群提供" to "我想回家",
            "ご視聴ありがとうございました" to "水が飲みたい"
        ).forEach { (heard, target) ->
            assertTrue("$heard 應判為幻覺", SpeechNoiseGuard.looksLikeSilenceHallucination(heard, target))
        }
    }

    @Test
    fun `目標句本身就是那句話時不可以擋`() {
        // 「謝謝」是真的會出現在題庫裡的練習句，不能因為它也在幻覺清單裡就吃掉。
        assertFalse(SpeechNoiseGuard.looksLikeSilenceHallucination("謝謝", "謝謝"))
        assertFalse(SpeechNoiseGuard.looksLikeSilenceHallucination("Thank you.", "Thank you"))
    }

    @Test
    fun `正常的跟讀結果不會被誤判`() {
        assertFalse(SpeechNoiseGuard.looksLikeSilenceHallucination(
            "the boy is washing his hair", "The boy is washing his hair"))
        assertFalse(SpeechNoiseGuard.looksLikeSilenceHallucination("我要喝", "我想喝水"))
    }
}
