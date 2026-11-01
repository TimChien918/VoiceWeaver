package com.speakmylife.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 釘住「模型 JSON 被截斷時不可以外漏給病人」這條線。
 * 這裡的第一個案例是實際在手機上拍到的畫面，不是編出來的。
 */
class CoTJsonTest {

    /** 實際發生過：reasoning 太長，token 燒完，reconstructed 根本還沒開始寫。 */
    private val truncatedInReasoning =
        """{"reasoning":"碎詞與頭痛有關，地點在醫院，周邊物件包含樂器、居家用品、自拍、微笑、嘴巴、耳朵，且常用表達參考包含「我會痛」、「我想回家」、「可以"""

    @Test
    fun `reasoning 中途被截斷時撈不到句子`() {
        // 撈不到才是對的——上層會據此換供應商 / 走 fallback，而不是把 JSON 唸出來
        assertNull(salvageReconstructed(truncatedInReasoning))
    }

    @Test
    fun `截斷的 JSON 會被認出來不是句子`() {
        assertTrue(looksLikeRawJson(truncatedInReasoning))
    }

    @Test
    fun `reconstructed 寫完後才被截斷仍能救回`() {
        val raw = """{"reasoning":"很長的分析","reconstructed":"我頭很痛，可以帶我去看醫生嗎？","confid"""
        assertEquals("我頭很痛，可以帶我去看醫生嗎？", salvageReconstructed(raw))
    }

    @Test
    fun `reconstructed 寫到一半被截斷會救回已寫出的部分`() {
        val raw = """{"reasoning":"分析","reconstructed":"我想回"""
        assertEquals("我想回", salvageReconstructed(raw))
    }

    @Test
    fun `完整 JSON 也撈得到`() {
        val raw = """{"reasoning":"分析","reconstructed":"我想喝水。","confidence":88}"""
        assertEquals("我想喝水。", salvageReconstructed(raw))
    }

    @Test
    fun `句子裡的跳脫引號要還原`() {
        val raw = """{"reconstructed":"他說\"好\"。","confidence":80}"""
        assertEquals("他說\"好\"。", salvageReconstructed(raw))
    }

    @Test
    fun `reconstructed 是空字串時視同撈不到`() {
        val raw = """{"reasoning":"看不懂","reconstructed":"","confidence":10}"""
        assertNull(salvageReconstructed(raw))
    }

    @Test
    fun `正常句子不會被誤判成 JSON`() {
        assertFalse(looksLikeRawJson("我想喝水。"))
    }
}
