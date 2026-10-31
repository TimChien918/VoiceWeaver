package com.example.ai

import com.example.data.DailyTrendDay
import com.example.net.TelegramClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「兼顧安全與隱私」三項承諾裡可以用純函式釘住的部分，以及趨勢圖的資料點。
 */
class PrivacyGuardsTest {

    // ── 危機訊息僅傳送給指定家人 ─────────────────────────────────

    @Test
    fun `沒指定家人時沒有收件者（不退回建置者的預設對象）`() {
        assertEquals("", TelegramClient.resolveTarget(null))
        assertEquals("", TelegramClient.resolveTarget("   "))
        assertEquals("-100123", TelegramClient.resolveTarget(" -100123 "))
    }

    // ── 錄音評分後刪除 ─────────────────────────────────────────

    @Test
    fun `評分與理解用的暫存錄音會被認出來`() {
        assertTrue(AudioRecorderManager.isDisposableClip("aphasia_voice_1730000000000.m4a"))
        assertTrue(AudioRecorderManager.isDisposableClip("aphasia_voice_1730000000000.aac"))
        assertTrue(AudioRecorderManager.isDisposableClip("direct_1730000000000.wav"))
    }

    @Test
    fun `其他快取與使用者資料不會被當成暫存錄音`() {
        // TTS 快取、危機語音（傳完由呼叫端刪）、生成圖片、報表都不歸這裡管
        assertFalse(AudioRecorderManager.isDisposableClip("gpt_sovit_last.wav"))
        assertFalse(AudioRecorderManager.isDisposableClip("crisis_voice_20261031.m4a"))
        assertFalse(AudioRecorderManager.isDisposableClip("intent_1730000000000.png"))
        assertFalse(AudioRecorderManager.isDisposableClip("aphasia_voice_notes.txt"))
        assertFalse(AudioRecorderManager.isDisposableClip("direct_1730000000000.m4a"))
    }

    // ── 復健軌跡趨勢圖的資料點 ─────────────────────────────────

    private fun ts(y: Int, m: Int, d: Int): Long = java.util.Calendar.getInstance().apply {
        set(y, m - 1, d, 12, 0, 0)
    }.timeInMillis

    @Test
    fun `沒資料的日子不畫點也不補零`() {
        val days = listOf(
            DailyTrendDay("2026-10-29", speakCount = 2, speakSumMs = 6000),
            DailyTrendDay("2026-10-30", candTotal = 4, candHit = 1),
            DailyTrendDay("2026-10-31", speakCount = 1, speakSumMs = 2000, candTotal = 2, candHit = 2),
        )
        val now = ts(2026, 10, 31)
        val reaction = reactionTrendBuckets(days, now)
        assertEquals(listOf("10/29", "10/31"), reaction.map { it.labelShort })
        assertEquals(listOf(3f, 2f), reaction.map { it.value })
        val hit = selectionHitTrendBuckets(days, now)
        assertEquals(listOf("10/30", "10/31"), hit.map { it.labelShort })
        assertEquals(listOf(25f, 100f), hit.map { it.value })
    }

    @Test
    fun `超出視窗的舊資料不畫`() {
        val days = listOf(
            DailyTrendDay("2026-08-01", speakCount = 1, speakSumMs = 9000),
            DailyTrendDay("2026-10-31", speakCount = 1, speakSumMs = 1000),
        )
        val pts = reactionTrendBuckets(days, ts(2026, 10, 31), windowDays = 30)
        assertEquals(listOf("10/31"), pts.map { it.labelShort })
    }
}
