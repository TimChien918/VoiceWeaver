package com.example.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 英文特化的回歸測試。
 *
 * 這幾條都是「英文模式下原本壞掉」的行為：意圖詞與情緒關鍵字都只有中文，
 * 英文句子永遠比不中任何一個，於是幻覺防護網與情緒參考音雙雙失效。
 * 有測試才不會下次調語料時又悄悄退回去。
 */
class EnglishSpecializationTest {

    private fun vault(raw: String, location: String = "", vision: List<String> = emptyList()) =
        GlobalTagsVault(rawText = raw, locationTag = location, visionTags = vision)

    @Test
    fun `英文幻覺會被擋下`() = runBlocking {
        // 碎詞只提到牙齒，重組卻生出「叫救護車」——憑空冒出的強烈意圖
        val ok = NativeRuleCritic.audit("Please call an ambulance.", vault("tooth hurt"))
        assertFalse("憑空的 ambulance 應該被擋", ok)
    }

    @Test
    fun `英文正常重組會通過`() = runBlocking {
        val ok = NativeRuleCritic.audit("My tooth hurts.", vault("tooth hurt"))
        assertTrue("貼著碎詞的重組應該放行", ok)
    }

    @Test
    fun `意圖詞有情境支撐時放行`() = runBlocking {
        // 碎詞沒講 doctor，但地點是醫院 → 有出處，不算幻覺
        val ok = NativeRuleCritic.audit("I want to see a doctor.", vault("see", location = "hospital"))
        assertTrue("地點提供了出處就該放行", ok)
    }

    @Test
    fun `整字比對不會被子字串誤判`() = runBlocking {
        // "ill" 不該因為 raw 裡有 "still" 就被當成有出處
        val ok = NativeRuleCritic.audit("I feel ill.", vault("I am still here"))
        assertFalse("still 不該讓 ill 過關", ok)
    }

    @Test
    fun `英文情緒偵測`() {
        assertEquals("难过", LocalSovitsHelper.detectEmotion("I am so tired and sad"))
        assertEquals("恐惧", LocalSovitsHelper.detectEmotion("Help me, I am scared"))
        assertEquals("开心", LocalSovitsHelper.detectEmotion("Thank you, that is wonderful"))
    }

    @Test
    fun `英文情緒不被子字串誤判`() {
        // "saddle" 含 "sad"，但不該判成難過
        assertEquals(LocalSovitsHelper.NEUTRAL, LocalSovitsHelper.detectEmotion("the saddle is brown"))
    }

    @Test
    fun `中文行為沒有退步`() {
        assertEquals("难过", LocalSovitsHelper.detectEmotion("我好累，想哭"))
        assertEquals("恐惧", LocalSovitsHelper.detectEmotion("救命，我好怕"))
    }
}
