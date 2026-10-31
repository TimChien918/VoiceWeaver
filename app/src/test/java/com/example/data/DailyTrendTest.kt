package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 復健軌跡趨勢圖的逐日資料。這份字串存在 DataStore 裡、跨版本保留，
 * 解析壞掉的代價是治療師的整張趨勢圖消失，所以格式邊界都要釘住。
 */
class DailyTrendTest {

    @Test
    fun `同一天累加反應時間與選句`() {
        var raw = ""
        raw = DailyTrendCodec.addReaction(raw, "2026-10-30", 4000)
        raw = DailyTrendCodec.addReaction(raw, "2026-10-30", 2000)
        raw = DailyTrendCodec.addCandidate(raw, "2026-10-30", firstHit = true)
        raw = DailyTrendCodec.addCandidate(raw, "2026-10-30", firstHit = false)
        val d = DailyTrendCodec.decode(raw).single()
        assertEquals(2L, d.speakCount)
        assertEquals(3000L, d.avgReactionMs)
        assertEquals(2L, d.candTotal)
        assertEquals(0.5f, d.hitRate!!, 0.0001f)
    }

    @Test
    fun `不同天各自一格且依日期排序`() {
        var raw = DailyTrendCodec.addCandidate("", "2026-10-31", true)
        raw = DailyTrendCodec.addReaction(raw, "2026-10-29", 1500)
        val days = DailyTrendCodec.decode(raw)
        assertEquals(listOf("2026-10-29", "2026-10-31"), days.map { it.day })
        // 只有選句的那天沒有反應時間 → null，不是 0（0 秒會被畫成「超快」）
        assertNull(days[1].avgReactionMs)
        assertNull(days[0].hitRate)
    }

    @Test
    fun `髒資料略過而不是整份讀不出來`() {
        val raw = "2026-10-01,1,1000,0,0;garbage;2026-13,1,2,3;2026-10-02,1,-5,0,0;2026-10-03,2,4000,4,1"
        val days = DailyTrendCodec.decode(raw)
        assertEquals(listOf("2026-10-01", "2026-10-03"), days.map { it.day })
        assertTrue(DailyTrendCodec.decode("").isEmpty())
    }

    @Test
    fun `編碼後解碼不變`() {
        val days = listOf(
            DailyTrendDay("2026-09-01", 3, 9000, 5, 2),
            DailyTrendDay("2026-09-02", 0, 0, 1, 1),
        )
        assertEquals(days, DailyTrendCodec.decode(DailyTrendCodec.encode(days)))
    }

    @Test
    fun `超過保留天數只留最新的`() {
        val days = (0 until DailyTrendCodec.MAX_DAYS + 20).map { i ->
            // 用連續的假日期（字串可排序即可）
            DailyTrendDay("2%03d-01-01".format(i), 1, 1000, 0, 0)
        }
        val kept = DailyTrendCodec.decode(DailyTrendCodec.encode(days))
        assertEquals(DailyTrendCodec.MAX_DAYS, kept.size)
        assertEquals(days.last().day, kept.last().day)
    }

    @Test
    fun `匯出列合併行為數據與復健命中率`() {
        val ts = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.OCTOBER, 30, 10, 0, 0)
        }.timeInMillis
        val day = DailyTrendCodec.dayKey(ts)
        assertEquals("2026-10-30", day)
        val rows = DailyTrendCodec.rows(
            days = listOf(DailyTrendDay(day, 1, 2500, 4, 3)),
            rehab = listOf(ts to 90, ts to 40, ts to DailyTrendCodec.REHAB_HIT_SCORE, ts to 10),
        )
        val r = rows.single()
        assertEquals(2500L, r.avgReactionMs)
        assertEquals(0.75f, r.candHitRate!!, 0.0001f)
        assertEquals(4, r.rehabCount)
        assertEquals(0.5f, r.rehabHitRate!!, 0.0001f)   // 90 與 70 命中，40 與 10 沒有
    }

    @Test
    fun `只有復健沒有行為數據的日子也要出現`() {
        val ts = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.OCTOBER, 1, 9, 0, 0)
        }.timeInMillis
        val r = DailyTrendCodec.rows(emptyList(), listOf(ts to 80)).single()
        assertNull(r.avgReactionMs)
        assertNull(r.candHitRate)
        assertEquals(1f, r.rehabHitRate!!, 0.0001f)
    }
}
