package com.speakmylife.app

import com.speakmylife.app.ai.countPositiveWords
import com.speakmylife.app.safety.SafetyGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 這一輪掃描抓到的 bug 的回歸測試。每一條都對應一個實測確認過的錯誤行為。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SweepRegressionTest {

    // ── 成績單的「正向情緒字眼」指標 ────────────────────────────────

    @Test
    fun `否定句裡的好不算正向`() {
        // 實測原本三句都回 1。詞典裡放了單獨一個「好」，而比對是純子字串搜尋——
        // 註解卻寫著「我不好裡的好這種反例不會誤計」，事實正好相反。
        assertEquals(0, countPositiveWords("我不好"))
        assertEquals(0, countPositiveWords("不好意思"))
    }

    @Test
    fun `好痛不能被記成正向情緒`() {
        // 這是最嚴重的一個方向：疼痛訊號被反向記進治療師看的報表。
        assertEquals(0, countPositiveWords("我這裡好痛"))
        assertEquals(0, countPositiveWords("好痛"))
    }

    @Test
    fun `真正的正向說法照樣要算到`() {
        assertEquals(1, countPositiveWords("我很好"))
        assertEquals(1, countPositiveWords("今天好多了"))
        assertEquals(1, countPositiveWords("太好了"))
        assertEquals(1, countPositiveWords("謝謝你"))
        assertEquals(1, countPositiveWords("我喜歡"))
    }

    @Test
    fun `被否定的正向詞不算`() {
        assertEquals(0, countPositiveWords("我不喜歡"))
        assertEquals(0, countPositiveWords("沒好多了"))
    }

    @Test
    fun `同一句出現兩次要算兩次`() {
        assertEquals(2, countPositiveWords("謝謝，真的謝謝"))
    }

    // ── 危機偵測 ────────────────────────────────────────────────

    @Test
    fun `jumping off 要擋得下來`() {
        // 原本寫 jump\s*off，命中不了動名詞——而 "thinking of jumping off"
        // 才是實際會講出來的說法。網頁版的回歸測試先抓到，兩邊都有同一個洞。
        listOf(
            "I am thinking of jumping off the roof",
            "he jumped off the bridge",
            "jump off",
        ).forEach {
            assertTrue("「$it」應該被判成危機", SafetyGuard.containsCrisisSignal(it))
        }
    }

    @Test
    fun `hanging myself 也要擋`() {
        assertTrue(SafetyGuard.containsCrisisSignal("I keep thinking about hanging myself"))
    }

    @Test
    fun `一般句子不會因為這次放寬而被誤判`() {
        listOf("the bus stop is off the main road", "I want to jump for joy",
               "turn off the light", "I need some water").forEach {
            assertTrue("「$it」不該被當成危機",
                !SafetyGuard.containsCrisisSignal(it))
        }
    }
}
