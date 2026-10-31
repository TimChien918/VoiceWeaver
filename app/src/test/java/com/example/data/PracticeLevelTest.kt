package com.example.data

import com.example.ui.I18n
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「中英日韓四級題庫」：四個等級、每級至少 8 題，而且每一題在英日韓都有譯文。
 * 少一個譯文不會報錯，只會讓那個語言的使用者看到一題中文、唸了永遠 0 分。
 */
class PracticeLevelTest {

    @After
    fun reset() { I18n.lang = AppLanguage.ZhTW }

    @Test
    fun `四個等級每級至少八題`() {
        assertEquals(4, PracticeLevel.values().size)
        PracticeLevel.values().forEach { level ->
            val items = AphasiaPractice.levelItems(level)
            assertTrue("$level 只有 ${items.size} 題", items.size >= 8)
            assertEquals("$level 有重複題目", items.size, items.distinct().size)
        }
    }

    @Test
    fun `每一題在英日韓都有譯文`() {
        listOf(AppLanguage.En, AppLanguage.Ja, AppLanguage.Ko).forEach { lang ->
            I18n.lang = lang
            PracticeLevel.values().forEach { level ->
                AphasiaPractice.levelItems(level).forEach { zh ->
                    assertNotNull("$lang 缺「$zh」（$level）的譯文", AphasiaPracticeI18n.gloss(zh))
                }
            }
        }
    }

    @Test
    fun `畫面預覽的題目都屬於該級`() {
        PracticeLevel.values().forEach { level ->
            val preview = AphasiaPractice.levelPreview(level)
            assertEquals(level.previewCount, preview.size)
            assertTrue(AphasiaPractice.levelItems(level).containsAll(preview))
        }
    }

    @Test
    fun `平均分對應到四級`() {
        assertEquals(PracticeLevel.Words, AphasiaPractice.levelFor(0.0))
        assertEquals(PracticeLevel.Phrases, AphasiaPractice.levelFor(50.0))
        assertEquals(PracticeLevel.Short, AphasiaPractice.levelFor(70.0))
        assertEquals(PracticeLevel.Situational, AphasiaPractice.levelFor(85.0))
        // 第 2 級出題要真的從詞組池出
        val picked = AphasiaPractice.pick(50.0, "")
        assertTrue(AphasiaPractice.phrases.containsAll(picked))
    }
}
