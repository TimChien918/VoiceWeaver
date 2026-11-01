package com.speakmylife.app

import com.speakmylife.app.ui.I18n
import com.speakmylife.app.data.AppLanguage
import com.speakmylife.app.data.DefaultStoryPrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 翻譯覆蓋率的守門測試。
 *
 * 這個專案用**繁中原文當翻譯鍵**，而 [I18n.tr] 找不到鍵時是「原樣回傳中文」——
 * 也就是漏翻不會報錯、不會崩潰，只會讓英日韓的使用者看到一句中文。
 * 靠肉眼是抓不完的（實測一次掃出 29 個已經包了 tr() 卻不在表裡的鍵），
 * 所以這裡把規則寫成測試。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class I18nCoverageTest {

    private val cjk = Regex("[\\u4e00-\\u9fff]")

    private fun inEnglish(zh: String): String {
        I18n.lang = AppLanguage.En
        return try { I18n.tr(zh) } finally { I18n.lang = AppLanguage.ZhTW }
    }

    private fun assertTranslated(zh: String, where: String) {
        val en = inEnglish(zh)
        assertFalse(
            "$where：「$zh」在英文介面仍然顯示中文（翻譯表裡沒有這個鍵）",
            en == zh || cjk.containsMatchIn(en),
        )
    }

    // ── 看圖說故事：整個模組原本完全沒有走翻譯層 ──────────────────

    @Test
    fun `故事主題與分鏡在英文介面不能是中文`() {
        DefaultStoryPrompts.forEach { p ->
            assertTranslated(p.title, "故事主題")
            p.frames.forEach { f -> assertTranslated(f.hint, "故事分鏡") }
        }
    }

    // ── 推給家人的成績單 ────────────────────────────────────────

    @Test
    fun `成績單的每一行都要翻得出來`() {
        listOf(
            "成績單", "復健次數", "平均分數", "圖卡正確率",
            "正向情緒字眼", "故事練習", "平均",
            "尚無趨勢資料，繼續加油 💪", "趨勢向上 📈 — 持續進步中！",
            "趨勢略降 📉 — 適度休息，明天再戰", "保持穩定 ✨",
            "今日", "本月", "本年度",
        ).forEach { assertTranslated(it, "Telegram 成績單") }
    }

    // ── 這一輪補上的其他鍵 ──────────────────────────────────────

    @Test
    fun `圖表與權限的字要翻得出來`() {
        listOf(
            "進步趨勢（平均分數）", "正向情緒字眼次數", "尚無資料",
            "授予權限", "需要相機權限", "關閉",
            "對準電腦語音中心顯示的 QR Code",
        ).forEach { assertTranslated(it, "圖表／權限") }
    }

    @Test
    fun `設定頁與執行期訊息要翻得出來`() {
        listOf(
            "診斷日誌", "分享診斷日誌", "顯示全部", "只看問題",
            "AI 直接聽（不轉文字）", "自動", "只用電腦本機", "只用雲端",
            "超大", "語音辨識", "模型", "已停用", "往上移", "往下移",
            "沒有聽到聲音，請再說一次（或把手機拿近一點）",
            "AI 回應逾時，請確認網路後再試一次。",
            "我的專屬發音（離線）",
            "匯入中文參考音檔", "匯入英文模型資料夾", "自動匯入整包資料夾",
        ).forEach { assertTranslated(it, "設定／執行期") }
    }

    // ── 佔位符 ────────────────────────────────────────────────

    @Test
    fun `帶佔位符的翻譯不能把佔位符弄丟`() {
        // %s 被翻譯吃掉的話，format() 會丟 MissingFormatArgumentException 直接閃退。
        listOf(
            "「AI 直接聽」失敗：%s" to 1,
            "語音辨識失敗：%s" to 1,
            "你可以開系統檔案頁選最外層資料夾，也可以把整包放到預設資料夾後直接掃描。預設資料夾：%s" to 1,
            "%d月" to 1,
        ).forEach { (zh, n) ->
            listOf(AppLanguage.En, AppLanguage.Ja, AppLanguage.Ko).forEach { lang ->
                I18n.lang = lang
                val out = try { I18n.tr(zh) } finally { I18n.lang = AppLanguage.ZhTW }
                val count = Regex("%[sd]").findAll(out).count()
                assertEquals("「$zh」的 $lang 翻譯佔位符數量不對：$out", n, count)
            }
        }
    }

    /**
     * 翻譯表**不可以有重複的鍵**。
     *
     * Kotlin 的 mapOf 遇到重複鍵不會報錯，是後者默默覆蓋前者。這個專案拿
     * 繁中原文當鍵，所以「同一個中文詞、不同語意」就會撞在一起——
     * 實測「關閉」同時被用成開關的 Off 與按鈕的 Close，加了第二筆之後
     * **全部的開關都變成 "Close"**，而且編譯與既有測試全部照過。
     */
    @Test
    fun `翻譯表不能有重複的鍵`() {
        val src = java.io.File("src/main/java/com/example/ui/I18n.kt").readText()
        val keys = Regex("""^\s*"((?:[^"\\]|\\.)*)"\s+to\s+arrayOf\(""", RegexOption.MULTILINE)
            .findAll(src).map { it.groupValues[1] }.toList()
        val dup = keys.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        assertTrue(
            "翻譯表有重複的鍵（後者會默默覆蓋前者）：$dup",
            dup.isEmpty(),
        )
        assertTrue("沒解析到鍵，測試本身壞了", keys.size > 500)
    }

    @Test
    fun `中文介面永遠原樣回傳`() {
        I18n.lang = AppLanguage.ZhTW
        assertEquals("授予權限", I18n.tr("授予權限"))
        // 表裡沒有的鍵也一樣不會炸
        assertEquals("這串不在表裡", I18n.tr("這串不在表裡"))
    }

    @Test
    fun `英文翻譯不能只是把中文原封不動抄過去`() {
        // 曾經有條目直接把中文填進英文欄位充數。抽查幾個常見的。
        listOf("設定", "關閉", "自動", "模型").forEach {
            val en = inEnglish(it)
            assertTrue("「$it」的英文是「$en」，看起來沒有真的翻譯", en.isNotBlank())
            assertFalse("「$it」的英文欄位還是中文", cjk.containsMatchIn(en))
        }
    }
}
