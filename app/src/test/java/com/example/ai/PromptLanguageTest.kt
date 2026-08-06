package com.example.ai

import com.example.data.AppLanguage
import com.example.safety.SafetyGuard
import com.example.ui.I18n
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prompt 英文化之後要守住的行為。
 *
 * 這裡測的是**指令用英文寫、輸出語言仍然跟著介面走**這兩件事同時成立。
 * 它們很容易在之後被人「順手改回去」——尤其安全 prompt，
 * 因為它黏在每一次 LLM 呼叫最前面，改壞了每個功能都會受影響。
 */
class PromptLanguageTest {

    private val original = I18n.lang

    @After fun restore() { I18n.lang = original }

    private fun hasCjk(text: String) = text.any { it.code in 0x4E00..0x9FFF }

    // ── 安全 prompt ─────────────────────────────────────────────────────

    @Test
    fun `安全 prompt 的指令是英文的`() {
        I18n.lang = AppLanguage.En
        val prompt = SafetyGuard.DEFENSIVE_SYSTEM_PROMPT
        assertFalse("英文介面下不該有中文指令：\n$prompt", hasCjk(prompt))
        assertTrue(prompt.contains("aphasia"))
    }

    @Test
    fun `安全 prompt 不再寫死輸出語言`() {
        // 舊版第 4 條是「輸出永遠是繁體中文」。它黏在每一次 LLM 呼叫的最前面，
        // 等於每次都在跟各處的 outputLanguageDirective 對打——
        // 英文介面拿到中文回應有一部分就是這樣來的。
        listOf(AppLanguage.En, AppLanguage.Ja, AppLanguage.Ko, AppLanguage.ZhTW).forEach { lang ->
            I18n.lang = lang
            val p = SafetyGuard.DEFENSIVE_SYSTEM_PROMPT
            assertFalse("$lang：不該出現「繁體中文」這種寫死的輸出語言", p.contains("繁體中文"))
            assertFalse("$lang：不該出現 Traditional Chinese", p.contains("Traditional Chinese"))
        }
    }

    @Test
    fun `安全 prompt 的專線說法跟著介面語言`() {
        I18n.lang = AppLanguage.En
        assertTrue(
            "英文介面應給英文的求助說法",
            SafetyGuard.DEFENSIVE_SYSTEM_PROMPT.contains("crisis line"),
        )
        I18n.lang = AppLanguage.ZhTW
        assertTrue(
            "中文介面應保留 1925",
            SafetyGuard.DEFENSIVE_SYSTEM_PROMPT.contains("1925"),
        )
    }

    @Test
    fun `替別人求救的例外沒有被弄丟`() {
        // 這條是整段 prompt 裡最重要的一句：使用者講不出話，這是他唯一的求救管道。
        // 擋下來會害另一個人叫不到救護車。
        listOf(AppLanguage.En, AppLanguage.ZhTW).forEach { lang ->
            I18n.lang = lang
            val p = SafetyGuard.DEFENSIVE_SYSTEM_PROMPT
            assertTrue("$lang：缺少「替別人求救要放行」的例外", p.contains("SOMEONE ELSE"))
            assertTrue("$lang：缺少救護車的具體例子", p.contains("ambulance"))
        }
    }

    @Test
    fun `安全 prompt 仍然擋住鼓勵放棄的字眼與用藥建議`() {
        I18n.lang = AppLanguage.En
        val p = SafetyGuard.DEFENSIVE_SYSTEM_PROMPT
        assertTrue(p.contains("self-harm"))
        assertTrue(p.contains("dosage") || p.contains("dosages"))
    }

    // ── 輸出語言指令 ────────────────────────────────────────────────────

    @Test
    fun `英文的語言名稱可以嵌進英文 prompt`() {
        I18n.lang = AppLanguage.En;    assertEquals("English", I18n.outputLanguageNameEn())
        I18n.lang = AppLanguage.Ja;    assertEquals("Japanese", I18n.outputLanguageNameEn())
        I18n.lang = AppLanguage.Ko;    assertEquals("Korean", I18n.outputLanguageNameEn())
        I18n.lang = AppLanguage.ZhTW;  assertEquals("Traditional Chinese", I18n.outputLanguageNameEn())
    }

