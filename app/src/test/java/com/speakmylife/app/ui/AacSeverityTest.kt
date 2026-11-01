package com.speakmylife.app.ui

import com.speakmylife.app.data.SeverityMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 嚴重程度真的會改到 AAC 的版面。
 *
 * 原本兩個格線都寫死 `Adaptive(110.dp)`，完全沒讀 severityMode——
 * 使用者把模式切到中度／重度，圖卡一點都沒變大，等於這個設定是假的。
 */
class AacSeverityTest {

    @Test
    fun `越嚴重卡片越大`() {
        val mild = aacCardMinSize(SeverityMode.Mild).value
        val moderate = aacCardMinSize(SeverityMode.Moderate).value
        val severe = aacCardMinSize(SeverityMode.Severe).value
        assertTrue("$mild < $moderate < $severe", mild < moderate && moderate < severe)
    }

    @Test
    fun `中度的卡片至少要大一半，看得出來才算數`() {
        // 差幾個 dp 使用者根本感覺不到，等於還是「調了沒反應」
        val ratio = aacCardMinSize(SeverityMode.Moderate).value / aacCardMinSize(SeverityMode.Mild).value
        assertTrue("中度只放大到 ${ratio}x，太少", ratio >= 1.5f)
    }

    @Test
    fun `字級跟著卡片一起放大`() {
        // 只放大卡片不放大字，就只是多出一堆空白，看起來仍然像沒改
        assertEquals(1f, aacScaleFor(SeverityMode.Mild), 0f)
        assertTrue(aacScaleFor(SeverityMode.Moderate) > 1f)
        assertTrue(aacScaleFor(SeverityMode.Severe) > aacScaleFor(SeverityMode.Moderate))
    }

    @Test
    fun `輕度維持原本的尺寸`() {
        // 輕度是「功能完整」的模式，不該因為這次改動而變樣
        assertEquals(110f, aacCardMinSize(SeverityMode.Mild).value, 0f)
    }
}