    @Test
    fun `輸出語言指令仍然用目標語言本身寫`() {
        // 這是實測下來最穩的組合：指令主體英文、最後一行用目標語言的祈使句。
        // 改成全英文（"answer in Japanese"）會讓模型的遵從度變差。
        I18n.lang = AppLanguage.Ja
        assertTrue(I18n.outputLanguageDirective("text").contains("日本語"))
        I18n.lang = AppLanguage.Ko
        assertTrue(I18n.outputLanguageDirective("text").contains("한국어"))
        I18n.lang = AppLanguage.ZhTW
        assertTrue(I18n.outputLanguageDirective("text").contains("繁體中文"))
        I18n.lang = AppLanguage.En
        assertTrue(I18n.outputLanguageDirective("text").contains("English"))
    }

    @Test
    fun `指令會指名到實際的 JSON 欄位`() {
        // 只說「用英文回答」時，模型常常只把 reasoning 換成英文而 candidates 照舊。
        I18n.lang = AppLanguage.En
        assertTrue(I18n.outputLanguageDirective("candidates[].text").contains("candidates[].text"))
    }

    // ── 緊急通報 ────────────────────────────────────────────────────────

    private fun alert() = com.example.safety.CrisisAction(
        userText = "I want to end it",
        latitude = 25.03, longitude = 121.56, poiTag = "Taipei Main Station",
    ).toTelegramMessage()

    @Test
    fun `緊急通報跟著介面語言`() {
        // 一封讀不懂的求救訊息等同沒發
        I18n.lang = AppLanguage.En
        val en = alert()
        assertFalse("英文介面不該送出中文通報：\n$en", hasCjk(en))
        assertTrue(en.contains("emergency alert"))
        assertTrue(en.contains("immediately"))

        I18n.lang = AppLanguage.ZhTW
        val zh = alert()
        assertTrue("中文介面應維持中文", zh.contains("緊急通報"))
    }

    @Test
    fun `緊急通報保留座標連結與原始輸入`() {
        // 這兩項是通報的全部價值：人在哪裡、他說了什麼。翻譯不能把它們弄丟。
        listOf(AppLanguage.En, AppLanguage.ZhTW, AppLanguage.Ja, AppLanguage.Ko).forEach { lang ->
            I18n.lang = lang
            val msg = alert()
            assertTrue("$lang：缺少 Google Maps 連結", msg.contains("maps.google.com/?q=25.03,121.56"))
            assertTrue("$lang：缺少原始輸入", msg.contains("I want to end it"))
            assertTrue("$lang：缺少 POI", msg.contains("Taipei Main Station"))
        }
    }

    @Test
    fun `沒有 GPS 時的說法也有翻譯`() {
        I18n.lang = AppLanguage.En
        val msg = com.example.safety.CrisisAction("help", null, null, "").toTelegramMessage()
        assertFalse("英文介面不該出現中文的「位置未知」：\n$msg", hasCjk(msg))
        assertTrue(msg.contains("Location unknown"))
    }

    // ── 離線練習句的語言前提 ────────────────────────────────────────────

    @Test
    fun `每個介面語言都有對應的離線辨識模型`() {
        // 練習句改成一律跟著介面語言，前提是裝置端 Vosk 有那個語言的模型。
        // 之後若加了第五個介面語言卻沒補模型，使用者會拿到唸完固定 0 分的練習句——
        // 那是靜默失敗，必須在這裡被擋下來。
        AppLanguage.entries.forEach { lang ->
            val spec = VoskModelSpec.forLanguage(lang)
            assertTrue("$lang 的模型目錄看起來不對：${spec.dirName}", spec.dirName.startsWith("vosk-model"))
            assertTrue("$lang 缺少下載網址", spec.url.startsWith("https://"))
        }
        // 四個語言必須對到四個不同的模型，不可以有人偷偷退回中文
        val dirs = AppLanguage.entries.map { VoskModelSpec.forLanguage(it).dirName }
        assertEquals("每個語言都該有自己的模型：$dirs", dirs.size, dirs.toSet().size)
    }

    // ── 中文介面沒有退步 ────────────────────────────────────────────────

    @Test
    fun `中文介面的求助說法沒有被英文化`() {
        I18n.lang = AppLanguage.ZhTW
        assertEquals(
            "我聽到了，建議聯絡家屬或撥打 1925 安心專線",
            I18n.tr("我聽到了，建議聯絡家屬或撥打 1925 安心專線"),
        )
    }

    @Test
    fun `英文介面的求助說法有翻譯不是回中文原文`() {
        // I18n.tr 查無譯文時會安全退回中文原文——那對這句話來說是失敗，
        // 英文使用者會在自己的介面上看到一句看不懂的中文。
        I18n.lang = AppLanguage.En
        val translated = I18n.tr("我聽到了，建議聯絡家屬或撥打 1925 安心專線")
        assertFalse("應有英文譯文，實得：$translated", hasCjk(translated))
    }
}
